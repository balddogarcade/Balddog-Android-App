-- Migration 025: Persist avatar_seed in room slot JSON (update_room_slot)
-- Ensures player avatar consistency between user profile and room lobby slots

create or replace function public.update_room_slot(
    p_room_id uuid,
    p_action text,
    p_slot_index integer default null,
    p_ready boolean default null,
    p_status text default null,
    p_endpoint text default null,
    p_voice_endpoint text default null
)
returns public.rooms
language plpgsql
security definer
set search_path = public
as $$
declare
    v_uid uuid := auth.uid();
    v_username text;
    v_avatar_seed text;
    v_room public.rooms;
    v_slots jsonb;
    v_my_slot_key text;
    v_target_key text;
    v_slot_count integer;
    v_existing_endpoint text;
    v_existing_voice text;
    v_eff_endpoint text;
    v_eff_voice text;
    v_new_slot jsonb;
    v_next_ready boolean;
    v_next_status text;
    v_i integer;
begin
    if v_uid is null then
        raise exception 'Not authenticated';
    end if;

    select p.username, p.avatar_seed
      into v_username, v_avatar_seed
    from public.profiles p
    where p.id = v_uid
      and p.is_anonymous = false;

    if not found then
        raise exception 'Registered user required';
    end if;

    select *
      into v_room
    from public.rooms r
    where r.id = p_room_id
    for update;

    if not found then
        raise exception 'Room not found';
    end if;

    v_slots := case
        when jsonb_typeof(v_room.slots_json) = 'object' then v_room.slots_json
        else '{}'::jsonb
    end;
    v_next_status := v_room.status;

    select e.key
      into v_my_slot_key
    from jsonb_each(v_slots) e
    where e.value ->> 'user_id' = v_uid::text
    limit 1;

    if p_action in ('join', 'claim') then
        if v_room.status <> 'waiting' then
            raise exception 'Room is not accepting players';
        end if;

        if p_slot_index is null then
            if v_my_slot_key is not null then
                v_target_key := v_my_slot_key;
            else
                for v_i in 0 .. (v_room.max_players - 1) loop
                    if not (v_slots ? v_i::text) then
                        v_target_key := v_i::text;
                        exit;
                    end if;
                end loop;
                if v_target_key is null then
                    raise exception 'Room is full';
                end if;
            end if;
        else
            if p_slot_index < 0 or p_slot_index >= v_room.max_players then
                raise exception 'Invalid slot index';
            end if;
            v_target_key := p_slot_index::text;
        end if;

        if (v_slots ? v_target_key)
           and (v_slots -> v_target_key ->> 'user_id') <> v_uid::text then
            raise exception 'Slot already occupied';
        end if;

        if v_my_slot_key is not null then
            v_existing_endpoint := nullif(btrim(coalesce(v_slots -> v_my_slot_key ->> 'endpoint', '')), '');
            v_existing_voice := nullif(btrim(coalesce(v_slots -> v_my_slot_key ->> 'voice_endpoint', '')), '');
            if v_my_slot_key <> v_target_key then
                v_slots := v_slots - v_my_slot_key;
            end if;
        end if;

        v_eff_endpoint := coalesce(nullif(left(btrim(coalesce(p_endpoint, '')), 128), ''), v_existing_endpoint, '');
        v_eff_voice := coalesce(nullif(left(btrim(coalesce(p_voice_endpoint, '')), 128), ''), v_existing_voice);
        v_next_ready := case
            when v_uid = v_room.host_id then true
            else coalesce(p_ready, false)
        end;

        v_new_slot := jsonb_build_object(
            'user_id', v_uid::text,
            'username', v_username,
            'endpoint', v_eff_endpoint,
            'ready', v_next_ready
        );
        if v_avatar_seed is not null and btrim(v_avatar_seed) <> '' then
            v_new_slot := v_new_slot || jsonb_build_object('avatar_seed', btrim(v_avatar_seed));
        end if;
        if v_eff_voice is not null then
            v_new_slot := v_new_slot || jsonb_build_object('voice_endpoint', v_eff_voice);
        end if;
        v_slots := v_slots || jsonb_build_object(v_target_key, v_new_slot);

    elsif p_action = 'ready' then
        if v_room.status <> 'waiting' then
            raise exception 'Room is not in waiting state';
        end if;
        if v_my_slot_key is null then
            raise exception 'User is not seated in this room';
        end if;
        v_next_ready := coalesce(
            p_ready,
            not coalesce((v_slots -> v_my_slot_key ->> 'ready')::boolean, false)
        );
        v_slots := jsonb_set(v_slots, array[v_my_slot_key, 'ready'], to_jsonb(v_next_ready));

    elsif p_action = 'leave' then
        if v_my_slot_key is null then
            raise exception 'User is not seated in this room';
        end if;
        v_slots := v_slots - v_my_slot_key;

    elsif p_action = 'kick' then
        if v_uid <> v_room.host_id then
            raise exception 'Only host can kick players';
        end if;
        if p_slot_index is null or p_slot_index < 0 or p_slot_index >= v_room.max_players then
            raise exception 'Invalid slot index';
        end if;
        if (v_slots -> p_slot_index::text ->> 'user_id') = v_uid::text then
            raise exception 'Host cannot kick self';
        end if;
        v_slots := v_slots - p_slot_index::text;

    elsif p_action in ('status', 'start', 'close_stale') then
        v_next_status := coalesce(
            nullif(btrim(coalesce(p_status, '')), ''),
            case
                when p_action = 'start' then 'playing'
                when p_action = 'close_stale' then 'closed'
                else v_room.status
            end
        );
        if v_next_status not in ('waiting', 'playing', 'closed') then
            raise exception 'Invalid room status';
        end if;

        if v_uid <> v_room.host_id then
            if v_next_status = 'closed'
               and v_my_slot_key is not null
               and v_room.status = 'waiting'
               and v_room.updated_at < now() - interval '12 seconds' then
                null;
            else
                raise exception 'Only host can change room status';
            end if;
        end if;

        if v_next_status = 'playing' then
            if v_room.status <> 'waiting' then
                raise exception 'Room already started or closed';
            end if;
            select count(*) into v_slot_count from jsonb_object_keys(v_slots);
            if v_slot_count < 2 then
                raise exception 'At least 2 players required to start';
            end if;
            if exists (
                select 1
                from jsonb_each(v_slots) e
                where coalesce((e.value ->> 'ready')::boolean, false) = false
            ) then
                raise exception 'All seated players must be ready';
            end if;
        end if;

    else
        raise exception 'Unsupported room action: %', p_action;
    end if;

    update public.rooms
    set slots_json = v_slots,
        status = v_next_status,
        updated_at = now()
    where id = p_room_id
    returning * into v_room;

    return v_room;
end;
$$;

revoke all on function public.update_room_slot(uuid, text, integer, boolean, text, text, text) from public, anon;
grant execute on function public.update_room_slot(uuid, text, integer, boolean, text, text, text) to authenticated;
