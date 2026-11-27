-- 048_matchmaking_and_room_bans.sql
-- Quick Matchmaking, Room Backfill, Per-Room Bans, Host Promotion, Live Stats & Public Open Rooms

-- 1. Extend public.rooms with banned_user_ids and backfill indexes
alter table public.rooms
    add column if not exists banned_user_ids uuid[] not null default '{}'::uuid[];

create index if not exists idx_rooms_public_waiting_matchmaking
    on public.rooms (game_id, created_at)
    where status = 'waiting' and is_private = false;

create index if not exists idx_rooms_banned_users
    on public.rooms using gin (banned_user_ids);

-- 2. Matchmaking queue table (1 active search per user)
create table if not exists public.matchmaking_queue (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null unique references auth.users(id) on delete cascade,
    game_id uuid not null references public.games(id) on delete cascade,
    game_slug text not null,
    endpoint text not null,
    voice_endpoint text,
    status text not null default 'waiting' check (status in ('waiting', 'matched')),
    room_id uuid references public.rooms(id) on delete set null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create index if not exists idx_matchmaking_waiting
    on public.matchmaking_queue (game_id, created_at)
    where status = 'waiting';

-- 3. RLS and Realtime for matchmaking_queue
alter table public.matchmaking_queue enable row level security;

drop policy if exists matchmaking_queue_select_own on public.matchmaking_queue;
create policy matchmaking_queue_select_own
    on public.matchmaking_queue
    for select
    to authenticated
    using (auth.uid() = user_id and public._is_registered_user(auth.uid()));

drop policy if exists matchmaking_queue_delete_own on public.matchmaking_queue;
create policy matchmaking_queue_delete_own
    on public.matchmaking_queue
    for delete
    to authenticated
    using (auth.uid() = user_id and public._is_registered_user(auth.uid()));

do $$
begin
    if not exists (
        select 1 from pg_publication_tables
        where pubname = 'supabase_realtime'
          and schemaname = 'public'
          and tablename = 'matchmaking_queue'
    ) then
        alter publication supabase_realtime add table public.matchmaking_queue;
    end if;
end $$;

-- 4. Update update_room_slot: enforce banned_user_ids, auto-promote host on leave, auto-close empty rooms
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
            order by (e.k)::integer asc
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
        host_id = v_next_host_id,
        updated_at = now()
    where id = p_room_id
    returning * into v_room;

    return v_room;
end;
$$;

revoke all on function public.update_room_slot(uuid, text, integer, boolean, text, text, text) from public;
grant execute on function public.update_room_slot(uuid, text, integer, boolean, text, text, text) to authenticated;

-- 5. Per-room ban RPC (host only; removes slot, clears room_messages, appends to banned_user_ids)
create or replace function public.ban_room_player(
    p_room_id uuid,
    p_target_user_id uuid
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
    v_target_key text;
    v_banned uuid[];
begin
    if v_uid is null then
        raise exception 'Not authenticated';
    end if;

    if not public._is_registered_user(v_uid) then
        raise exception 'Registered account required';
    end if;

    if p_target_user_id is null or p_target_user_id = v_uid then
        raise exception 'Invalid target user';
    end if;

    select * into v_room
    from public.rooms
    where id = p_room_id
    for update;

    if not found then
        raise exception 'Room not found';
    end if;

    if v_uid <> v_room.host_id then
        raise exception 'Only host can ban players';
    end if;

    if v_room.status <> 'waiting' then
        raise exception 'Can only ban players while room is waiting';
    end if;

    v_slots := coalesce(v_room.slots_json, '{}'::jsonb);

    select k
    into v_target_key
    from jsonb_each(v_slots) e(k, val)
    where e.val ->> 'user_id' = p_target_user_id::text
    limit 1;

    if v_target_key is not null then
        v_slots := v_slots - v_target_key;
    end if;

    delete from public.room_messages
    where room_id = p_room_id
      and user_id = p_target_user_id;

    v_banned := coalesce(v_room.banned_user_ids, '{}'::uuid[]);
    if not (p_target_user_id = any(v_banned)) then
        v_banned := array_append(v_banned, p_target_user_id);
    end if;

    update public.rooms
    set slots_json = v_slots,
        banned_user_ids = v_banned,
        updated_at = now()
    where id = p_room_id
    returning * into v_room;

    return v_room;
end;
$$;

revoke all on function public.ban_room_player(uuid, uuid) from public;
grant execute on function public.ban_room_player(uuid, uuid) to authenticated;

-- 6. Atomic matchmaking RPC: backfill public waiting rooms (largest first, then oldest) -> match queue -> enqueue
create or replace function public.find_or_enqueue_match(
    p_game_slug text,
    p_endpoint text,
    p_voice_endpoint text default null
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_uid uuid := auth.uid();
    v_game public.games%rowtype;
    v_my_username text;
    v_my_avatar text;
    v_eff_endpoint text;
    v_eff_voice text;
    v_room public.rooms%rowtype;
    v_slots jsonb;
    v_target_key text;
    v_new_slot jsonb;
    v_peer_queue public.matchmaking_queue%rowtype;
    v_peer_username text;
    v_peer_avatar text;
    v_code text;
    v_max_players smallint;
    v_new_room_id uuid;
    v_attempt integer := 0;
begin
    if v_uid is null then
        raise exception 'Not authenticated';
    end if;

    if not public._is_registered_user(v_uid) then
        raise exception 'Registered account required';
    end if;

    select * into v_game
    from public.games
    where slug = btrim(coalesce(p_game_slug, ''));

    if not found then
        raise exception 'Game not found';
    end if;

    v_eff_endpoint := left(btrim(coalesce(p_endpoint, '')), 128);
    if v_eff_endpoint = '' then
        raise exception 'Valid endpoint required';
    end if;
    v_eff_voice := nullif(left(btrim(coalesce(p_voice_endpoint, '')), 128), '');

    select p.username, p.avatar_seed
    into v_my_username, v_my_avatar
    from public.profiles p
    where p.id = v_uid;

    v_my_username := coalesce(nullif(btrim(v_my_username), ''), 'Player');

    -- Expire stale queue rows (>90s without heartbeat) and remove any previous entry for current user
    delete from public.matchmaking_queue
    where (status = 'waiting' and updated_at < now() - interval '90 seconds')
       or user_id = v_uid;

    -- Priority 1: Backfill an existing public waiting room (most populated first, then oldest)
    select r.*
    into v_room
    from public.rooms r
    where r.game_id = v_game.id
      and r.status = 'waiting'
      and r.is_private = false
      and r.updated_at >= now() - interval '2 minutes'
      and not (v_uid = any(coalesce(r.banned_user_ids, '{}'::uuid[])))
      and (select count(*) from jsonb_object_keys(coalesce(r.slots_json, '{}'::jsonb))) < r.max_players
    order by (select count(*) from jsonb_object_keys(coalesce(r.slots_json, '{}'::jsonb))) desc,
             r.created_at asc
    for update skip locked
    limit 1;

    if found then
        v_slots := coalesce(v_room.slots_json, '{}'::jsonb);

        -- Check if already seated
        select k
        into v_target_key
        from jsonb_each(v_slots) e(k, val)
        where e.val ->> 'user_id' = v_uid::text
        limit 1;

        if v_target_key is null then
            select gs::text
            into v_target_key
            from generate_series(0, greatest(v_room.max_players - 1, 0)) gs
            where not (v_slots ? gs::text)
            order by gs
            limit 1;
        end if;

        if v_target_key is not null then
            v_new_slot := jsonb_build_object(
                'user_id', v_uid::text,
                'username', v_my_username,
                'endpoint', v_eff_endpoint,
                'ready', (v_uid = v_room.host_id)
            );
            if v_my_avatar is not null and btrim(v_my_avatar) <> '' then
                v_new_slot := v_new_slot || jsonb_build_object('avatar_seed', btrim(v_my_avatar));
            end if;
            if v_eff_voice is not null then
                v_new_slot := v_new_slot || jsonb_build_object('voice_endpoint', v_eff_voice);
            end if;

            v_slots := v_slots || jsonb_build_object(v_target_key, v_new_slot);

            update public.rooms
            set slots_json = v_slots,
                updated_at = now()
            where id = v_room.id;

            return jsonb_build_object(
                'status', 'matched',
                'room_id', v_room.id,
                'source', 'backfill'
            );
        end if;
    end if;

    -- Priority 2: Pair with another waiting player in matchmaking_queue
    select q.*
    into v_peer_queue
    from public.matchmaking_queue q
    where q.game_id = v_game.id
      and q.status = 'waiting'
      and q.user_id <> v_uid
      and q.updated_at >= now() - interval '90 seconds'
    order by q.created_at asc
    for update skip locked
    limit 1;

    if found then
        select p.username, p.avatar_seed
        into v_peer_username, v_peer_avatar
        from public.profiles p
        where p.id = v_peer_queue.user_id;

        v_peer_username := coalesce(nullif(btrim(v_peer_username), ''), 'Player');
        v_max_players := least(greatest(coalesce(v_game.max_players, 2), 2), 4)::smallint;

        v_slots := jsonb_build_object(
            '0', jsonb_strip_nulls(jsonb_build_object(
                'user_id', v_peer_queue.user_id::text,
                'username', v_peer_username,
                'endpoint', v_peer_queue.endpoint,
                'voice_endpoint', v_peer_queue.voice_endpoint,
                'ready', true,
                'avatar_seed', nullif(btrim(coalesce(v_peer_avatar, '')), '')
            )),
            '1', jsonb_strip_nulls(jsonb_build_object(
                'user_id', v_uid::text,
                'username', v_my_username,
                'endpoint', v_eff_endpoint,
                'voice_endpoint', v_eff_voice,
                'ready', false,
                'avatar_seed', nullif(btrim(coalesce(v_my_avatar, '')), '')
            ))
        );

        loop
            v_attempt := v_attempt + 1;
            v_code := (1000 + floor(random() * 9000)::int)::text || chr(65 + floor(random() * 26)::int);
            begin
                insert into public.rooms (
                    code,
                    host_id,
                    game_id,
                    mode,
                    status,
                    max_players,
                    slots_json,
                    host_endpoint,
                    is_private
                ) values (
                    v_code,
                    v_peer_queue.user_id,
                    v_game.id,
                    'online',
                    'waiting',
                    v_max_players,
                    v_slots,
                    v_peer_queue.endpoint,
                    false
                )
                returning id into v_new_room_id;
                exit;
            exception when unique_violation then
                if v_attempt >= 8 then
                    raise exception 'Could not generate unique room code';
                end if;
            end;
        end loop;

        update public.matchmaking_queue
        set status = 'matched',
            room_id = v_new_room_id,
            updated_at = now()
        where id = v_peer_queue.id;

        return jsonb_build_object(
            'status', 'matched',
            'room_id', v_new_room_id,
            'source', 'queue'
        );
    end if;

    -- Priority 3: Enqueue current user (1 active search per user)
    insert into public.matchmaking_queue (
        user_id,
        game_id,
        game_slug,
        endpoint,
        voice_endpoint,
        status,
        room_id,
        created_at,
        updated_at
    ) values (
        v_uid,
        v_game.id,
        v_game.slug,
        v_eff_endpoint,
        v_eff_voice,
        'waiting',
        null,
        now(),
        now()
    )
    on conflict (user_id) do update
    set game_id = excluded.game_id,
        game_slug = excluded.game_slug,
        endpoint = excluded.endpoint,
        voice_endpoint = excluded.voice_endpoint,
        status = 'waiting',
        room_id = null,
        created_at = now(),
        updated_at = now();

    return jsonb_build_object(
        'status', 'queued',
        'room_id', null,
        'source', 'queue'
    );
end;
$$;

revoke all on function public.find_or_enqueue_match(text, text, text) from public;
grant execute on function public.find_or_enqueue_match(text, text, text) to authenticated;

-- 7. Matchmaking heartbeat & cancel RPCs
create or replace function public.touch_matchmaking_heartbeat()
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
    v_uid uuid := auth.uid();
begin
    if v_uid is null then
        raise exception 'Not authenticated';
    end if;

    update public.matchmaking_queue
    set updated_at = now()
    where user_id = v_uid
      and status = 'waiting';
end;
$$;

revoke all on function public.touch_matchmaking_heartbeat() from public;
grant execute on function public.touch_matchmaking_heartbeat() to authenticated;

create or replace function public.cancel_matchmaking()
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
    v_uid uuid := auth.uid();
begin
    if v_uid is null then
        raise exception 'Not authenticated';
    end if;

    delete from public.matchmaking_queue
    where user_id = v_uid
      and status = 'waiting';
end;
$$;

revoke all on function public.cancel_matchmaking() from public;
grant execute on function public.cancel_matchmaking() to authenticated;

-- 8. Live matchmaking & active-players stats RPC
create or replace function public.get_matchmaking_stats()
returns jsonb
language sql
security definer
stable
set search_path = public
as $$
    with searching_by_game as (
        select
            q.game_slug,
            count(*)::int as searching
        from public.matchmaking_queue q
        where q.status = 'waiting'
          and q.updated_at >= now() - interval '90 seconds'
        group by q.game_slug
    ),
    playing_by_game as (
        select
            g.slug as game_slug,
            coalesce(sum((select count(*) from jsonb_object_keys(coalesce(r.slots_json, '{}'::jsonb)))), 0)::int as playing
        from public.rooms r
        join public.games g on g.id = r.game_id
        where r.status = 'playing'
        group by g.slug
    ),
    open_rooms_by_game as (
        select
            g.slug as game_slug,
            count(*)::int as open_rooms
        from public.rooms r
        join public.games g on g.id = r.game_id
        where r.status = 'waiting'
          and r.is_private = false
          and r.updated_at >= now() - interval '2 minutes'
          and (select count(*) from jsonb_object_keys(coalesce(r.slots_json, '{}'::jsonb))) < r.max_players
        group by g.slug
    ),
    all_slugs as (
        select game_slug from searching_by_game
        union
        select game_slug from playing_by_game
        union
        select game_slug from open_rooms_by_game
    )
    select jsonb_build_object(
        'total_searching', coalesce((select sum(searching) from searching_by_game), 0),
        'total_playing', coalesce((select sum(playing) from playing_by_game), 0),
        'by_game', coalesce((
            select jsonb_agg(
                jsonb_build_object(
                    'game_slug', a.game_slug,
                    'searching', coalesce(s.searching, 0),
                    'playing', coalesce(p.playing, 0),
                    'open_rooms', coalesce(o.open_rooms, 0)
                )
                order by a.game_slug asc
            )
            from all_slugs a
            left join searching_by_game s on s.game_slug = a.game_slug
            left join playing_by_game p on p.game_slug = a.game_slug
            left join open_rooms_by_game o on o.game_slug = a.game_slug
        ), '[]'::jsonb)
    );
$$;

revoke all on function public.get_matchmaking_stats() from public;
grant execute on function public.get_matchmaking_stats() to anon, authenticated;

-- 9. Public open rooms RPC for web QR listing (exposes only username + avatar_seed, zero IPs/emails)
create or replace function public.get_public_open_rooms()
returns jsonb
language sql
security definer
stable
set search_path = public
as $$
    select coalesce(jsonb_agg(row_data order by player_count desc, created_at asc), '[]'::jsonb)
    from (
        select
            r.created_at,
            (select count(*) from jsonb_object_keys(coalesce(r.slots_json, '{}'::jsonb)))::int as player_count,
            jsonb_build_object(
                'room_id', r.id,
                'code', r.code,
                'game_id', g.id,
                'game_slug', g.slug,
                'game_title', g.title,
                'cover_url', g.cover_url,
                'player_count', (select count(*) from jsonb_object_keys(coalesce(r.slots_json, '{}'::jsonb)))::int,
                'max_players', r.max_players,
                'created_at', r.created_at,
                'players', coalesce((
                    select jsonb_agg(
                        jsonb_build_object(
                            'username', coalesce(e.value ->> 'username', 'Player'),
                            'avatar_seed', e.value ->> 'avatar_seed'
                        )
                        order by (e.key)::integer asc
                    )
                    from jsonb_each(coalesce(r.slots_json, '{}'::jsonb)) e
                ), '[]'::jsonb)
            ) as row_data
        from public.rooms r
        join public.games g on g.id = r.game_id
        where r.status = 'waiting'
          and r.is_private = false
          and r.updated_at >= now() - interval '2 minutes'
          and (select count(*) from jsonb_object_keys(coalesce(r.slots_json, '{}'::jsonb))) < r.max_players
    ) sub;
$$;

revoke all on function public.get_public_open_rooms() from public;
grant execute on function public.get_public_open_rooms() to anon, authenticated;
