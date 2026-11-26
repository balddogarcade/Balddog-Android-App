-- Migración 028: Limpieza de cuentas anónimas residuales y RPC público de estadísticas para bald.dog
begin;

-- 1. Limpieza de posibles usuarios anónimos residuales (en la plataforma todos los usuarios están registrados)
delete from auth.users
where is_anonymous = true;

delete from public.profiles
where is_anonymous = true;

-- 2. RPC público de solo lectura para la web oficial (bald.dog)
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
        ), '[]'::jsonb)
    );
$$;

revoke all on function public.get_public_arcade_stats() from public;
grant execute on function public.get_public_arcade_stats() to anon, authenticated;

commit;
