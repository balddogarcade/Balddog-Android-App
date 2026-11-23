-- Fase 2: Perfiles públicos, bio, país, avatar_seed aleatorio, redes sociales, privacidad y bloqueo de usuarios anónimos

-- 1. Nuevas columnas en public.profiles
alter table public.profiles
    add column if not exists bio text,
    add column if not exists country_code text,
    add column if not exists avatar_seed text,
    add column if not exists is_private boolean not null default false,
    add column if not exists social_links jsonb not null default '{}'::jsonb;

-- Inicializar avatar_seed para filas existentes que no lo tengan
update public.profiles
set avatar_seed = substr(md5(id::text || username), 1, 12)
where avatar_seed is null or btrim(avatar_seed) = '';

-- 2. Constraints de integridad en profiles
do $$
begin
    if not exists (
        select 1 from pg_constraint
        where conname = 'chk_profiles_bio_length'
          and conrelid = 'public.profiles'::regclass
    ) then
        alter table public.profiles
            add constraint chk_profiles_bio_length
            check (bio is null or char_length(bio) <= 140);
    end if;

    if not exists (
        select 1 from pg_constraint
        where conname = 'chk_profiles_country_code'
          and conrelid = 'public.profiles'::regclass
    ) then
        alter table public.profiles
            add constraint chk_profiles_country_code
            check (country_code is null or country_code ~ '^[A-Z]{2}$');
    end if;

    if not exists (
        select 1 from pg_constraint
        where conname = 'chk_profiles_social_links_object'
          and conrelid = 'public.profiles'::regclass
    ) then
        alter table public.profiles
            add constraint chk_profiles_social_links_object
            check (jsonb_typeof(social_links) = 'object');
    end if;
end;
$$;

-- Índice único insensible a mayúsculas para username
create unique index if not exists idx_profiles_username_lower
    on public.profiles (lower(username));

-- 3. Trigger de registro: prohibir usuarios anónimos y generar avatar_seed aleatorio
create or replace function public.handle_new_user()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
declare
    raw_name text;
    clean_name text;
    seed_val text;
begin
    if coalesce(new.is_anonymous, false) then
        raise exception 'Anonymous users are not allowed on this platform';
    end if;

    raw_name := coalesce(
        nullif(btrim(new.raw_user_meta_data ->> 'username'), ''),
        'player_' || substr(replace(new.id::text, '-', ''), 1, 8)
    );
    clean_name := regexp_replace(raw_name, '[^a-zA-Z0-9_]', '_', 'g');
    if char_length(clean_name) < 3 then
        clean_name := 'player_' || substr(replace(new.id::text, '-', ''), 1, 6);
    end if;
    clean_name := left(clean_name, 20);
    seed_val := substr(md5(random()::text || new.id::text), 1, 12);

    insert into public.profiles (id, username, is_anonymous, avatar_seed)
    values (new.id, clean_name, false, seed_val)
    on conflict (id) do nothing;

    return new;
end;
$$;

-- 4. RLS en profiles: solo usuarios registrados visibles para autenticados
drop policy if exists "profiles_select_all" on public.profiles;
drop policy if exists "profiles_select_registered" on public.profiles;
create policy "profiles_select_registered" on public.profiles
    for select to authenticated
    using (is_anonymous = false);

drop policy if exists "profiles_update_own" on public.profiles;
create policy "profiles_update_own" on public.profiles
    for update to authenticated
    using (auth.uid() = id)
    with check (auth.uid() = id and is_anonymous = false);

-- 5. RPC seguro para actualizar perfil propio con validación estricta
create or replace function public.update_my_profile(
    p_username text,
    p_bio text default null,
    p_country_code text default null,
    p_avatar_seed text default null,
    p_is_private boolean default false,
    p_social_links jsonb default '{}'::jsonb
)
returns public.profiles
language plpgsql
security definer
set search_path = public
as $$
declare
    v_uid uuid := auth.uid();
    v_username text := btrim(coalesce(p_username, ''));
    v_bio text := nullif(btrim(coalesce(p_bio, '')), '');
    v_country text := nullif(upper(btrim(coalesce(p_country_code, ''))), '');
    v_seed text := nullif(btrim(coalesce(p_avatar_seed, '')), '');
    v_links jsonb := coalesce(p_social_links, '{}'::jsonb);
    v_updated public.profiles;
begin
    if v_uid is null then
        raise exception 'Not authenticated';
    end if;

    if v_username !~ '^[a-zA-Z0-9_]{3,20}$' then
        raise exception 'El username debe tener entre 3 y 20 caracteres (solo letras, números y _)';
    end if;

    if v_bio is not null and char_length(v_bio) > 140 then
        raise exception 'La bio no puede superar los 140 caracteres';
    end if;

    if v_country is not null and v_country !~ '^[A-Z]{2}$' then
        raise exception 'Código de país inválido';
    end if;

    if jsonb_typeof(v_links) <> 'object' then
        raise exception 'Formato de redes sociales inválido';
    end if;

    if exists (
        select 1
        from public.profiles p
        where lower(p.username) = lower(v_username)
          and p.id <> v_uid
    ) then
        raise exception 'Ese nombre de usuario ya está en uso';
    end if;

    update public.profiles
    set
        username = v_username,
        bio = v_bio,
        country_code = v_country,
        avatar_seed = coalesce(v_seed, avatar_seed),
        is_private = coalesce(p_is_private, false),
        social_links = v_links
    where id = v_uid
    returning * into v_updated;

    if not found then
        raise exception 'Perfil no encontrado';
    end if;

    return v_updated;
end;
$$;

revoke all on function public.update_my_profile(text, text, text, text, boolean, jsonb) from public;
grant execute on function public.update_my_profile(text, text, text, text, boolean, jsonb) to authenticated;
