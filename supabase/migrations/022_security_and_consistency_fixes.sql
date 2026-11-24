-- Migration 022: Security, RLS, Anti-Cheat, Rank Consistency, and Atomic Room/Verification Fixes
begin;

-- ============================================================================
-- 1. GAMES: Align max_valid_score on mismatched rows (Med DB 2)
-- ============================================================================
update public.games
set max_valid_score = (score_config ->> 'max_valid_score')::bigint
where score_config ? 'max_valid_score'
  and (score_config ->> 'max_valid_score') ~ '^[0-9]+$'
  and coalesce(max_valid_score, 0) < (score_config ->> 'max_valid_score')::bigint;

-- ============================================================================
-- 2. HELPER: Registered user check (for RLS without exposing public.profiles)
-- ============================================================================
create or replace function public._is_registered_user(p_user_id uuid)
returns boolean
language sql
stable
security definer
set search_path = public
as $$
    select exists (
        select 1
        from public.profiles p
        where p.id = p_user_id
          and p.is_anonymous = false
    );
$$;

-- ============================================================================
-- 3. PROFILES RLS & update_my_profile (Crit 1.4, High DB 1, Low DB 1)
-- ============================================================================
drop policy if exists "profiles_select_auth" on public.profiles;
drop policy if exists "profiles_select_all" on public.profiles;
drop policy if exists "profiles_select_registered" on public.profiles;
drop policy if exists "profiles_select_own" on public.profiles;
create policy "profiles_select_own" on public.profiles
    for select to authenticated
    using (auth.uid() = id and is_anonymous = false);

drop policy if exists "profiles_update_own" on public.profiles;

drop policy if exists "profiles_insert_own" on public.profiles;
create policy "profiles_insert_own" on public.profiles
    for insert to authenticated
    with check (
        auth.uid() = id
        and is_anonymous = false
        and total_plays = 0
        and total_play_seconds = 0
        and username ~ '^[a-zA-Z0-9_]{3,20}$'
    );

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
    v_clean_links jsonb := '{}'::jsonb;
    v_key text;
    v_val_json jsonb;
    v_val text;
    v_host text;
    v_updated public.profiles;
begin
    if v_uid is null then
        raise exception 'Not authenticated';
    end if;

    if not public._is_registered_user(v_uid) then
        raise exception 'Perfil no encontrado o cuenta anónima no permitida';
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

    for v_key, v_val_json in select key, value from jsonb_each(v_links) loop
        if v_key not in ('x', 'instagram', 'youtube', 'twitch', 'discord', 'github', 'website') then
            raise exception 'Red social no soportada: %', v_key;
        end if;
        if jsonb_typeof(v_val_json) = 'null' then
            continue;
        end if;
        if jsonb_typeof(v_val_json) <> 'string' then
            raise exception 'Valor inválido para %', v_key;
        end if;

        v_val := btrim(v_val_json #>> '{}');
        if v_val = '' then
            continue;
        end if;
        if char_length(v_val) > 200 or v_val ~ '[[:cntrl:]]' or v_val ~ '\s' then
            raise exception 'Enlace inválido para %', v_key;
        end if;

        if v_key = 'discord' then
            if v_val ~* '^https://' then
                v_host := lower(split_part(split_part(split_part(substr(v_val, 9), '/', 1), '?', 1), '#', 1));
                if v_host not in ('discord.gg', 'discord.com', 'www.discord.com') then
                    raise exception 'Dominio inválido para discord';
                end if;
            elsif v_val ~* '^[a-z][a-z0-9+.-]*:' or char_length(v_val) < 2 or char_length(v_val) > 37 then
                raise exception 'Usuario/tag de Discord inválido';
            end if;
        else
            if v_val !~* '^https://' then
                raise exception 'El enlace de % debe iniciar con https://', v_key;
            end if;
            v_host := lower(split_part(split_part(split_part(substr(v_val, 9), '/', 1), '?', 1), '#', 1));
            if v_host = '' or position('.' in v_host) = 0 then
                raise exception 'URL inválida para %', v_key;
            end if;
            if v_key = 'x' and v_host not in ('x.com', 'www.x.com', 'twitter.com', 'www.twitter.com') then
                raise exception 'Dominio inválido para x';
            elsif v_key = 'instagram' and v_host not in ('instagram.com', 'www.instagram.com') then
                raise exception 'Dominio inválido para instagram';
            elsif v_key = 'youtube' and v_host not in ('youtube.com', 'www.youtube.com', 'm.youtube.com', 'youtu.be') then
                raise exception 'Dominio inválido para youtube';
            elsif v_key = 'twitch' and v_host not in ('twitch.tv', 'www.twitch.tv') then
                raise exception 'Dominio inválido para twitch';
            elsif v_key = 'github' and v_host not in ('github.com', 'www.github.com') then
                raise exception 'Dominio inválido para github';
            end if;
        end if;

        v_clean_links := v_clean_links || jsonb_build_object(v_key, v_val);
    end loop;

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
        social_links = v_clean_links
    where id = v_uid
    returning * into v_updated;

    if not found then
        raise exception 'Perfil no encontrado';
    end if;

    return v_updated;
end;
$$;

-- ============================================================================
-- 4. USER_FOLLOWS RLS (Low DB 2) & USER_GAME_STATS RLS
-- ============================================================================
drop policy if exists "user_follows_insert_own" on public.user_follows;
create policy "user_follows_insert_own" on public.user_follows
    for insert to authenticated
    with check (
        auth.uid() = follower_id
        and follower_id <> following_id
        and public._is_registered_user(auth.uid())
        and public._is_registered_user(following_id)
    );

drop policy if exists "user_game_stats_select_auth" on public.user_game_stats;
drop policy if exists "user_game_stats_select_own" on public.user_game_stats;
create policy "user_game_stats_select_own" on public.user_game_stats
    for select to authenticated
    using (auth.uid() = user_id);

-- ============================================================================
-- 5. STORAGE (replays bucket) & HIGH_SCORES RLS (Crit 1.1, Crit 1.2)
-- ============================================================================
drop policy if exists "replays_public_read" on storage.objects;
drop policy if exists "replays_owner_select" on storage.objects;
drop policy if exists "replays_owner_insert" on storage.objects;
drop policy if exists "replays_owner_update" on storage.objects;
drop policy if exists "replays_owner_delete" on storage.objects;

create policy "replays_public_read"
on storage.objects for select
to anon, authenticated
using (
    bucket_id = 'replays'
    and position('..' in name) = 0
    and name ~ '\.bsv\.gz$'
);

create policy "replays_owner_insert"
on storage.objects for insert
to authenticated
with check (
    bucket_id = 'replays'
    and position('..' in name) = 0
    and name ~ ('^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/' || auth.uid()::text || '/[A-Za-z0-9_-]+\.bsv\.gz$')
);

create policy "replays_owner_delete"
on storage.objects for delete
to authenticated
using (
    bucket_id = 'replays'
    and position('..' in name) = 0
    and (
        name ~ ('^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/' || auth.uid()::text || '/[A-Za-z0-9_-]+\.bsv\.gz$')
        or name like '%/' || auth.uid()::text || '.bsv.gz'
    )
    and not exists (
        select 1
        from public.high_scores hs
        where hs.replay_path = name
          and hs.verification_status in ('pending', 'verifying', 'verified')
    )
);

-- Restrict high_scores direct SELECT to authenticated (valid or own rows)
drop policy if exists "high_scores_select_all" on public.high_scores;
drop policy if exists "high_scores_select_auth" on public.high_scores;
create policy "high_scores_select_auth" on public.high_scores
    for select to authenticated
    using (
        auth.uid() = user_id
        or verification_status in ('pending', 'verifying', 'verified')
    );

create unique index if not exists idx_high_scores_unique_replay_path
    on public.high_scores (replay_path)
    where replay_path is not null;

-- ============================================================================
-- 6. ANTI-CHEAT: submit_high_score, claim_pending_replay_verifications,
--    complete_replay_verification (Crit 1.1, Crit 1.2, Med DB 4)
-- ============================================================================
create or replace function public.submit_high_score(
    p_game_id uuid,
    p_score bigint,
    p_rom_sha256 text,
    p_credits_used smallint default 1,
    p_is_hardcore boolean default true,
    p_replay_path text default null,
    p_replay_size_bytes integer default null,
    p_replay_sha256 text default null
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_uid uuid := auth.uid();
    v_game_sha256 text;
    v_max_valid_score bigint;
    v_score_config jsonb;
    v_replay_path text := nullif(btrim(coalesce(p_replay_path, '')), '');
    v_replay_sha256 text := nullif(lower(btrim(coalesce(p_replay_sha256, ''))), '');
    v_prev_score bigint;
    v_best_score bigint;
    v_achieved_at timestamptz;
    v_is_new_record boolean := false;
    v_rank bigint;
begin
    if v_uid is null then
        raise exception 'Not authenticated';
    end if;

    if not public._is_registered_user(v_uid) then
        return jsonb_build_object(
            'accepted', false,
            'is_new_record', false,
            'best_score', 0,
            'rank', null,
            'reason', 'anonymous_not_allowed'
        );
    end if;

    if p_score is null or p_score <= 0 then
        return jsonb_build_object(
            'accepted', false,
            'is_new_record', false,
            'best_score', 0,
            'rank', null,
            'reason', 'invalid_score'
        );
    end if;

    if not coalesce(p_is_hardcore, false) then
        return jsonb_build_object(
            'accepted', false,
            'is_new_record', false,
            'best_score', 0,
            'rank', null,
            'reason', 'hardcore_required'
        );
    end if;

    if coalesce(p_credits_used, 1) <> 1 then
        return jsonb_build_object(
            'accepted', false,
            'is_new_record', false,
            'best_score', 0,
            'rank', null,
            'reason', 'single_credit_only'
        );
    end if;

    select sha256, max_valid_score, score_config
      into v_game_sha256, v_max_valid_score, v_score_config
    from public.games
    where id = p_game_id;

    if not found then
        return jsonb_build_object(
            'accepted', false,
            'is_new_record', false,
            'best_score', 0,
            'rank', null,
            'reason', 'game_not_found'
        );
    end if;

    if not (coalesce(v_score_config, '{}'::jsonb) ? 'lboard')
       or nullif(btrim(coalesce(v_score_config ->> 'lboard', '')), '') is null then
        return jsonb_build_object(
            'accepted', false,
            'is_new_record', false,
            'best_score', 0,
            'rank', null,
            'reason', 'leaderboard_not_configured'
        );
    end if;

    if lower(coalesce(p_rom_sha256, '')) <> lower(v_game_sha256) then
        return jsonb_build_object(
            'accepted', false,
            'is_new_record', false,
            'best_score', 0,
            'rank', null,
            'reason', 'rom_mismatch'
        );
    end if;

    if v_max_valid_score is not null and p_score > v_max_valid_score then
        return jsonb_build_object(
            'accepted', false,
            'is_new_record', false,
            'best_score', 0,
            'rank', null,
            'reason', 'score_exceeds_max'
        );
    end if;

    if v_replay_path is null then
        return jsonb_build_object(
            'accepted', false,
            'is_new_record', false,
            'best_score', 0,
            'rank', null,
            'reason', 'replay_required'
        );
    end if;

    if position('..' in v_replay_path) > 0
       or left(v_replay_path, 1) = '/'
       or v_replay_path !~ ('^' || p_game_id::text || '/' || v_uid::text || '/[A-Za-z0-9_-]+\.bsv\.gz$') then
        return jsonb_build_object(
            'accepted', false,
            'is_new_record', false,
            'best_score', 0,
            'rank', null,
            'reason', 'invalid_replay_path'
        );
    end if;

    if p_replay_size_bytes is not null and (p_replay_size_bytes <= 0 or p_replay_size_bytes > 5242880) then
        return jsonb_build_object(
            'accepted', false,
            'is_new_record', false,
            'best_score', 0,
            'rank', null,
            'reason', 'invalid_replay_metadata'
        );
    end if;

    if v_replay_sha256 is not null and v_replay_sha256 !~ '^[0-9a-f]{64}$' then
        return jsonb_build_object(
            'accepted', false,
            'is_new_record', false,
            'best_score', 0,
            'rank', null,
            'reason', 'invalid_replay_sha256'
        );
    end if;

    if exists (
        select 1
        from public.high_scores hs
        where hs.replay_path = v_replay_path
          and (
              hs.user_id <> v_uid
              or hs.game_id <> p_game_id
              or p_score <> hs.score
              or hs.verification_status in ('rejected', 'error')
          )
    ) then
        return jsonb_build_object(
            'accepted', false,
            'is_new_record', false,
            'best_score', 0,
            'rank', null,
            'reason', 'replay_already_used'
        );
    end if;

    if v_replay_sha256 is not null and exists (
        select 1
        from public.high_scores hs
        where lower(hs.replay_sha256) = v_replay_sha256
          and (
              hs.user_id <> v_uid
              or hs.game_id <> p_game_id
              or p_score <> hs.score
              or hs.verification_status in ('rejected', 'error')
          )
    ) then
        return jsonb_build_object(
            'accepted', false,
            'is_new_record', false,
            'best_score', 0,
            'rank', null,
            'reason', 'replay_already_used'
        );
    end if;

    select score
      into v_prev_score
    from public.high_scores
    where game_id = p_game_id
      and user_id = v_uid
      and verification_status in ('pending', 'verifying', 'verified');

    insert into public.high_scores (
        game_id,
        user_id,
        score,
        achieved_at,
        updated_at,
        is_hardcore,
        credits_used,
        rom_sha256,
        replay_path,
        replay_size_bytes,
        replay_sha256,
        verification_status,
        verified_score,
        verified_at,
        verifier_version,
        verification_error,
        verification_claimed_at
    )
    values (
        p_game_id,
        v_uid,
        p_score,
        now(),
        now(),
        true,
        1,
        lower(p_rom_sha256),
        v_replay_path,
        p_replay_size_bytes,
        v_replay_sha256,
        'pending',
        null,
        null,
        null,
        null,
        null
    )
    on conflict (game_id, user_id) do update
    set score = excluded.score,
        achieved_at = now(),
        updated_at = now(),
        is_hardcore = excluded.is_hardcore,
        credits_used = excluded.credits_used,
        rom_sha256 = excluded.rom_sha256,
        replay_path = excluded.replay_path,
        replay_size_bytes = excluded.replay_size_bytes,
        replay_sha256 = excluded.replay_sha256,
        verification_status = 'pending',
        verified_score = null,
        verified_at = null,
        verifier_version = null,
        verification_error = null,
        verification_claimed_at = null
    where excluded.score > public.high_scores.score
       or public.high_scores.verification_status in ('rejected', 'error', 'unverified')
       or (
           excluded.score = public.high_scores.score
           and public.high_scores.replay_path is null
       );

    v_is_new_record := (v_prev_score is null or p_score > v_prev_score);

    select score, achieved_at
      into v_best_score, v_achieved_at
    from public.high_scores
    where game_id = p_game_id
      and user_id = v_uid
      and verification_status in ('pending', 'verifying', 'verified');

    if v_best_score is not null then
        select count(*) + 1
          into v_rank
        from public.high_scores o
        join public.profiles op on op.id = o.user_id
        where o.game_id = p_game_id
          and o.is_hardcore = true
          and o.credits_used = 1
          and o.verification_status in ('pending', 'verifying', 'verified')
          and op.is_anonymous = false
          and (
              o.score > v_best_score
              or (o.score = v_best_score and o.achieved_at < v_achieved_at)
          );
    end if;

    return jsonb_build_object(
        'accepted', true,
        'is_new_record', v_is_new_record,
        'best_score', coalesce(v_best_score, p_score),
        'rank', v_rank,
        'reason', null
    );
end;
$$;

create or replace function public.claim_pending_replay_verifications(
    p_limit integer default 5
)
returns table (
    score_id uuid,
    game_id uuid,
    slug text,
    rom_filename text,
    rom_sha256 text,
    lboard_expr text,
    claimed_score bigint,
    replay_path text,
    replay_sha256 text
)
language plpgsql
security definer
set search_path = public
as $$
declare
    v_limit integer := least(greatest(coalesce(p_limit, 5), 1), 25);
begin
    return query
    with candidates as (
        select hs.id
        from public.high_scores hs
        join public.games g on g.id = hs.game_id
        join public.profiles p on p.id = hs.user_id
        where (
            hs.verification_status = 'pending'
            or (
                hs.verification_status = 'verifying'
                and hs.verification_claimed_at < now() - interval '30 minutes'
            )
        )
          and hs.is_hardcore = true
          and hs.credits_used = 1
          and p.is_anonymous = false
          and hs.replay_path is not null
          and position('..' in hs.replay_path) = 0
          and hs.replay_path ~ ('^' || hs.game_id::text || '/' || hs.user_id::text || '(/[A-Za-z0-9_-]+)?\.bsv\.gz$')
          and coalesce(g.score_config->>'lboard', '') <> ''
        order by hs.updated_at asc
        limit v_limit
        for update of hs skip locked
    ),
    claimed as (
        update public.high_scores hs
        set verification_status = 'verifying',
            verification_claimed_at = now()
        from candidates c
        where hs.id = c.id
        returning hs.id, hs.game_id, hs.score, hs.replay_path, hs.replay_sha256
    )
    select
        cl.id as score_id,
        cl.game_id,
        g.slug,
        g.rom_filename,
        g.sha256 as rom_sha256,
        g.score_config->>'lboard' as lboard_expr,
        cl.score as claimed_score,
        cl.replay_path,
        cl.replay_sha256
    from claimed cl
    join public.games g on g.id = cl.game_id;
end;
$$;

drop function if exists public.complete_replay_verification(uuid, text, bigint, text, text);

create or replace function public.complete_replay_verification(
    p_score_id uuid,
    p_status text,
    p_verified_score bigint default null,
    p_verifier_version text default null,
    p_error text default null,
    p_expected_replay_path text default null,
    p_replay_sha256 text default null
)
returns boolean
language plpgsql
security definer
set search_path = public
as $$
declare
    v_expected_path text := nullif(btrim(coalesce(p_expected_replay_path, '')), '');
    v_replay_sha256 text := nullif(lower(btrim(coalesce(p_replay_sha256, ''))), '');
begin
    if p_status not in ('verified', 'rejected', 'error') then
        raise exception 'Invalid verification status: %', p_status;
    end if;

    update public.high_scores
    set verification_status = p_status,
        verified_score = p_verified_score,
        verified_at = now(),
        verifier_version = p_verifier_version,
        verification_error = left(p_error, 500),
        verification_claimed_at = null,
        replay_sha256 = coalesce(public.high_scores.replay_sha256, v_replay_sha256)
    where id = p_score_id
      and verification_status = 'verifying'
      and (v_expected_path is null or replay_path = v_expected_path)
      and (
          replay_sha256 is null
          or v_replay_sha256 is null
          or lower(replay_sha256) = v_replay_sha256
      )
      and (
          p_status <> 'verified'
          or (p_verified_score is not null and p_verified_score = score)
      );

    return found;
end;
$$;

-- ============================================================================
-- 7. RANKING & PROFILE RPCs: Unified filters & achieved_at ASC tiebreakers
--    (Crit 1.1, High DB 3, Med DB 3, Low DB 3)
-- ============================================================================
create or replace function public._compute_user_global_stats()
returns table (
    user_id uuid,
    global_points bigint,
    first_places bigint,
    podiums bigint,
    total_1cc bigint,
    verified_replays bigint
)
language sql
stable
security definer
set search_path = public
as $$
    with ranked_game_scores as (
        select
            hs.user_id,
            hs.game_id,
            hs.verification_status,
            row_number() over (
                partition by hs.game_id
                order by
                    hs.score desc,
                    hs.achieved_at asc
            ) as game_rank
        from public.high_scores hs
        join public.games g on g.id = hs.game_id
        join public.profiles p on p.id = hs.user_id
        where hs.is_hardcore = true
          and hs.credits_used = 1
          and hs.verification_status in ('pending', 'verifying', 'verified')
          and p.is_anonymous = false
          and coalesce(g.score_config ->> 'lboard', '') <> ''
    )
    select
        r.user_id,
        sum(
            case
                when r.game_rank = 1 then 100
                when r.game_rank = 2 then 75
                when r.game_rank = 3 then 50
                when r.game_rank between 4 and 10 then 25
                else 10
            end
        )::bigint as global_points,
        count(*) filter (where r.game_rank = 1)::bigint as first_places,
        count(*) filter (where r.game_rank <= 3)::bigint as podiums,
        count(*)::bigint as total_1cc,
        count(*) filter (where r.verification_status = 'verified')::bigint as verified_replays
    from ranked_game_scores r
    group by r.user_id;
$$;

create or replace function public.get_game_leaderboard(
    p_game_id uuid,
    p_limit integer default 50,
    p_offset integer default 0
)
returns table (
    rank bigint,
    user_id uuid,
    username text,
    avatar_url text,
    is_anonymous boolean,
    score bigint,
    achieved_at timestamptz,
    replay_path text,
    verification_status text
)
language plpgsql
stable
security definer
set search_path = public
as $$
declare
    v_limit integer := least(greatest(coalesce(p_limit, 50), 1), 100);
    v_offset integer := greatest(coalesce(p_offset, 0), 0);
begin
    return query
    with page as (
        select
            hs.user_id,
            p.username,
            p.avatar_url,
            p.is_anonymous,
            hs.score,
            hs.achieved_at,
            hs.replay_path,
            hs.verification_status
        from public.high_scores hs
        join public.profiles p on p.id = hs.user_id
        where hs.game_id = p_game_id
          and hs.is_hardcore = true
          and hs.credits_used = 1
          and hs.verification_status in ('pending', 'verifying', 'verified')
          and p.is_anonymous = false
        order by hs.score desc, hs.achieved_at asc
        limit v_limit offset v_offset
    )
    select
        (v_offset + row_number() over (order by page.score desc, page.achieved_at asc))::bigint as rank,
        page.user_id,
        page.username,
        page.avatar_url,
        page.is_anonymous,
        page.score,
        page.achieved_at,
        page.replay_path,
        page.verification_status
    from page;
end;
$$;

drop function if exists public.get_my_game_rank(uuid);

create or replace function public.get_my_game_rank(p_game_id uuid)
returns table (
    rank bigint,
    user_id uuid,
    username text,
    avatar_url text,
    is_anonymous boolean,
    score bigint,
    achieved_at timestamptz,
    replay_path text,
    verification_status text
)
language plpgsql
stable
security definer
set search_path = public
as $$
declare
    v_uid uuid := auth.uid();
begin
    if v_uid is null then
        return;
    end if;

    return query
    select
        (
            select count(*) + 1
            from public.high_scores o
            join public.profiles op on op.id = o.user_id
            where o.game_id = p_game_id
              and o.is_hardcore = true
              and o.credits_used = 1
              and o.verification_status in ('pending', 'verifying', 'verified')
              and op.is_anonymous = false
              and (
                  o.score > hs.score
                  or (o.score = hs.score and o.achieved_at < hs.achieved_at)
              )
        )::bigint as rank,
        hs.user_id,
        p.username,
        p.avatar_url,
        p.is_anonymous,
        hs.score,
        hs.achieved_at,
        hs.replay_path,
        hs.verification_status
    from public.high_scores hs
    join public.profiles p on p.id = hs.user_id
    where hs.game_id = p_game_id
      and hs.user_id = v_uid
      and hs.is_hardcore = true
      and hs.credits_used = 1
      and hs.verification_status in ('pending', 'verifying', 'verified')
      and p.is_anonymous = false;
end;
$$;

create or replace function public.get_my_high_scores()
returns table (
    game_id uuid,
    score bigint,
    achieved_at timestamptz,
    updated_at timestamptz,
    rank bigint,
    total_players bigint,
    replay_path text,
    replay_size_bytes integer,
    verification_status text
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
            hs.replay_size_bytes,
            hs.verification_status
        from public.high_scores hs
        join public.profiles p on p.id = hs.user_id
        where hs.user_id = auth.uid()
          and hs.is_hardcore = true
          and hs.credits_used = 1
          and hs.verification_status in ('pending', 'verifying', 'verified')
          and p.is_anonymous = false
    )
    select
        ms.game_id,
        ms.score,
        ms.achieved_at,
        ms.updated_at,
        (
            select count(*) + 1
            from public.high_scores other
            join public.profiles op on op.id = other.user_id
            where other.game_id = ms.game_id
              and other.is_hardcore = true
              and other.credits_used = 1
              and other.verification_status in ('pending', 'verifying', 'verified')
              and op.is_anonymous = false
              and (
                  other.score > ms.score
                  or (other.score = ms.score and other.achieved_at < ms.achieved_at)
              )
        )::bigint as rank,
        (
            select count(*)
            from public.high_scores total
            join public.profiles tp on tp.id = total.user_id
            where total.game_id = ms.game_id
              and total.is_hardcore = true
              and total.credits_used = 1
              and total.verification_status in ('pending', 'verifying', 'verified')
              and tp.is_anonymous = false
        )::bigint as total_players,
        ms.replay_path,
        ms.replay_size_bytes,
        ms.verification_status
    from my_scores ms
    order by rank asc, ms.score desc, ms.updated_at desc;
$$;

create or replace function public.get_global_leaderboard(
    p_only_following boolean default false,
    p_search text default null,
    p_limit integer default 50,
    p_offset integer default 0
)
returns table (
    global_rank bigint,
    user_id uuid,
    username text,
    avatar_seed text,
    country_code text,
    global_points bigint,
    first_places bigint,
    podiums bigint,
    total_1cc bigint,
    total_play_seconds bigint,
    is_following_by_me boolean
)
language sql
stable
security definer
set search_path = public
as $$
    with global_ranked as (
        select
            row_number() over (
                order by
                    s.global_points desc,
                    p.total_play_seconds asc,
                    p.created_at asc
            )::bigint as global_rank,
            p.id as user_id,
            p.username,
            p.avatar_seed,
            p.country_code,
            s.global_points,
            s.first_places,
            s.podiums,
            s.total_1cc,
            p.total_play_seconds,
            exists (
                select 1 from public.user_follows uf
                where uf.follower_id = auth.uid()
                  and uf.following_id = p.id
            ) as is_following_by_me
        from public._compute_user_global_stats() s
        join public.profiles p on p.id = s.user_id
        where s.global_points > 0
          and p.is_anonymous = false
    )
    select *
    from global_ranked gr
    where (
        not coalesce(p_only_following, false)
        or gr.user_id = auth.uid()
        or gr.is_following_by_me
    )
      and (
        nullif(btrim(coalesce(p_search, '')), '') is null
        or gr.username ilike '%' || replace(replace(replace(btrim(p_search), '\', '\\'), '%', '\%'), '_', '\_') || '%' escape '\'
    )
    order by gr.global_rank asc
    limit greatest(1, least(coalesce(p_limit, 50), 100))
    offset greatest(0, coalesce(p_offset, 0));
$$;

create or replace function public.get_game_leaderboard_v2(
    p_game_id uuid,
    p_mode text default '1cc',
    p_only_following boolean default false,
    p_limit integer default 50,
    p_offset integer default 0
)
returns table (
    rank bigint,
    user_id uuid,
    username text,
    avatar_seed text,
    country_code text,
    score bigint,
    play_seconds bigint,
    achieved_at timestamptz,
    replay_path text,
    verification_status text,
    is_following_by_me boolean
)
language sql
stable
security definer
set search_path = public
as $$
    with base_rows as (
        select
            row_number() over (
                order by
                    hs.score desc,
                    hs.achieved_at asc
            )::bigint as rank,
            hs.user_id,
            p.username,
            p.avatar_seed,
            p.country_code,
            hs.score,
            coalesce(ugs.play_seconds, 0)::bigint as play_seconds,
            hs.achieved_at,
            hs.replay_path,
            hs.verification_status,
            exists (
                select 1 from public.user_follows uf
                where uf.follower_id = auth.uid()
                  and uf.following_id = hs.user_id
            ) as is_following_by_me
        from public.high_scores hs
        join public.profiles p on p.id = hs.user_id
        left join public.user_game_stats ugs
            on ugs.user_id = hs.user_id and ugs.game_id = hs.game_id
        where coalesce(p_mode, '1cc') = '1cc'
          and hs.game_id = p_game_id
          and hs.is_hardcore = true
          and hs.credits_used = 1
          and hs.verification_status in ('pending', 'verifying', 'verified')
          and p.is_anonymous = false

        union all

        select
            row_number() over (
                order by
                    ugs.play_seconds desc,
                    ugs.play_count desc,
                    ugs.last_played_at desc
            )::bigint as rank,
            ugs.user_id,
            p.username,
            p.avatar_seed,
            p.country_code,
            ugs.play_seconds as score,
            ugs.play_seconds,
            ugs.last_played_at as achieved_at,
            null::text as replay_path,
            'unverified'::text as verification_status,
            exists (
                select 1 from public.user_follows uf
                where uf.follower_id = auth.uid()
                  and uf.following_id = ugs.user_id
            ) as is_following_by_me
        from public.user_game_stats ugs
        join public.profiles p on p.id = ugs.user_id
        where coalesce(p_mode, '1cc') = 'playtime'
          and ugs.game_id = p_game_id
          and ugs.play_seconds > 0
          and p.is_anonymous = false
    )
    select *
    from base_rows br
    where (
        not coalesce(p_only_following, false)
        or br.user_id = auth.uid()
        or br.is_following_by_me
    )
    order by br.rank asc
    limit greatest(1, least(coalesce(p_limit, 50), 100))
    offset greatest(0, coalesce(p_offset, 0));
$$;

create or replace function public.get_games_ranking_summary()
returns table (
    game_id uuid,
    total_players bigint,
    my_rank bigint,
    my_score bigint,
    top3_json jsonb
)
language sql
stable
security definer
set search_path = public
as $$
    with ranked as (
        select
            hs.game_id,
            hs.user_id,
            p.username,
            p.country_code,
            hs.score,
            row_number() over (
                partition by hs.game_id
                order by
                    hs.score desc,
                    hs.achieved_at asc
            )::bigint as rank
        from public.high_scores hs
        join public.games g on g.id = hs.game_id
        join public.profiles p on p.id = hs.user_id
        where hs.is_hardcore = true
          and hs.credits_used = 1
          and hs.verification_status in ('pending', 'verifying', 'verified')
          and p.is_anonymous = false
          and coalesce(g.score_config ->> 'lboard', '') <> ''
    )
    select
        g.id as game_id,
        count(r.user_id)::bigint as total_players,
        max(case when r.user_id = auth.uid() then r.rank end)::bigint as my_rank,
        max(case when r.user_id = auth.uid() then r.score end)::bigint as my_score,
        coalesce(
            (
                select jsonb_agg(
                    jsonb_build_object(
                        'rank', t.rank,
                        'user_id', t.user_id,
                        'username', t.username,
                        'country_code', t.country_code,
                        'score', t.score
                    )
                    order by t.rank asc
                )
                from ranked t
                where t.game_id = g.id and t.rank <= 3
            ),
            '[]'::jsonb
        ) as top3_json
    from public.games g
    left join ranked r on r.game_id = g.id
    where coalesce(g.score_config ->> 'lboard', '') <> ''
    group by g.id;
$$;

create or replace function public.get_public_profile(p_user_id uuid)
returns jsonb
language plpgsql
stable
security definer
set search_path = public
as $$
declare
    v_uid uuid := auth.uid();
    v_profile public.profiles;
    v_is_owner boolean;
    v_hide_private boolean;
    v_global_points bigint := 0;
    v_global_rank bigint := null;
    v_first_places bigint := 0;
    v_podiums bigint := 0;
    v_total_1cc bigint := 0;
    v_verified_replays bigint := 0;
    v_netplay_matches bigint := 0;
    v_followers_count bigint := 0;
    v_following_count bigint := 0;
    v_is_following boolean := false;
    v_high_scores jsonb := '[]'::jsonb;
    v_recent_matches jsonb := '[]'::jsonb;
begin
    select * into v_profile
    from public.profiles
    where id = p_user_id and is_anonymous = false;

    if not found then
        return null;
    end if;

    v_is_owner := (v_uid = p_user_id);
    v_hide_private := (v_profile.is_private and not v_is_owner);

    with global_ranked as (
        select
            s.user_id,
            s.global_points,
            s.first_places,
            s.podiums,
            s.total_1cc,
            s.verified_replays,
            row_number() over (
                order by s.global_points desc, pr.total_play_seconds asc, pr.created_at asc
            )::bigint as grank
        from public._compute_user_global_stats() s
        join public.profiles pr on pr.id = s.user_id
        where s.global_points > 0 and pr.is_anonymous = false
    )
    select
        coalesce(gr.global_points, 0),
        gr.grank,
        coalesce(gr.first_places, 0),
        coalesce(gr.podiums, 0),
        coalesce(gr.total_1cc, 0),
        coalesce(gr.verified_replays, 0)
    into
        v_global_points,
        v_global_rank,
        v_first_places,
        v_podiums,
        v_total_1cc,
        v_verified_replays
    from global_ranked gr
    where gr.user_id = p_user_id;

    if not v_hide_private then
        select count(*)::bigint into v_netplay_matches
        from public.match_history mh
        where mh.mode in ('lan', 'online')
          and mh.is_private = false
          and p_user_id = any(mh.participant_ids);
    end if;

    select count(*)::bigint into v_followers_count
    from public.user_follows where following_id = p_user_id;

    select count(*)::bigint into v_following_count
    from public.user_follows where follower_id = p_user_id;

    if v_uid is not null and not v_is_owner then
        select exists (
            select 1 from public.user_follows
            where follower_id = v_uid and following_id = p_user_id
        ) into v_is_following;
    end if;

    select coalesce(
        jsonb_agg(
            jsonb_build_object(
                'game_id', t.game_id,
                'score', t.score,
                'achieved_at', t.achieved_at,
                'rank', t.rank,
                'total_players', t.total_players,
                'replay_path', t.replay_path,
                'verification_status', t.verification_status
            )
            order by t.rank asc, t.score desc
        ),
        '[]'::jsonb
    )
    into v_high_scores
    from (
        select
            hs.game_id,
            hs.score,
            hs.achieved_at,
            hs.replay_path,
            hs.verification_status,
            (
                select count(*) + 1
                from public.high_scores o
                join public.profiles op on op.id = o.user_id
                where o.game_id = hs.game_id
                  and o.is_hardcore = true
                  and o.credits_used = 1
                  and o.verification_status in ('pending', 'verifying', 'verified')
                  and op.is_anonymous = false
                  and (
                      o.score > hs.score
                      or (o.score = hs.score and o.achieved_at < hs.achieved_at)
                  )
            )::bigint as rank,
            (
                select count(*)
                from public.high_scores tot
                join public.profiles tp on tp.id = tot.user_id
                where tot.game_id = hs.game_id
                  and tot.is_hardcore = true
                  and tot.credits_used = 1
                  and tot.verification_status in ('pending', 'verifying', 'verified')
                  and tp.is_anonymous = false
            )::bigint as total_players
        from public.high_scores hs
        where hs.user_id = p_user_id
          and hs.is_hardcore = true
          and hs.credits_used = 1
          and hs.verification_status in ('pending', 'verifying', 'verified')
    ) t;

    if not v_hide_private then
        select coalesce(
            jsonb_agg(
                jsonb_build_object(
                    'id', mh.id,
                    'game_id', mh.game_id,
                    'mode', mh.mode,
                    'duration_seconds', mh.duration_seconds,
                    'score', mh.score,
                    'ended_at', mh.ended_at
                )
                order by mh.ended_at desc nulls last
            ),
            '[]'::jsonb
        )
        into v_recent_matches
        from (
            select id, game_id, mode, duration_seconds, score, ended_at
            from public.match_history
            where is_private = false
              and p_user_id = any(participant_ids)
            order by ended_at desc nulls last
            limit 15
        ) mh;
    end if;

    return jsonb_build_object(
        'id', v_profile.id,
        'username', v_profile.username,
        'avatar_seed', v_profile.avatar_seed,
        'bio', v_profile.bio,
        'country_code', v_profile.country_code,
        'is_private', v_profile.is_private,
        'social_links', case when v_hide_private then '{}'::jsonb else coalesce(v_profile.social_links, '{}'::jsonb) end,
        'total_plays', case when v_hide_private then null else v_profile.total_plays end,
        'total_play_seconds', case when v_hide_private then null else v_profile.total_play_seconds end,
        'global_points', coalesce(v_global_points, 0),
        'global_rank', v_global_rank,
        'first_places', coalesce(v_first_places, 0),
        'podiums', coalesce(v_podiums, 0),
        'total_1cc', coalesce(v_total_1cc, 0),
        'verified_replays', coalesce(v_verified_replays, 0),
        'netplay_matches', case when v_hide_private then 0 else coalesce(v_netplay_matches, 0) end,
        'followers_count', coalesce(v_followers_count, 0),
        'following_count', coalesce(v_following_count, 0),
        'is_following_by_me', v_is_following,
        'high_scores', v_high_scores,
        'recent_matches', v_recent_matches
    );
end;
$$;

create or replace function public.get_follow_list(
    p_user_id uuid,
    p_direction text default 'followers',
    p_limit integer default 50,
    p_offset integer default 0
)
returns table (
    user_id uuid,
    username text,
    avatar_seed text,
    country_code text,
    global_points bigint,
    is_following_by_me boolean
)
language sql
stable
security definer
set search_path = public
as $$
    with target_ids as (
        select
            case
                when coalesce(p_direction, 'followers') = 'following' then uf.following_id
                else uf.follower_id
            end as uid,
            uf.created_at
        from public.user_follows uf
        where exists (
            select 1
            from public.profiles target_p
            where target_p.id = p_user_id
              and target_p.is_anonymous = false
        )
          and (
            (
                coalesce(p_direction, 'followers') = 'following'
                and uf.follower_id = p_user_id
            ) or (
                coalesce(p_direction, 'followers') <> 'following'
                and uf.following_id = p_user_id
            )
        )
    )
    select
        p.id as user_id,
        p.username,
        p.avatar_seed,
        p.country_code,
        coalesce(s.global_points, 0)::bigint as global_points,
        exists (
            select 1 from public.user_follows me
            where me.follower_id = auth.uid()
              and me.following_id = p.id
        ) as is_following_by_me
    from target_ids t
    join public.profiles p on p.id = t.uid
    left join public._compute_user_global_stats() s on s.user_id = p.id
    where p.is_anonymous = false
    order by t.created_at desc
    limit greatest(1, least(coalesce(p_limit, 50), 100))
    offset greatest(0, coalesce(p_offset, 0));
$$;

-- ============================================================================
-- 8. ROOMS & ROOM_MESSAGES RLS + SNAPSHOTS + COMPANION RPCs (Crit 1.3)
-- ============================================================================
create table if not exists public.room_session_snapshots (
    room_id uuid primary key,
    host_id uuid not null,
    game_id uuid not null,
    mode text not null check (mode in ('lan', 'online')),
    is_private boolean not null default false,
    participant_ids uuid[] not null default '{}',
    started_at timestamptz not null default now()
);

alter table public.room_session_snapshots enable row level security;

create or replace function public._snapshot_playing_room()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
declare
    v_src public.rooms;
    v_participants uuid[];
begin
    if tg_op = 'DELETE' then
        v_src := old;
    else
        v_src := new;
    end if;

    if v_src.status <> 'playing' then
        return coalesce(new, old);
    end if;

    select coalesce(array_agg(distinct u_id), array[v_src.host_id])
      into v_participants
    from (
        select v_src.host_id as u_id
        union all
        select (e.value ->> 'user_id')::uuid as u_id
        from jsonb_each(
            case when jsonb_typeof(v_src.slots_json) = 'object' then v_src.slots_json else '{}'::jsonb end
        ) e
        where (e.value ->> 'user_id') ~ '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
    ) t;

    insert into public.room_session_snapshots (
        room_id, host_id, game_id, mode, is_private, participant_ids, started_at
    )
    values (
        v_src.id, v_src.host_id, v_src.game_id, v_src.mode, v_src.is_private, v_participants, now()
    )
    on conflict (room_id) do update
    set participant_ids = excluded.participant_ids,
        is_private = excluded.is_private;

    return coalesce(new, old);
end;
$$;

drop trigger if exists trg_snapshot_playing_room_insupd on public.rooms;
create trigger trg_snapshot_playing_room_insupd
    after insert or update of status, slots_json on public.rooms
    for each row execute procedure public._snapshot_playing_room();

drop trigger if exists trg_snapshot_playing_room_del on public.rooms;
create trigger trg_snapshot_playing_room_del
    before delete on public.rooms
    for each row execute procedure public._snapshot_playing_room();

drop policy if exists "rooms_select_auth" on public.rooms;
drop policy if exists "rooms_select_visible" on public.rooms;
create policy "rooms_select_visible" on public.rooms
    for select to authenticated
    using (
        is_private = false
        or auth.uid() = host_id
        or (
            jsonb_typeof(slots_json) = 'object'
            and exists (
                select 1
                from jsonb_each(slots_json) s
                where s.value ->> 'user_id' = auth.uid()::text
            )
        )
    );

drop policy if exists "rooms_insert_host" on public.rooms;
create policy "rooms_insert_host" on public.rooms
    for insert to authenticated
    with check (
        auth.uid() = host_id
        and public._is_registered_user(auth.uid())
    );

drop policy if exists "rooms_update_auth" on public.rooms;
drop policy if exists "rooms_update_host" on public.rooms;
create policy "rooms_update_host" on public.rooms
    for update to authenticated
    using (auth.uid() = host_id)
    with check (auth.uid() = host_id);

drop policy if exists "room_messages_select_auth" on public.room_messages;
drop policy if exists "room_messages_select_participants" on public.room_messages;
create policy "room_messages_select_participants" on public.room_messages
    for select to authenticated
    using (
        exists (
            select 1
            from public.rooms r
            where r.id = room_id
              and (
                  r.host_id = auth.uid()
                  or (
                      jsonb_typeof(r.slots_json) = 'object'
                      and exists (
                          select 1
                          from jsonb_each(r.slots_json) s
                          where s.value ->> 'user_id' = auth.uid()::text
                      )
                  )
              )
        )
    );

drop policy if exists "room_messages_insert_own" on public.room_messages;
drop policy if exists "room_messages_insert_participants" on public.room_messages;
create policy "room_messages_insert_participants" on public.room_messages
    for insert to authenticated
    with check (
        auth.uid() = user_id
        and char_length(btrim(message)) between 1 and 240
        and exists (
            select 1
            from public.profiles p
            where p.id = auth.uid()
              and p.is_anonymous = false
              and p.username = username
        )
        and exists (
            select 1
            from public.rooms r
            where r.id = room_id
              and r.status in ('waiting', 'playing')
              and (
                  r.host_id = auth.uid()
                  or (
                      jsonb_typeof(r.slots_json) = 'object'
                      and exists (
                          select 1
                          from jsonb_each(r.slots_json) s
                          where s.value ->> 'user_id' = auth.uid()::text
                      )
                  )
              )
        )
    );

create or replace function public.find_room_by_code(p_code text)
returns setof public.rooms
language plpgsql
stable
security definer
set search_path = public
as $$
declare
    v_uid uuid := auth.uid();
    v_clean text := btrim(regexp_replace(coalesce(p_code, ''), '^#+', ''));
begin
    if v_uid is null then
        raise exception 'Not authenticated';
    end if;

    if not public._is_registered_user(v_uid) then
        raise exception 'Registered user required';
    end if;

    if v_clean = '' then
        return;
    end if;

    return query
    select r.*
    from public.rooms r
    where r.status = 'waiting'
      and (
          upper(r.code) = upper(v_clean)
          or (
              v_clean ~ '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
              and r.id = v_clean::uuid
          )
      )
    limit 1;
end;
$$;

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

    select p.username
      into v_username
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

create or replace function public.send_room_message(
    p_room_id uuid,
    p_content text
)
returns public.room_messages
language plpgsql
security definer
set search_path = public
as $$
declare
    v_uid uuid := auth.uid();
    v_username text;
    v_clean text := left(btrim(coalesce(p_content, '')), 240);
    v_msg public.room_messages;
begin
    if v_uid is null then
        raise exception 'Not authenticated';
    end if;

    select p.username
      into v_username
    from public.profiles p
    where p.id = v_uid
      and p.is_anonymous = false;

    if not found then
        raise exception 'Registered user required';
    end if;

    if v_clean = '' then
        raise exception 'Message cannot be empty';
    end if;

    if not exists (
        select 1
        from public.rooms r
        where r.id = p_room_id
          and r.status in ('waiting', 'playing')
          and (
              r.host_id = v_uid
              or (
                  jsonb_typeof(r.slots_json) = 'object'
                  and exists (
                      select 1
                      from jsonb_each(r.slots_json) s
                      where s.value ->> 'user_id' = v_uid::text
                  )
              )
          )
    ) then
        raise exception 'Not an active participant of this room';
    end if;

    insert into public.room_messages (room_id, user_id, username, message)
    values (p_room_id, v_uid, v_username, v_clean)
    returning * into v_msg;

    return v_msg;
end;
$$;

-- ============================================================================
-- 9. MATCH HISTORY & PLAY SESSION HARDENING (High DB 6, Med DB 5)
-- ============================================================================
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
    v_effective_private boolean := false;
    v_room_game_id uuid;
    v_room_mode text;
    v_room_participants uuid[];
    v_effective_room_id uuid := null;
    v_participants uuid[];
    v_duration integer;
begin
    if v_uid is null then
        raise exception 'Not authenticated';
    end if;

    if not public._is_registered_user(v_uid) then
        raise exception 'Registered user required';
    end if;

    if p_mode not in ('local', 'lan', 'online') then
        raise exception 'Invalid match mode: %', p_mode;
    end if;

    if not exists (select 1 from public.games g where g.id = p_game_id) then
        raise exception 'Game not found';
    end if;

    v_duration := least(greatest(coalesce(p_duration_seconds, 0), 0), 86400);

    if p_mode = 'local' then
        if coalesce(array_length(p_participant_ids, 1), 0) > 0
           and not (v_uid = any(p_participant_ids)) then
            raise exception 'User is not a participant of this match';
        end if;
        v_participants := array[v_uid];
        v_effective_room_id := null;
    else
        if p_room_id is null then
            raise exception 'room_id is required for lan/online matches';
        end if;

        select s.game_id, s.mode, s.is_private, s.participant_ids
          into v_room_game_id, v_room_mode, v_room_private, v_room_participants
        from public.room_session_snapshots s
        where s.room_id = p_room_id;

        if not found then
            select
                r.game_id,
                r.mode,
                r.is_private,
                (
                    select coalesce(array_agg(distinct u_id), array[r.host_id])
                    from (
                        select r.host_id as u_id
                        union all
                        select (e.value ->> 'user_id')::uuid as u_id
                        from jsonb_each(
                            case when jsonb_typeof(r.slots_json) = 'object' then r.slots_json else '{}'::jsonb end
                        ) e
                        where (e.value ->> 'user_id') ~ '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
                    ) t
                )
              into v_room_game_id, v_room_mode, v_room_private, v_room_participants
            from public.rooms r
            where r.id = p_room_id
              and r.status = 'playing';

            if not found then
                raise exception 'Valid started room not found for match';
            end if;
        end if;

        if v_room_game_id <> p_game_id or v_room_mode <> p_mode then
            raise exception 'Room metadata mismatch';
        end if;

        if not (v_uid = any(v_room_participants)) then
            raise exception 'User is not a participant of this room';
        end if;

        if coalesce(array_length(p_participant_ids, 1), 0) > 0 then
            if not (v_uid = any(p_participant_ids)) then
                raise exception 'User is not a participant of this match';
            end if;
            if not (p_participant_ids <@ v_room_participants) then
                raise exception 'Participant mismatch with room';
            end if;
        end if;

        if coalesce(array_length(v_room_participants, 1), 0) < 2 then
            raise exception 'Netplay match requires at least 2 participants';
        end if;

        v_participants := v_room_participants;

        if exists (select 1 from public.rooms r where r.id = p_room_id) then
            v_effective_room_id := p_room_id;
        end if;
    end if;

    v_effective_private := (coalesce(p_is_private, false) or coalesce(v_room_private, false));
    if v_effective_private then
        return;
    end if;

    if p_client_session_id is not null and exists (
        select 1
        from public.match_history mh
        where mh.client_session_id = p_client_session_id
          and not (v_uid = any(mh.participant_ids))
    ) then
        raise exception 'Cannot overwrite another session';
    end if;

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
        v_effective_room_id,
        v_effective_private,
        case
            when jsonb_typeof(coalesce(p_players_json, '[]'::jsonb)) = 'array' then coalesce(p_players_json, '[]'::jsonb)
            else '[]'::jsonb
        end,
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
        end
    where v_uid = any(public.match_history.participant_ids);
end;
$$;

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
    v_profile_created_at timestamptz;
    v_last_stat_at timestamptz;
    v_ref_at timestamptz;
    v_elapsed bigint;
    v_seconds bigint;
    v_session_inc integer := 0;
begin
    if v_uid is null then
        raise exception 'Not authenticated';
    end if;

    select p.created_at
      into v_profile_created_at
    from public.profiles p
    where p.id = v_uid
      and p.is_anonymous = false;

    if not found then
        return;
    end if;

    if p_seconds is null or p_seconds <= 0 then
        return;
    end if;

    if not exists (select 1 from public.games g where g.id = p_game_id) then
        return;
    end if;

    select max(ugs.last_played_at)
      into v_last_stat_at
    from public.user_game_stats ugs
    where ugs.user_id = v_uid;

    -- Rate-limit rapid repeated calls within 5 seconds
    if v_last_stat_at is not null and (now() - v_last_stat_at) < interval '5 seconds' then
        return;
    end if;

    v_ref_at := coalesce(v_last_stat_at, v_profile_created_at);
    v_elapsed := greatest(0, floor(extract(epoch from (now() - v_ref_at)))::bigint);
    v_seconds := least(p_seconds, 14400, greatest(v_elapsed + 60, 60));

    if p_is_new_session and (v_last_stat_at is null or (now() - v_last_stat_at) >= interval '10 seconds') then
        v_session_inc := 1;
    end if;

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

-- ============================================================================
-- 10. PRIVILEGE HARDENING: REVOKE FROM PUBLIC, anon ON ALL public FUNCTIONS (High DB 5)
-- ============================================================================
-- Trigger & internal helpers: no anon or authenticated access
revoke all on function public.handle_new_user() from public, anon, authenticated;
revoke all on function public._compute_user_global_stats() from public, anon, authenticated;
revoke all on function public._snapshot_playing_room() from public, anon, authenticated;
do $$
begin
    if to_regprocedure('public.rls_auto_enable()') is not null then
        execute 'revoke all on function public.rls_auto_enable() from public, anon, authenticated';
    end if;
end;
$$;

-- Service-role only worker RPCs
revoke all on function public.claim_pending_replay_verifications(integer) from public, anon, authenticated;
grant execute on function public.claim_pending_replay_verifications(integer) to service_role;

revoke all on function public.complete_replay_verification(uuid, text, bigint, text, text, text, text) from public, anon, authenticated;
grant execute on function public.complete_replay_verification(uuid, text, bigint, text, text, text, text) to service_role;

-- Authenticated-only RPCs & RLS helper
revoke all on function public._is_registered_user(uuid) from public, anon;
grant execute on function public._is_registered_user(uuid) to authenticated;

revoke all on function public.increment_game_download(uuid) from public, anon;
grant execute on function public.increment_game_download(uuid) to authenticated;

revoke all on function public.record_play_session(uuid, bigint, boolean) from public, anon;
grant execute on function public.record_play_session(uuid, bigint, boolean) to authenticated;

revoke all on function public.submit_high_score(uuid, bigint, text, smallint, boolean, text, integer, text) from public, anon;
grant execute on function public.submit_high_score(uuid, bigint, text, smallint, boolean, text, integer, text) to authenticated;

revoke all on function public.get_game_leaderboard(uuid, integer, integer) from public, anon;
grant execute on function public.get_game_leaderboard(uuid, integer, integer) to authenticated;

revoke all on function public.get_my_game_rank(uuid) from public, anon;
grant execute on function public.get_my_game_rank(uuid) to authenticated;

revoke all on function public.get_my_high_scores() from public, anon;
grant execute on function public.get_my_high_scores() to authenticated;

revoke all on function public.record_match_history(uuid, uuid, text, uuid, boolean, jsonb, uuid[], timestamptz, timestamptz, integer, bigint) from public, anon;
grant execute on function public.record_match_history(uuid, uuid, text, uuid, boolean, jsonb, uuid[], timestamptz, timestamptz, integer, bigint) to authenticated;

revoke all on function public.get_my_match_history(integer, integer) from public, anon;
grant execute on function public.get_my_match_history(integer, integer) to authenticated;

revoke all on function public.update_my_profile(text, text, text, text, boolean, jsonb) from public, anon;
grant execute on function public.update_my_profile(text, text, text, text, boolean, jsonb) to authenticated;

revoke all on function public.follow_user(uuid) from public, anon;
grant execute on function public.follow_user(uuid) to authenticated;

revoke all on function public.unfollow_user(uuid) from public, anon;
grant execute on function public.unfollow_user(uuid) to authenticated;

revoke all on function public.get_global_leaderboard(boolean, text, integer, integer) from public, anon;
grant execute on function public.get_global_leaderboard(boolean, text, integer, integer) to authenticated;

revoke all on function public.get_game_leaderboard_v2(uuid, text, boolean, integer, integer) from public, anon;
grant execute on function public.get_game_leaderboard_v2(uuid, text, boolean, integer, integer) to authenticated;

revoke all on function public.get_games_ranking_summary() from public, anon;
grant execute on function public.get_games_ranking_summary() to authenticated;

revoke all on function public.get_public_profile(uuid) from public, anon;
grant execute on function public.get_public_profile(uuid) to authenticated;

revoke all on function public.get_follow_list(uuid, text, integer, integer) from public, anon;
grant execute on function public.get_follow_list(uuid, text, integer, integer) to authenticated;

revoke all on function public.find_room_by_code(text) from public, anon;
grant execute on function public.find_room_by_code(text) to authenticated;

revoke all on function public.update_room_slot(uuid, text, integer, boolean, text, text, text) from public, anon;
grant execute on function public.update_room_slot(uuid, text, integer, boolean, text, text, text) to authenticated;

revoke all on function public.send_room_message(uuid, text) from public, anon;
grant execute on function public.send_room_message(uuid, text) to authenticated;

-- Defense-in-depth: ensure no non-extension function in public retains PUBLIC or anon EXECUTE
do $$
declare
    r record;
begin
    for r in
        select p.proname, pg_get_function_identity_arguments(p.oid) as args
        from pg_proc p
        where p.pronamespace = 'public'::regnamespace
          and p.prokind = 'f'
          and not exists (
              select 1
              from pg_depend d
              where d.objid = p.oid
                and d.deptype = 'e'
          )
    loop
        execute format('revoke all on function public.%I(%s) from public, anon', r.proname, r.args);
    end loop;
end;
$$;

commit;
