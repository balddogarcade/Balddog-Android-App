-- Fase 5: Bucket de Supabase Storage para replays deterministas (.bsv.gz)
-- y extensión de public.high_scores + RPCs de leaderboard.

-- 1. Bucket público de lectura para replays (límite 5 MB por archivo)
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values (
    'replays',
    'replays',
    true,
    5242880,
    array['application/gzip', 'application/x-gzip', 'application/octet-stream']
)
on conflict (id) do update
set public = excluded.public,
    file_size_limit = excluded.file_size_limit,
    allowed_mime_types = excluded.allowed_mime_types;

drop policy if exists "replays_public_read" on storage.objects;
create policy "replays_public_read"
on storage.objects for select
to anon, authenticated
using (bucket_id = 'replays');

drop policy if exists "replays_owner_insert" on storage.objects;
create policy "replays_owner_insert"
on storage.objects for insert
to authenticated
with check (
    bucket_id = 'replays'
    and name like '%/' || auth.uid()::text || '.bsv.gz'
);

drop policy if exists "replays_owner_update" on storage.objects;
create policy "replays_owner_update"
on storage.objects for update
to authenticated
using (
    bucket_id = 'replays'
    and name like '%/' || auth.uid()::text || '.bsv.gz'
)
with check (
    bucket_id = 'replays'
    and name like '%/' || auth.uid()::text || '.bsv.gz'
);

drop policy if exists "replays_owner_delete" on storage.objects;
create policy "replays_owner_delete"
on storage.objects for delete
to authenticated
using (
    bucket_id = 'replays'
    and name like '%/' || auth.uid()::text || '.bsv.gz'
);

-- 2. Columnas de replay en high_scores
alter table public.high_scores
    add column if not exists replay_path text,
    add column if not exists replay_size_bytes integer,
    add column if not exists replay_sha256 text;

-- 3. Actualizar submit_high_score para registrar replay_path opcional
drop function if exists public.submit_high_score(uuid, bigint, text, smallint, boolean);

create or replace function public.submit_high_score(
    p_game_id uuid,
    p_score bigint,
    p_rom_sha256 text,
    p_credits_used smallint default 1,
    p_is_hardcore boolean default true,
    p_replay_path text default null,
    p_replay_size_bytes integer default null,
    p_replay_sha256 text default null
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
        rom_sha256,
        replay_path,
        replay_size_bytes,
        replay_sha256
    )
    values (
        p_game_id,
        v_uid,
        p_score,
        now(),
        now(),
        true,
        1,
        lower(p_rom_sha256),
        p_replay_path,
        p_replay_size_bytes,
        p_replay_sha256
    )
    on conflict (game_id, user_id) do update
    set score = excluded.score,
        achieved_at = now(),
        updated_at = now(),
        is_hardcore = excluded.is_hardcore,
        credits_used = excluded.credits_used,
        rom_sha256 = excluded.rom_sha256,
        replay_path = coalesce(excluded.replay_path, public.high_scores.replay_path),
        replay_size_bytes = coalesce(excluded.replay_size_bytes, public.high_scores.replay_size_bytes),
        replay_sha256 = coalesce(excluded.replay_sha256, public.high_scores.replay_sha256)
    where excluded.score > public.high_scores.score
       or (excluded.score = public.high_scores.score and excluded.replay_path is not null and public.high_scores.replay_path is null);

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

revoke all on function public.submit_high_score(uuid, bigint, text, smallint, boolean, text, integer, text) from public;
grant execute on function public.submit_high_score(uuid, bigint, text, smallint, boolean, text, integer, text) to authenticated;

-- 4. Actualizar get_game_leaderboard para devolver replay_path
drop function if exists public.get_game_leaderboard(uuid, integer, integer);

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
    achieved_at timestamptz,
    replay_path text
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
            hs.achieved_at,
            hs.replay_path
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
        page.achieved_at,
        page.replay_path
    from page;
end;
$$;

revoke all on function public.get_game_leaderboard(uuid, integer, integer) from public;
grant execute on function public.get_game_leaderboard(uuid, integer, integer) to anon, authenticated;
