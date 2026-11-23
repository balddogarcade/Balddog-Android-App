-- Fase 4: RPCs de Ranking Global, Ranking por Juego (1CC + Tiempo), Resumen por Juego, Perfil Público y Listas de Seguidores

-- 1. Helper interno: puntos globales y conteos de podios por usuario
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
                    coalesce(ugs.play_seconds, 0) asc,
                    hs.achieved_at asc
            ) as game_rank
        from public.high_scores hs
        join public.games g on g.id = hs.game_id
        join public.profiles p on p.id = hs.user_id
        left join public.user_game_stats ugs
            on ugs.user_id = hs.user_id and ugs.game_id = hs.game_id
        where hs.is_hardcore = true
          and hs.credits_used = 1
          and coalesce(hs.verification_status, 'unverified') <> 'rejected'
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

-- 2. Ranking Global de Jugadores (desempate: menor tiempo total acumulado, luego cuenta más antigua)
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
        or gr.username ilike '%' || btrim(p_search) || '%'
    )
    order by gr.global_rank asc
    limit greatest(1, least(coalesce(p_limit, 50), 100))
    offset greatest(0, coalesce(p_offset, 0));
$$;

-- 3. Ranking Individual de un Juego (modo '1cc' o 'playtime', filtro siguiendo, paginado)
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
                    coalesce(ugs.play_seconds, 0) asc,
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
            coalesce(hs.verification_status, 'unverified') as verification_status,
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
          and coalesce(hs.verification_status, 'unverified') <> 'rejected'
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

-- 4. Resumen de Top 3 y mi posición para todos los juegos con leaderboard
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
                    coalesce(ugs.play_seconds, 0) asc,
                    hs.achieved_at asc
            )::bigint as rank
        from public.high_scores hs
        join public.games g on g.id = hs.game_id
        join public.profiles p on p.id = hs.user_id
        left join public.user_game_stats ugs
            on ugs.user_id = hs.user_id and ugs.game_id = hs.game_id
        where hs.is_hardcore = true
          and hs.credits_used = 1
          and coalesce(hs.verification_status, 'unverified') <> 'rejected'
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

-- 5. Perfil Público completo (respetando is_private para redes, tiempo e historial)
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

    select count(*)::bigint into v_netplay_matches
    from public.match_history mh
    where mh.mode in ('lan', 'online')
      and p_user_id = any(mh.participant_ids);

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
            coalesce(hs.verification_status, 'unverified') as verification_status,
            (
                select count(*) + 1
                from public.high_scores o
                left join public.user_game_stats ugs_o
                    on ugs_o.user_id = o.user_id and ugs_o.game_id = o.game_id
                left join public.user_game_stats ugs_m
                    on ugs_m.user_id = hs.user_id and ugs_m.game_id = hs.game_id
                where o.game_id = hs.game_id
                  and o.is_hardcore = true
                  and o.credits_used = 1
                  and coalesce(o.verification_status, 'unverified') <> 'rejected'
                  and (
                      o.score > hs.score
                      or (o.score = hs.score and coalesce(ugs_o.play_seconds, 0) < coalesce(ugs_m.play_seconds, 0))
                      or (o.score = hs.score and coalesce(ugs_o.play_seconds, 0) = coalesce(ugs_m.play_seconds, 0) and o.achieved_at < hs.achieved_at)
                  )
            )::bigint as rank,
            (
                select count(*)
                from public.high_scores tot
                where tot.game_id = hs.game_id
                  and tot.is_hardcore = true
                  and tot.credits_used = 1
                  and coalesce(tot.verification_status, 'unverified') <> 'rejected'
            )::bigint as total_players
        from public.high_scores hs
        where hs.user_id = p_user_id
          and hs.is_hardcore = true
          and hs.credits_used = 1
          and coalesce(hs.verification_status, 'unverified') <> 'rejected'
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
        'netplay_matches', coalesce(v_netplay_matches, 0),
        'followers_count', coalesce(v_followers_count, 0),
        'following_count', coalesce(v_following_count, 0),
        'is_following_by_me', v_is_following,
        'high_scores', v_high_scores,
        'recent_matches', v_recent_matches
    );
end;
$$;

-- 6. Lista de Seguidores o Siguiendo con puntos globales
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
        where (
            coalesce(p_direction, 'followers') = 'following'
            and uf.follower_id = p_user_id
        ) or (
            coalesce(p_direction, 'followers') <> 'following'
            and uf.following_id = p_user_id
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

revoke all on function public._compute_user_global_stats() from public;

revoke all on function public.get_global_leaderboard(boolean, text, integer, integer) from public;
grant execute on function public.get_global_leaderboard(boolean, text, integer, integer) to authenticated;

revoke all on function public.get_game_leaderboard_v2(uuid, text, boolean, integer, integer) from public;
grant execute on function public.get_game_leaderboard_v2(uuid, text, boolean, integer, integer) to authenticated;

revoke all on function public.get_games_ranking_summary() from public;
grant execute on function public.get_games_ranking_summary() to authenticated;

revoke all on function public.get_public_profile(uuid) from public;
grant execute on function public.get_public_profile(uuid) to authenticated;

revoke all on function public.get_follow_list(uuid, text, integer, integer) from public;
grant execute on function public.get_follow_list(uuid, text, integer, integer) to authenticated;
