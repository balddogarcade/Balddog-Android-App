-- bald.dog ARCADE Android - Esquema inicial Supabase (PostgreSQL + RLS + Realtime)

create extension if not exists "pgcrypto";

-- 1. Perfiles de usuario (Email y Anónimos/Invitados)
create table if not exists public.profiles (
    id uuid primary key references auth.users(id) on delete cascade,
    username text not null unique,
    avatar_url text,
    total_plays integer not null default 0,
    is_anonymous boolean not null default false,
    created_at timestamptz not null default now()
);

-- Trigger para crear perfil automáticamente al registrarse (incluyendo usuarios anónimos)
create or replace function public.handle_new_user()
returns trigger
language plpgsql
security definer set search_path = public
as $$
declare
    default_name text;
begin
    default_name := coalesce(
        new.raw_user_meta_data ->> 'username',
        'player_' || substr(replace(new.id::text, '-', ''), 1, 8)
    );
    insert into public.profiles (id, username, is_anonymous)
    values (new.id, default_name, coalesce(new.is_anonymous, false))
    on conflict (id) do nothing;
    return new;
end;
$$;

drop trigger if exists on_auth_user_created on auth.users;
create trigger on_auth_user_created
    after insert on auth.users
    for each row execute procedure public.handle_new_user();

-- 2. BIOS compartidas (neogeo.zip, pgm.zip, etc.)
create table if not exists public.game_bios (
    id uuid primary key default gen_random_uuid(),
    system text not null unique,
    filename text not null,
    ipfs_cid text not null,
    sha256 text not null,
    crc32 text not null,
    size_bytes bigint not null default 0,
    created_at timestamptz not null default now()
);

-- 3. Catálogo curado de juegos
create table if not exists public.games (
    id uuid primary key default gen_random_uuid(),
    slug text not null unique,
    title text not null,
    system text not null,
    rom_filename text not null,
    ipfs_cid text not null,
    sha256 text not null,
    crc32 text not null,
    size_bytes bigint not null default 0,
    max_players smallint not null default 2 check (max_players between 1 and 4),
    is_coop boolean not null default true,
    parent_game_id uuid references public.games(id) on delete set null,
    bios_id uuid references public.game_bios(id) on delete set null,
    cover_url text,
    created_at timestamptz not null default now()
);

create index if not exists idx_games_system on public.games(system);

-- 4. Salas Multijugador (LAN y Online)
create table if not exists public.rooms (
    id uuid primary key default gen_random_uuid(),
    code text not null unique,
    host_id uuid not null references public.profiles(id) on delete cascade,
    game_id uuid not null references public.games(id) on delete cascade,
    mode text not null check (mode in ('lan', 'online')),
    status text not null default 'waiting' check (status in ('waiting', 'playing', 'closed')),
    max_players smallint not null default 2 check (max_players between 2 and 4),
    slots_json jsonb not null default '{}'::jsonb,
    host_endpoint text,
    local_ip text,
    local_port integer,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create index if not exists idx_rooms_status_mode on public.rooms(status, mode);

-- 5. Chat rápido en Lobby de Sala
create table if not exists public.room_messages (
    id uuid primary key default gen_random_uuid(),
    room_id uuid not null references public.rooms(id) on delete cascade,
    user_id uuid not null references public.profiles(id) on delete cascade,
    username text not null,
    message text not null check (char_length(message) <= 240),
    created_at timestamptz not null default now()
);

create index if not exists idx_room_messages_room on public.room_messages(room_id, created_at);

-- 6. High Scores
create table if not exists public.high_scores (
    id uuid primary key default gen_random_uuid(),
    game_id uuid not null references public.games(id) on delete cascade,
    user_id uuid not null references public.profiles(id) on delete cascade,
    score bigint not null check (score >= 0),
    achieved_at timestamptz not null default now()
);

create index if not exists idx_high_scores_game_score on public.high_scores(game_id, score desc);

-- 7. Historial de Partidas (preparado para expansión)
create table if not exists public.match_history (
    id uuid primary key default gen_random_uuid(),
    game_id uuid not null references public.games(id) on delete cascade,
    mode text not null check (mode in ('local', 'lan', 'online')),
    players_json jsonb not null default '[]'::jsonb,
    started_at timestamptz not null default now(),
    ended_at timestamptz
);

-- 8. Amistades (preparado para expansión)
create table if not exists public.friendships (
    id uuid primary key default gen_random_uuid(),
    requester_id uuid not null references public.profiles(id) on delete cascade,
    addressee_id uuid not null references public.profiles(id) on delete cascade,
    status text not null default 'pending' check (status in ('pending', 'accepted', 'blocked')),
    created_at timestamptz not null default now(),
    unique (requester_id, addressee_id)
);

-- ============================================================================
-- ROW LEVEL SECURITY (RLS)
-- ============================================================================
alter table public.profiles enable row level security;
alter table public.game_bios enable row level security;
alter table public.games enable row level security;
alter table public.rooms enable row level security;
alter table public.room_messages enable row level security;
alter table public.high_scores enable row level security;
alter table public.match_history enable row level security;
alter table public.friendships enable row level security;

-- Profiles: lectura pública para usuarios autenticados, escritura solo dueño
drop policy if exists "profiles_select_auth" on public.profiles;
create policy "profiles_select_auth" on public.profiles
    for select to authenticated using (true);

drop policy if exists "profiles_insert_own" on public.profiles;
create policy "profiles_insert_own" on public.profiles
    for insert to authenticated with check (auth.uid() = id);

drop policy if exists "profiles_update_own" on public.profiles;
create policy "profiles_update_own" on public.profiles
    for update to authenticated using (auth.uid() = id);

-- Catálogo (games y game_bios): solo lectura pública para autenticados (y anon)
drop policy if exists "games_select_all" on public.games;
create policy "games_select_all" on public.games
    for select to anon, authenticated using (true);

drop policy if exists "bios_select_all" on public.game_bios;
create policy "bios_select_all" on public.game_bios
    for select to anon, authenticated using (true);

-- Rooms: lectura para autenticados; crear/actualizar/borrar solo host o participantes autenticados
drop policy if exists "rooms_select_auth" on public.rooms;
create policy "rooms_select_auth" on public.rooms
    for select to authenticated using (true);

drop policy if exists "rooms_insert_host" on public.rooms;
create policy "rooms_insert_host" on public.rooms
    for insert to authenticated with check (auth.uid() = host_id);

drop policy if exists "rooms_update_auth" on public.rooms;
create policy "rooms_update_auth" on public.rooms
    for update to authenticated using (true);

drop policy if exists "rooms_delete_host" on public.rooms;
create policy "rooms_delete_host" on public.rooms
    for delete to authenticated using (auth.uid() = host_id);

-- Room Messages: lectura e inserción solo usuarios autenticados
drop policy if exists "room_messages_select_auth" on public.room_messages;
create policy "room_messages_select_auth" on public.room_messages
    for select to authenticated using (true);

drop policy if exists "room_messages_insert_own" on public.room_messages;
create policy "room_messages_insert_own" on public.room_messages
    for insert to authenticated with check (auth.uid() = user_id);

-- High Scores: lectura para todos, inserción solo dueño
drop policy if exists "high_scores_select_all" on public.high_scores;
create policy "high_scores_select_all" on public.high_scores
    for select to anon, authenticated using (true);

drop policy if exists "high_scores_insert_own" on public.high_scores;
create policy "high_scores_insert_own" on public.high_scores
    for insert to authenticated with check (auth.uid() = user_id);

-- Match History: lectura para autenticados
drop policy if exists "match_history_select_auth" on public.match_history;
create policy "match_history_select_auth" on public.match_history
    for select to authenticated using (true);

-- Friendships: solo participantes
drop policy if exists "friendships_select_own" on public.friendships;
create policy "friendships_select_own" on public.friendships
    for select to authenticated using (auth.uid() = requester_id or auth.uid() = addressee_id);

drop policy if exists "friendships_insert_own" on public.friendships;
create policy "friendships_insert_own" on public.friendships
    for insert to authenticated with check (auth.uid() = requester_id);

drop policy if exists "friendships_update_own" on public.friendships;
create policy "friendships_update_own" on public.friendships
    for update to authenticated using (auth.uid() = requester_id or auth.uid() = addressee_id);

-- ============================================================================
-- SUPABASE REALTIME
-- ============================================================================
do $$
begin
    if not exists (
        select 1 from pg_publication_tables
        where pubname = 'supabase_realtime' and schemaname = 'public' and tablename = 'rooms'
    ) then
        alter publication supabase_realtime add table public.rooms;
    end if;
    if not exists (
        select 1 from pg_publication_tables
        where pubname = 'supabase_realtime' and schemaname = 'public' and tablename = 'room_messages'
    ) then
        alter publication supabase_realtime add table public.room_messages;
    end if;
end;
$$;
