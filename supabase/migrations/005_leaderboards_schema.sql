-- Fase 1: Esquema de Leaderboards (unicidad por usuario/juego, índice compuesto y cierre de escritura directa por RLS)

-- 1. Metadatos de validación y lectura de puntaje en el catálogo de juegos
alter table public.games
    add column if not exists max_valid_score bigint check (max_valid_score is null or max_valid_score > 0),
    add column if not exists score_config jsonb;

-- 2. Deduplicar posibles filas previas conservando el mejor puntaje (y el más antiguo en caso de empate)
delete from public.high_scores a
using (
    select id
    from (
        select
            id,
            row_number() over (
                partition by game_id, user_id
                order by score desc, achieved_at asc, id asc
            ) as rn
        from public.high_scores
    ) ranked
    where ranked.rn > 1
) dup
where a.id = dup.id;

-- 3. Columnas de auditoría e integridad en high_scores
alter table public.high_scores
    add column if not exists updated_at timestamptz not null default now(),
    add column if not exists is_hardcore boolean not null default true,
    add column if not exists credits_used smallint not null default 1 check (credits_used >= 1),
    add column if not exists rom_sha256 text;

-- 4. Restricción de unicidad (1 mejor puntaje por usuario y juego)
do $$
begin
    if not exists (
        select 1
        from pg_constraint
        where conname = 'uq_high_scores_game_user'
          and conrelid = 'public.high_scores'::regclass
    ) then
        alter table public.high_scores
            add constraint uq_high_scores_game_user unique (game_id, user_id);
    end if;
end;
$$;

-- 5. Índice compuesto para Top N y cálculo O(log N) de posición con desempate por achieved_at ASC
drop index if exists public.idx_high_scores_game_score;
create index if not exists idx_high_scores_leaderboard
    on public.high_scores(game_id, score desc, achieved_at asc);

-- 6. Cerrar inserción directa por RLS: toda escritura debe pasar por RPC SECURITY DEFINER
drop policy if exists "high_scores_insert_own" on public.high_scores;

drop policy if exists "high_scores_select_all" on public.high_scores;
create policy "high_scores_select_all" on public.high_scores
    for select to anon, authenticated using (true);
