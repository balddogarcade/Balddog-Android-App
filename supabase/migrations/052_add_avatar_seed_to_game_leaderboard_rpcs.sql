-- Add profiles.avatar_seed (`avatar_seed`) to get_game_leaderboard and get_my_game_rank
-- so GameLeaderboardSheet renders the user's real DiceBear pixel-art avatar instead of
-- falling back to username.

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
    avatar_seed text,
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
            p.avatar_seed,
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
        page.avatar_seed,
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

drop function if exists public.get_my_game_rank(uuid);
create or replace function public.get_my_game_rank(p_game_id uuid)
returns table (
    rank bigint,
    user_id uuid,
    username text,
    avatar_url text,
    avatar_seed text,
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
        p.avatar_seed,
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

revoke all on function public.get_my_game_rank(uuid) from public, anon;
grant execute on function public.get_my_game_rank(uuid) to authenticated;
