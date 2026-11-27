-- Add high_scores.id (`id`) to replay-bearing RPCs so the Android app can build
-- direct links to https://bald.dog/replays/<id> for copying and sharing.

drop function if exists public.get_game_leaderboard(uuid, integer, integer);
create or replace function public.get_game_leaderboard(
    p_game_id uuid,
    p_limit integer default 50,
    p_offset integer default 0
)
returns table (
    id uuid,
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
            hs.id,
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
        page.id,
        (v_offset + row_number() over (order by page.score desc, page.achieved_at asc))::bigint as rank,
        page.user_id,
        page.username,
        page.avatar_url,
        page.is_anonymous,
        page.score,
        page.achieved_at,
        page.replay_path,
        page.verification_status
    from page
    order by page.score desc, page.achieved_at asc;
end;
$$;

revoke all on function public.get_game_leaderboard(uuid, integer, integer) from public, anon;
grant execute on function public.get_game_leaderboard(uuid, integer, integer) to authenticated;

drop function if exists public.get_my_high_scores();
create or replace function public.get_my_high_scores()
returns table (
    id uuid,
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
            hs.id,
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
        ms.id,
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
    order by ms.updated_at desc;
$$;

revoke all on function public.get_my_high_scores() from public, anon;
grant execute on function public.get_my_high_scores() to authenticated;

drop function if exists public.get_game_leaderboard_v2(uuid, text, boolean, integer, integer);
create or replace function public.get_game_leaderboard_v2(
    p_game_id uuid,
    p_mode text default '1cc',
    p_only_following boolean default false,
    p_limit integer default 50,
    p_offset integer default 0
)
returns table (
    id uuid,
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
            hs.id,
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
            null::uuid as id,
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
    select
        br.id,
        br.rank,
        br.user_id,
        br.username,
        br.avatar_seed,
        br.country_code,
        br.score,
        br.play_seconds,
        br.achieved_at,
        br.replay_path,
        br.verification_status,
        br.is_following_by_me
    from base_rows br
    where (not coalesce(p_only_following, false)) or br.is_following_by_me
    order by br.rank asc
    limit least(greatest(coalesce(p_limit, 50), 1), 100)
    offset greatest(coalesce(p_offset, 0), 0);
$$;

revoke all on function public.get_game_leaderboard_v2(uuid, text, boolean, integer, integer) from public, anon;
grant execute on function public.get_game_leaderboard_v2(uuid, text, boolean, integer, integer) to authenticated;
