-- Fase 5 (Anti-Cheat): Verificación determinista de replays en servidor
-- Agrega máquina de estados de verificación a public.high_scores, filtra puntajes
-- rechazados de los leaderboards y expone RPCs exclusivas para service_role.

-- 1. Columnas de estado de verificación en public.high_scores
alter table public.high_scores
    add column if not exists verification_status text not null default 'unverified'
        check (verification_status in ('unverified', 'pending', 'verifying', 'verified', 'rejected', 'error')),
    add column if not exists verified_score bigint,
    add column if not exists verified_at timestamptz,
    add column if not exists verifier_version text,
    add column if not exists verification_error text,
    add column if not exists verification_claimed_at timestamptz;

-- Marcar replays ya existentes sin verificar como 'pending' para que el worker pueda auditarlos
update public.high_scores
set verification_status = 'pending'
where replay_path is not null
  and verification_status = 'unverified';

create index if not exists idx_high_scores_verification_queue
    on public.high_scores (updated_at asc)
    where verification_status in ('pending', 'verifying');

-- 2. Actualizar submit_high_score para inicializar verification_status y excluir rechazados del rank
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
    v_prev_score bigint;
    v_best_score bigint;
    v_achieved_at timestamptz;
    v_is_new_record boolean := false;
    v_rank bigint;
    v_initial_status text;
begin
    if v_uid is null then
        raise exception 'Not authenticated';
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

    select sha256, max_valid_score
      into v_game_sha256, v_max_valid_score
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

    select score
      into v_prev_score
    from public.high_scores
    where game_id = p_game_id
      and user_id = v_uid
      and verification_status <> 'rejected';

    v_initial_status := case
        when p_replay_path is not null and length(trim(p_replay_path)) > 0 then 'pending'
        else 'unverified'
    end;

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
        p_replay_path,
        p_replay_size_bytes,
        p_replay_sha256,
        v_initial_status,
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
        verification_status = excluded.verification_status,
        verified_score = null,
        verified_at = null,
        verifier_version = null,
        verification_error = null,
        verification_claimed_at = null
    where excluded.score > public.high_scores.score
       or public.high_scores.verification_status = 'rejected'
       or (
           excluded.score = public.high_scores.score
           and excluded.replay_path is not null
           and public.high_scores.replay_path is null
       );

    v_is_new_record := (v_prev_score is null or p_score > v_prev_score);

    select score, achieved_at
      into v_best_score, v_achieved_at
    from public.high_scores
    where game_id = p_game_id
      and user_id = v_uid;

    select count(*) + 1
      into v_rank
    from public.high_scores o
    where o.game_id = p_game_id
      and o.verification_status <> 'rejected'
      and (
          o.score > v_best_score
          or (o.score = v_best_score and o.achieved_at < v_achieved_at)
      );

    return jsonb_build_object(
        'accepted', true,
        'is_new_record', v_is_new_record,
        'best_score', v_best_score,
        'rank', v_rank,
        'reason', null
    );
end;
$$;

revoke all on function public.submit_high_score(uuid, bigint, text, smallint, boolean, text, integer, text) from public;
grant execute on function public.submit_high_score(uuid, bigint, text, smallint, boolean, text, integer, text) to authenticated;

-- 3. Actualizar get_game_leaderboard para exponer verification_status y excluir rechazados
drop function if exists public.get_game_leaderboard(uuid, integer, integer);

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
          and hs.verification_status <> 'rejected'
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

revoke all on function public.get_game_leaderboard(uuid, integer, integer) from public;
grant execute on function public.get_game_leaderboard(uuid, integer, integer) to anon, authenticated;

-- 4. Actualizar get_my_high_scores para incluir verification_status
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
              and other.verification_status <> 'rejected'
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
              and total.verification_status <> 'rejected'
        )::bigint as total_players,
        ms.replay_path,
        ms.replay_size_bytes,
        ms.verification_status
    from my_scores ms
    order by rank asc, ms.score desc, ms.updated_at desc;
$$;

revoke all on function public.get_my_high_scores() from public;
grant execute on function public.get_my_high_scores() to authenticated;

-- 5. RPCs exclusivas de worker (service_role) para reclamar y completar verificaciones
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
        where (
            hs.verification_status = 'pending'
            or (
                hs.verification_status = 'verifying'
                and hs.verification_claimed_at < now() - interval '10 minutes'
            )
        )
          and hs.replay_path is not null
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

revoke all on function public.claim_pending_replay_verifications(integer) from public, anon, authenticated;
grant execute on function public.claim_pending_replay_verifications(integer) to service_role;

create or replace function public.complete_replay_verification(
    p_score_id uuid,
    p_status text,
    p_verified_score bigint default null,
    p_verifier_version text default null,
    p_error text default null
)
returns void
language plpgsql
security definer
set search_path = public
as $$
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
        verification_claimed_at = null
    where id = p_score_id;
end;
$$;

revoke all on function public.complete_replay_verification(uuid, text, bigint, text, text) from public, anon, authenticated;
grant execute on function public.complete_replay_verification(uuid, text, bigint, text, text) to service_role;
