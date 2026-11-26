-- 038_harden_leaderboards_privacy_and_indexes.sql
-- Phase 2:
-- 1. Revoke PUBLIC execute on cleanup_stale_rooms().
-- 2. Add partial indexes for stale room cleanup, latest replays, and netplay match_history uniqueness.
-- 3. Purge legacy invalid high_scores for mslug3 and snowbros (fixed in 033).
-- 4. Enforce is_private = false in get_public_arcade_stats() latest_replays.

revoke all on function public.cleanup_stale_rooms() from public;

create index if not exists idx_rooms_stale_cleanup
    on public.rooms (updated_at)
    where status in ('waiting', 'playing');

create index if not exists idx_high_scores_latest_replays
    on public.high_scores (updated_at desc)
    where replay_path is not null;

-- Remove any duplicate netplay match_history rows per room_id before adding unique index
delete from public.match_history a
using public.match_history b
where a.room_id is not null
  and a.mode in ('lan', 'online')
  and b.room_id = a.room_id
  and b.mode = a.mode
  and a.ctid < b.ctid;

create unique index if not exists idx_match_history_netplay_room_unique
    on public.match_history (room_id)
    where room_id is not null and mode in ('lan', 'online');

-- Purge stale invalid scores recorded before 033_fix_mslug3_and_snowbros_score_config.sql
delete from public.high_scores
where game_id in (
    select id from public.games where slug in ('mslug3', 'snowbros')
)
  and updated_at < '2026-11-26 12:40:00+00'::timestamptz;

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
                  and coalesce(hs.verification_status, 'unverified') in ('pending', 'verifying', 'verified')
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
                  and p.is_private = false
                  and hs.replay_path is not null
                  and length(btrim(hs.replay_path)) > 0
                  and coalesce(hs.verification_status, 'unverified') in ('pending', 'verifying', 'verified')
                order by hs.updated_at desc
                limit 6
            ) r
        ), '[]'::jsonb)
    );
$$;

revoke all on function public.get_public_arcade_stats() from public;
grant execute on function public.get_public_arcade_stats() to anon, authenticated;
