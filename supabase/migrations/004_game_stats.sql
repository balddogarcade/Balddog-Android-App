-- Métricas de descargas y tiempo de juego (global y por usuario)

-- 1. Contadores globales en el catálogo de juegos
alter table public.games
    add column if not exists download_count bigint not null default 0 check (download_count >= 0),
    add column if not exists total_play_seconds bigint not null default 0 check (total_play_seconds >= 0);

-- 2. Tiempo total jugado en el perfil de usuario
alter table public.profiles
    add column if not exists total_play_seconds bigint not null default 0 check (total_play_seconds >= 0);

-- 3. Registro de tiempo jugado por usuario y por juego
create table if not exists public.user_game_stats (
    user_id uuid not null references public.profiles(id) on delete cascade,
    game_id uuid not null references public.games(id) on delete cascade,
    play_seconds bigint not null default 0 check (play_seconds >= 0),
    play_count integer not null default 0 check (play_count >= 0),
    last_played_at timestamptz not null default now(),
    primary key (user_id, game_id)
);

create index if not exists idx_user_game_stats_user
    on public.user_game_stats(user_id, play_seconds desc);

create index if not exists idx_user_game_stats_game
    on public.user_game_stats(game_id, play_seconds desc);

alter table public.user_game_stats enable row level security;

drop policy if exists "user_game_stats_select_auth" on public.user_game_stats;
create policy "user_game_stats_select_auth" on public.user_game_stats
    for select to authenticated using (true);

-- 4. RPC atómico para registrar una descarga de juego
create or replace function public.increment_game_download(p_game_id uuid)
returns void
language plpgsql
security definer
set search_path = public
as $$
begin
    if auth.uid() is null then
        raise exception 'Not authenticated';
    end if;

    update public.games
    set download_count = download_count + 1
    where id = p_game_id;
end;
$$;

revoke all on function public.increment_game_download(uuid) from public;
grant execute on function public.increment_game_download(uuid) to authenticated;

-- 5. RPC atómico para registrar tiempo de juego (actualiza user_game_stats, games y profiles)
create or replace function public.record_play_session(
    p_game_id uuid,
    p_seconds bigint,
    p_is_new_session boolean default true
)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
    v_uid uuid := auth.uid();
    v_seconds bigint;
    v_session_inc integer := case when p_is_new_session then 1 else 0 end;
begin
    if v_uid is null then
        raise exception 'Not authenticated';
    end if;

    if p_seconds is null or p_seconds <= 0 then
        return;
    end if;

    -- Tope de seguridad por llamada (24h) para evitar datos corruptos
    v_seconds := least(p_seconds, 86400);

    insert into public.user_game_stats (user_id, game_id, play_seconds, play_count, last_played_at)
    values (v_uid, p_game_id, v_seconds, v_session_inc, now())
    on conflict (user_id, game_id) do update
    set play_seconds = public.user_game_stats.play_seconds + v_seconds,
        play_count = public.user_game_stats.play_count + v_session_inc,
        last_played_at = now();

    update public.games
    set total_play_seconds = total_play_seconds + v_seconds
    where id = p_game_id;

    update public.profiles
    set total_play_seconds = total_play_seconds + v_seconds,
        total_plays = total_plays + v_session_inc
    where id = v_uid;
end;
$$;

revoke all on function public.record_play_session(uuid, bigint, boolean) from public;
grant execute on function public.record_play_session(uuid, bigint, boolean) to authenticated;
