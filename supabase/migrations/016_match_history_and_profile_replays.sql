-- Fase 6: Historial de partidas (Solo, LAN, Online; excluyendo salas privadas)
-- y exposición de replay_path en get_my_high_scores para la lista de Replays del perfil.

-- 1. Actualizar get_my_high_scores() para incluir replay_path y replay_size_bytes
drop function if exists public.get_my_high_scores();

create or replace function public.get_my_high_scores()
returns table (
    game_id uuid,
    score bigint,
    achieved_at timestamptz,
    updated_at timestamptz,
    rank bigint,
    total_players bigint,
    replay_path text,
    replay_size_bytes integer
)
language sql
stable
security definer
set search_path = public
as $$
    with my_scores as (
        select
            hs.game_id,
            hs.score,
            hs.achieved_at,
            hs.updated_at,
            hs.replay_path,
            hs.replay_size_bytes
        from public.high_scores hs
        where hs.user_id = auth.uid()
          and hs.is_hardcore = true
          and hs.credits_used = 1
    )
    select
        ms.game_id,
        ms.score,
        ms.achieved_at,
        ms.updated_at,
        (
            select count(*) + 1
            from public.high_scores other
            where other.game_id = ms.game_id
              and other.is_hardcore = true
              and other.credits_used = 1
              and (
                  other.score > ms.score
                  or (other.score = ms.score and other.achieved_at < ms.achieved_at)
              )
        )::bigint as rank,
        (
            select count(*)
            from public.high_scores total
            where total.game_id = ms.game_id
              and total.is_hardcore = true
              and total.credits_used = 1
        )::bigint as total_players,
        ms.replay_path,
        ms.replay_size_bytes
    from my_scores ms
    order by rank asc, ms.score desc, ms.updated_at desc;
$$;

revoke all on function public.get_my_high_scores() from public;
grant execute on function public.get_my_high_scores() to authenticated;

-- 2. Extender public.match_history para soportar idempotencia, participantes y exclusión de salas privadas
alter table public.match_history
    add column if not exists client_session_id uuid,
    add column if not exists room_id uuid references public.rooms(id) on delete set null,
    add column if not exists is_private boolean not null default false,
    add column if not exists participant_ids uuid[] not null default '{}',
    add column if not exists duration_seconds integer not null default 0 check (duration_seconds >= 0),
    add column if not exists score bigint;

create unique index if not exists idx_match_history_client_session
    on public.match_history(client_session_id)
    where client_session_id is not null;

create index if not exists idx_match_history_participants
    on public.match_history using gin (participant_ids);

create index if not exists idx_match_history_ended_at
    on public.match_history(ended_at desc nulls last);

-- Restringir lectura RLS únicamente a participantes de partidas no privadas
drop policy if exists "match_history_select_auth" on public.match_history;
create policy "match_history_select_auth" on public.match_history
    for select to authenticated
    using (
        is_private = false
        and auth.uid() = any(participant_ids)
    );

-- 3. RPC idempotente para registrar historial de partida (nunca registra salas privadas)
create or replace function public.record_match_history(
    p_client_session_id uuid,
    p_game_id uuid,
    p_mode text,
    p_room_id uuid default null,
    p_is_private boolean default false,
    p_players_json jsonb default '[]'::jsonb,
    p_participant_ids uuid[] default '{}',
    p_started_at timestamptz default now(),
    p_ended_at timestamptz default now(),
    p_duration_seconds integer default 0,
    p_score bigint default null
)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
    v_uid uuid := auth.uid();
    v_room_private boolean := false;
    v_participants uuid[];
    v_duration integer;
begin
    if v_uid is null then
        raise exception 'Not authenticated';
    end if;

    if coalesce(p_is_private, false) then
        return;
    end if;

    if p_room_id is not null then
        select coalesce(r.is_private, false)
          into v_room_private
        from public.rooms r
        where r.id = p_room_id;

        if coalesce(v_room_private, false) then
            return;
        end if;
    end if;

    if p_mode not in ('local', 'lan', 'online') then
        raise exception 'Invalid match mode: %', p_mode;
    end if;

    v_participants := coalesce(p_participant_ids, '{}');
    if not (v_uid = any(v_participants)) then
        if p_mode = 'local' then
            v_participants := array[v_uid];
        else
            raise exception 'User is not a participant of this match';
        end if;
    end if;

    v_duration := least(greatest(coalesce(p_duration_seconds, 0), 0), 86400);

    insert into public.match_history (
        client_session_id,
        game_id,
        mode,
        room_id,
        is_private,
        players_json,
        participant_ids,
        started_at,
        ended_at,
        duration_seconds,
        score
    )
    values (
        p_client_session_id,
        p_game_id,
        p_mode,
        p_room_id,
        false,
        coalesce(p_players_json, '[]'::jsonb),
        v_participants,
        coalesce(p_started_at, now()),
        coalesce(p_ended_at, now()),
        v_duration,
        p_score
    )
    on conflict (client_session_id) where (client_session_id is not null) do update
    set ended_at = greatest(public.match_history.ended_at, excluded.ended_at),
        duration_seconds = greatest(public.match_history.duration_seconds, excluded.duration_seconds),
        players_json = case
            when jsonb_array_length(excluded.players_json) >= jsonb_array_length(public.match_history.players_json)
                then excluded.players_json
            else public.match_history.players_json
        end,
        score = case
            when excluded.score is not null and (public.match_history.score is null or excluded.score > public.match_history.score)
                then excluded.score
            else public.match_history.score
        end;
end;
$$;

revoke all on function public.record_match_history(uuid, uuid, text, uuid, boolean, jsonb, uuid[], timestamptz, timestamptz, integer, bigint) from public;
grant execute on function public.record_match_history(uuid, uuid, text, uuid, boolean, jsonb, uuid[], timestamptz, timestamptz, integer, bigint) to authenticated;

-- 4. RPC para obtener historial de partidas del usuario autenticado (excluyendo salas privadas)
create or replace function public.get_my_match_history(
    p_limit integer default 50,
    p_offset integer default 0
)
returns table (
    id uuid,
    game_id uuid,
    mode text,
    room_id uuid,
    players_json jsonb,
    started_at timestamptz,
    ended_at timestamptz,
    duration_seconds integer,
    score bigint
)
language plpgsql
stable
security definer
set search_path = public
as $$
declare
    v_uid uuid := auth.uid();
    v_limit integer := least(greatest(coalesce(p_limit, 50), 1), 100);
    v_offset integer := greatest(coalesce(p_offset, 0), 0);
begin
    if v_uid is null then
        raise exception 'Not authenticated';
    end if;

    return query
    select
        mh.id,
        mh.game_id,
        mh.mode,
        mh.room_id,
        mh.players_json,
        mh.started_at,
        mh.ended_at,
        mh.duration_seconds,
        mh.score
    from public.match_history mh
    where v_uid = any(mh.participant_ids)
      and mh.is_private = false
    order by coalesce(mh.ended_at, mh.started_at) desc
    limit v_limit offset v_offset;
end;
$$;

revoke all on function public.get_my_match_history(integer, integer) from public;
grant execute on function public.get_my_match_history(integer, integer) to authenticated;
