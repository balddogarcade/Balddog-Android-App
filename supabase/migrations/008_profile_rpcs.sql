-- Fase 1 (Perfil): RPC para obtener todos los récords personales del usuario y su posición global por juego

create or replace function public.get_my_high_scores()
returns table (
    game_id uuid,
    score bigint,
    achieved_at timestamptz,
    updated_at timestamptz,
    rank bigint,
    total_players bigint
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
            hs.updated_at
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
        )::bigint as total_players
    from my_scores ms
    order by rank asc, ms.score desc, ms.updated_at desc;
$$;

revoke all on function public.get_my_high_scores() from public;
grant execute on function public.get_my_high_scores() to authenticated;
