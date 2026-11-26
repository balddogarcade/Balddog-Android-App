-- Migración 031: RPC público para perfiles web (/u/[username]) consumido por rol anon
begin;

create or replace function public.get_public_web_profile(p_username text)
returns jsonb
language plpgsql
stable
security definer
set search_path = public
as $$
declare
    v_clean_username text := nullif(btrim(coalesce(p_username, '')), '');
    v_profile public.profiles;
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
    v_high_scores jsonb := '[]'::jsonb;
    v_recent_matches jsonb := '[]'::jsonb;
begin
    if v_clean_username is null then
        return null;
    end if;

    select * into v_profile
    from public.profiles
    where lower(username) = lower(v_clean_username)
      and is_anonymous = false
    limit 1;

    if not found then
        return null;
    end if;

    v_hide_private := coalesce(v_profile.is_private, false);

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
    where gr.user_id = v_profile.id;

    if not v_hide_private then
        select count(*)::bigint into v_netplay_matches
        from public.match_history mh
        where mh.mode in ('lan', 'online')
          and mh.is_private = false
          and v_profile.id = any(mh.participant_ids);
    end if;

    select count(*)::bigint into v_followers_count
    from public.user_follows where following_id = v_profile.id;

    select count(*)::bigint into v_following_count
    from public.user_follows where follower_id = v_profile.id;

    select coalesce(
        jsonb_agg(
            jsonb_build_object(
                'game_id', t.game_id,
                'game_slug', t.game_slug,
                'game_title', t.game_title,
                'system', t.system,
                'rom_filename', t.rom_filename,
                'ipfs_cid', t.ipfs_cid,
                'size_bytes', t.size_bytes,
                'sha256', t.sha256,
                'crc32', t.crc32,
                'bios', t.bios,
                'parent', t.parent,
                'score', t.score,
                'achieved_at', t.achieved_at,
                'rank', t.rank,
                'total_players', t.total_players,
                'replay_path', t.replay_path,
                'replay_size_bytes', t.replay_size_bytes,
                'verification_status', coalesce(t.verification_status, 'unverified')
            )
            order by t.rank asc, t.score desc
        ),
        '[]'::jsonb
    )
    into v_high_scores
    from (
        select
            hs.game_id,
            g.slug as game_slug,
            g.title as game_title,
            g.system,
            g.rom_filename,
            g.ipfs_cid,
            g.size_bytes,
            g.sha256,
            g.crc32,
            case
                when b.id is not null then jsonb_build_object(
                    'rom_filename', b.filename,
                    'ipfs_cid', b.ipfs_cid,
                    'size_bytes', b.size_bytes
                )
                else null
            end as bios,
            case
                when pg.id is not null then jsonb_build_object(
                    'rom_filename', pg.rom_filename,
                    'ipfs_cid', pg.ipfs_cid,
                    'size_bytes', pg.size_bytes
                )
                else null
            end as parent,
            hs.score,
            hs.achieved_at,
            hs.replay_path,
            hs.replay_size_bytes,
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
        join public.games g on g.id = hs.game_id
        left join public.game_bios b on b.id = g.bios_id
        left join public.games pg on pg.id = g.parent_game_id
        where hs.user_id = v_profile.id
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
                    'game_slug', mh.game_slug,
                    'game_title', mh.game_title,
                    'system', mh.system,
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
            select
                m.id,
                m.game_id,
                g.slug as game_slug,
                g.title as game_title,
                g.system,
                m.mode,
                m.duration_seconds,
                m.score,
                m.ended_at
            from public.match_history m
            left join public.games g on g.id = m.game_id
            where m.is_private = false
              and v_profile.id = any(m.participant_ids)
            order by m.ended_at desc nulls last
            limit 15
        ) mh;
    end if;

    return jsonb_build_object(
        'username', v_profile.username,
        'avatar_seed', v_profile.avatar_seed,
        'bio', v_profile.bio,
        'country_code', v_profile.country_code,
        'is_private', v_hide_private,
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
        'high_scores', v_high_scores,
        'recent_matches', v_recent_matches
    );
end;
$$;

revoke all on function public.get_public_web_profile(text) from public;
grant execute on function public.get_public_web_profile(text) to anon, authenticated;

commit;
