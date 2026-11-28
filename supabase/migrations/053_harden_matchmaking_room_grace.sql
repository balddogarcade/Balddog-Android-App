-- 053_harden_matchmaking_room_grace.sql
-- Increase stale-host grace window in update_room_slot from 12s to 20s to match RoomLobbyScreen
-- and avoid premature room closure when a matched host is resolving STUN/claiming their slot.

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
    v_room public.rooms%rowtype;
    v_slots jsonb;
    v_my_slot_key text;
    v_target_key text;
    v_username text;
    v_avatar_seed text;
    v_existing_endpoint text;
    v_existing_voice text;
    v_existing_ready boolean;
    v_eff_endpoint text;
    v_eff_voice text;
    v_next_ready boolean;
    v_new_slot jsonb;
    v_next_status text;
    v_next_host_id uuid;
    v_next_host_key text;
    v_slot_count integer;
begin
    if v_uid is null then
        raise exception 'Not authenticated';
    end if;

    if not public._is_registered_user(v_uid) then
        raise exception 'Registered account required';
    end if;

    select * into v_room
    from public.rooms
    where id = p_room_id
    for update;

    if not found then
        raise exception 'Room not found';
    end if;

    select p.username, p.avatar_seed
    into v_username, v_avatar_seed
    from public.profiles p
    where p.id = v_uid;

    v_username := coalesce(nullif(btrim(v_username), ''), 'Player');
    v_slots := coalesce(v_room.slots_json, '{}'::jsonb);
    v_next_status := v_room.status;
    v_next_host_id := v_room.host_id;

    select k
    into v_my_slot_key
    from jsonb_each(v_slots) e(k, val)
    where e.val ->> 'user_id' = v_uid::text
    limit 1;

    if p_action in ('join', 'claim') then
        if v_room.status <> 'waiting' then
            raise exception 'Room is not in waiting state';
        end if;

        if v_uid = any(coalesce(v_room.banned_user_ids, '{}'::uuid[])) then
            raise exception 'You are banned from this room';
        end if;

        if p_slot_index is null then
            if v_my_slot_key is not null then
                v_target_key := v_my_slot_key;
            else
                select gs::text
                into v_target_key
                from generate_series(0, greatest(v_room.max_players - 1, 0)) gs
                where not (v_slots ? gs::text)
                order by gs
                limit 1;

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
            v_existing_ready := coalesce((v_slots -> v_my_slot_key ->> 'ready')::boolean, false);
            if v_my_slot_key <> v_target_key then
                v_slots := v_slots - v_my_slot_key;
            end if;
        else
            v_existing_ready := false;
        end if;

        v_eff_endpoint := coalesce(nullif(left(btrim(coalesce(p_endpoint, '')), 128), ''), v_existing_endpoint, '');
        v_eff_voice := coalesce(nullif(left(btrim(coalesce(p_voice_endpoint, '')), 128), ''), v_existing_voice);
        v_next_ready := case
            when v_uid = v_next_host_id then true
            else coalesce(p_ready, v_existing_ready, false)
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
        if v_my_slot_key is not null then
            v_slots := v_slots - v_my_slot_key;
        end if;

        select count(*) into v_slot_count from jsonb_object_keys(v_slots);
        if v_slot_count = 0 then
            v_next_status := 'closed';
        elsif v_uid = v_room.host_id and v_room.status = 'waiting' then
            select k
            into v_next_host_key
            from jsonb_each(v_slots) e(k, val)
            order by (e.key)::integer asc
            limit 1;

            if v_next_host_key is not null then
                v_next_host_id := (v_slots -> v_next_host_key ->> 'user_id')::uuid;
                v_slots := jsonb_set(v_slots, array[v_next_host_key, 'ready'], 'true'::jsonb);
            end if;
        end if;

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
               and v_room.updated_at < now() - interval '20 seconds' then
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
        host_id = v_next_host_id,
        updated_at = now()
    where id = p_room_id
    returning * into v_room;

    return v_room;
end;
$$;

revoke all on function public.update_room_slot(uuid, text, integer, boolean, text, text, text) from public;
grant execute on function public.update_room_slot(uuid, text, integer, boolean, text, text, text) to authenticated;
