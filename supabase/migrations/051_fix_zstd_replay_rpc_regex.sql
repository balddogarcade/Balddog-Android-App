-- Allow both .bsv.gz and .bsv.zst replays in submit_high_score and claim_pending_replay_verifications

CREATE OR REPLACE FUNCTION public.claim_pending_replay_verifications(p_limit integer DEFAULT 5)
 RETURNS TABLE(score_id uuid, game_id uuid, slug text, rom_filename text, rom_sha256 text, lboard_expr text, claimed_score bigint, replay_path text, replay_sha256 text)
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
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
          and hs.replay_path ~ ('^' || hs.game_id::text || '/' || hs.user_id::text || '(/[A-Za-z0-9_-]+)?\.bsv\.(gz|zst)$')
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
$function$;

CREATE OR REPLACE FUNCTION public.submit_high_score(p_game_id uuid, p_score bigint, p_rom_sha256 text, p_credits_used smallint DEFAULT 1, p_is_hardcore boolean DEFAULT true, p_replay_path text DEFAULT NULL::text, p_replay_size_bytes integer DEFAULT NULL::integer, p_replay_sha256 text DEFAULT NULL::text)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
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
       or v_replay_path !~ ('^' || p_game_id::text || '/' || v_uid::text || '/[A-Za-z0-9_-]+\.bsv\.(gz|zst)$') then
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

    if v_replay_sha256 is null or v_replay_sha256 !~ '^[0-9a-f]{64}$' then
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

    if exists (
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
$function$;
