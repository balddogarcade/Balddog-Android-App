-- Migración 030: Exponer las últimas 6 repeticiones públicas en get_public_arcade_stats()
begin;

create or replace function public.get_public_arcade_stats()
returns jsonb
language sql
stable
security definer
set search_path = public
as $$
    select jsonb_build_object(
        'summary', jsonb_build_object(
            'total_users', (
                select count(*)::bigint
                from public.profiles
                where is_anonymous = false
            ),
            'total_games', (
                select count(*)::bigint
                from public.games
            ),
            'total_downloads', (
                select coalesce(sum(download_count), 0)::bigint
                from public.games
            ),
            'total_play_seconds', (
                select coalesce(sum(total_play_seconds), 0)::bigint
                from public.games
            ),
            'total_sessions', (
                select coalesce(sum(total_plays), 0)::bigint
                from public.profiles
                where is_anonymous = false
            ),
            'total_1cc_records', (
                select count(*)::bigint
                from public.high_scores hs
                join public.profiles p on p.id = hs.user_id
                where hs.is_hardcore = true
                  and hs.credits_used = 1
                  and coalesce(hs.verification_status, 'unverified') <> 'rejected'
                  and p.is_anonymous = false
            ),
            'verified_replays', (
                select count(*)::bigint
                from public.high_scores hs
                join public.profiles p on p.id = hs.user_id
                where hs.verification_status = 'verified'
                  and p.is_anonymous = false
            ),
            'active_countries', (
                select count(distinct country_code)::bigint
                from public.profiles
                where is_anonymous = false
                  and country_code is not null
                  and btrim(country_code) <> ''
            )
        ),
        'top_downloaded', coalesce((
            select jsonb_agg(row_to_json(t))
            from (
                select
                    slug,
                    title,
                    system,
                    max_players,
                    is_coop,
                    download_count,
                    total_play_seconds
                from public.games
                order by download_count desc, total_play_seconds desc, title asc
                limit 6
            ) t
        ), '[]'::jsonb),
        'top_played', coalesce((
            select jsonb_agg(row_to_json(t))
            from (
                select
                    slug,
                    title,
                    system,
                    max_players,
                    is_coop,
                    download_count,
                    total_play_seconds
                from public.games
                order by total_play_seconds desc, download_count desc, title asc
                limit 6
            ) t
        ), '[]'::jsonb),
        'latest_replays', coalesce((
            select jsonb_agg(row_to_json(r))
            from (
                select
                    hs.id,
                    hs.score,
                    hs.replay_path,
                    hs.replay_size_bytes,
                    coalesce(hs.verification_status, 'unverified') as verification_status,
                    hs.updated_at as achieved_at,
                    p.username,
                    p.country_code,
                    p.avatar_seed,
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
                    end as parent
                from public.high_scores hs
                join public.profiles p on p.id = hs.user_id
                join public.games g on g.id = hs.game_id
                left join public.game_bios b on b.id = g.bios_id
                left join public.games pg on pg.id = g.parent_game_id
                where p.is_anonymous = false
                  and hs.replay_path is not null
                  and length(btrim(hs.replay_path)) > 0
                  and coalesce(hs.verification_status, 'unverified') <> 'rejected'
                order by hs.updated_at desc
                limit 6
            ) r
        ), '[]'::jsonb)
    );
$$;

revoke all on function public.get_public_arcade_stats() from public;
grant execute on function public.get_public_arcade_stats() to anon, authenticated;

commit;
