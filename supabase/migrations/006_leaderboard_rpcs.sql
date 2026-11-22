-- Fase 2: RPCs seguros para envío de puntajes y consulta de Top Ranking + posición del jugador

-- 1. Envío atómico de puntaje (única vía de escritura a high_scores)
create or replace function public.submit_high_score(
    p_game_id uuid,
    p_score bigint,
    p_rom_sha256 text,
    p_credits_used smallint default 1,
    p_is_hardcore boolean default true
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
      and user_id = v_uid;

    insert into public.high_scores (
        game_id,
        user_id,
        score,
        achieved_at,
        updated_at,
        is_hardcore,
        credits_used,
        rom_sha256
    )
    values (
        p_game_id,
        v_uid,
        p_score,
        now(),
        now(),
        true,
        1,
        lower(p_rom_sha256)
    )
    on conflict (game_id, user_id) do update
    set score = excluded.score,
        achieved_at = now(),
        updated_at = now(),
        is_hardcore = excluded.is_hardcore,
        credits_used = excluded.credits_used,
        rom_sha256 = excluded.rom_sha256
    where excluded.score > public.high_scores.score;

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

revoke all on function public.submit_high_score(uuid, bigint, text, smallint, boolean) from public;
grant execute on function public.submit_high_score(uuid, bigint, text, smallint, boolean) to authenticated;

-- 2. Consulta paginada del Top Ranking por juego con datos de perfil
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
    achieved_at timestamptz
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
            hs.achieved_at
        from public.high_scores hs
        join public.profiles p on p.id = hs.user_id
        where hs.game_id = p_game_id
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
        page.achieved_at
    from page;
end;
$$;

revoke all on function public.get_game_leaderboard(uuid, integer, integer) from public;
grant execute on function public.get_game_leaderboard(uuid, integer, integer) to anon, authenticated;

-- 3. Posición exacta O(log N) del usuario autenticado en un juego
create or replace function public.get_my_game_rank(p_game_id uuid)
returns table (
    rank bigint,
    user_id uuid,
    username text,
    avatar_url text,
    is_anonymous boolean,
    score bigint,
    achieved_at timestamptz
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
            where o.game_id = p_game_id
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
        hs.achieved_at
    from public.high_scores hs
    join public.profiles p on p.id = hs.user_id
    where hs.game_id = p_game_id
      and hs.user_id = v_uid;
end;
$$;

revoke all on function public.get_my_game_rank(uuid) from public;
grant execute on function public.get_my_game_rank(uuid) to authenticated;
