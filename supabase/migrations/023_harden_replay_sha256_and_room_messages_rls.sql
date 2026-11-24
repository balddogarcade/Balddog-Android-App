-- Migration 023: Harden replay_sha256 anti-cheat, room_messages RLS, and defensive RPC guards
begin;

-- ============================================================================
-- 1. ROOM_MESSAGES RLS: Fix column-shadowing in room_messages_insert_participants
-- ============================================================================
drop policy if exists "room_messages_insert_participants" on public.room_messages;
create policy "room_messages_insert_participants" on public.room_messages
    for insert to authenticated
    with check (
        auth.uid() = user_id
        and char_length(btrim(message)) between 1 and 240
        and exists (
            select 1
            from public.profiles p
            where p.id = auth.uid()
              and p.is_anonymous = false
              and p.username = room_messages.username
        )
        and exists (
            select 1
            from public.rooms r
            where r.id = room_messages.room_id
              and r.status in ('waiting', 'playing')
              and (
                  r.host_id = auth.uid()
                  or (
                      jsonb_typeof(r.slots_json) = 'object'
                      and exists (
                          select 1
                          from jsonb_each(r.slots_json) s
                          where s.value ->> 'user_id' = auth.uid()::text
                      )
                  )
              )
        )
    );

-- ============================================================================
-- 2. HIGH_SCORES: Partial unique index on active replay_sha256
-- ============================================================================
create unique index if not exists idx_high_scores_unique_replay_sha256
    on public.high_scores (lower(replay_sha256))
    where replay_sha256 is not null
      and verification_status in ('pending', 'verifying', 'verified');

-- ============================================================================
-- 3. ANTI-CHEAT: Require non-null 64-hex replay_sha256 in submit_high_score
--    and enforce replay_sha256 uniqueness in complete_replay_verification
-- ============================================================================
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
    v_score_config jsonb;
    v_replay_path text := nullif(btrim(coalesce(p_replay_path, '')), '');
    v_replay_sha256 text := nullif(lower(btrim(coalesce(p_replay_sha256, ''))), '');
    v_prev_score bigint;
    v_best_score bigint;
    v_achieved_at timestamptz;
    v_is_new_record boolean := false;
    v_rank bigint;
begin
    if v_uid is null then
        raise exception 'Not authenticated';
    end if;

    if not public._is_registered_user(v_uid) then
        return jsonb_build_object(
            'accepted', false,
            'is_new_record', false,
            'best_score', 0,
            'rank', null,
            'reason', 'anonymous_not_allowed'
        );
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

    select sha256, max_valid_score, score_config
      into v_game_sha256, v_max_valid_score, v_score_config
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

    if not (coalesce(v_score_config, '{}'::jsonb) ? 'lboard')
       or nullif(btrim(coalesce(v_score_config ->> 'lboard', '')), '') is null then
        return jsonb_build_object(
            'accepted', false,
            'is_new_record', false,
            'best_score', 0,
            'rank', null,
            'reason', 'leaderboard_not_configured'
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

    if v_replay_path is null then
        return jsonb_build_object(
            'accepted', false,
            'is_new_record', false,
            'best_score', 0,
            'rank', null,
            'reason', 'replay_required'
        );
    end if;

    if position('..' in v_replay_path) > 0
       or left(v_replay_path, 1) = '/'
       or v_replay_path !~ ('^' || p_game_id::text || '/' || v_uid::text || '/[A-Za-z0-9_-]+\.bsv\.gz$') then
        return jsonb_build_object(
            'accepted', false,
            'is_new_record', false,
            'best_score', 0,
            'rank', null,
            'reason', 'invalid_replay_path'
        );
    end if;

    if p_replay_size_bytes is not null and (p_replay_size_bytes <= 0 or p_replay_size_bytes > 5242880) then
        return jsonb_build_object(
            'accepted', false,
            'is_new_record', false,
            'best_score', 0,
            'rank', null,
            'reason', 'invalid_replay_metadata'
        );
    end if;

    if v_replay_sha256 is null or v_replay_sha256 !~ '^[0-9a-f]{64}$' then
        return jsonb_build_object(
            'accepted', false,
            'is_new_record', false,
            'best_score', 0,
            'rank', null,
            'reason', 'invalid_replay_sha256'
        );
    end if;

    if exists (
        select 1
        from public.high_scores hs
        where hs.replay_path = v_replay_path
          and (
              hs.user_id <> v_uid
              or hs.game_id <> p_game_id
              or p_score <> hs.score
              or hs.verification_status in ('rejected', 'error')
          )
    ) then
        return jsonb_build_object(
            'accepted', false,
            'is_new_record', false,
            'best_score', 0,
            'rank', null,
            'reason', 'replay_already_used'
        );
    end if;

    if exists (
        select 1
        from public.high_scores hs
        where lower(hs.replay_sha256) = v_replay_sha256
          and (
              hs.user_id <> v_uid
              or hs.game_id <> p_game_id
              or p_score <> hs.score
              or hs.verification_status in ('rejected', 'error')
          )
    ) then
        return jsonb_build_object(
            'accepted', false,
            'is_new_record', false,
            'best_score', 0,
            'rank', null,
            'reason', 'replay_already_used'
        );
    end if;

    select score
      into v_prev_score
    from public.high_scores
    where game_id = p_game_id
      and user_id = v_uid
      and verification_status in ('pending', 'verifying', 'verified');

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
        replay_sha256,
        verification_status,
        verified_score,
        verified_at,
        verifier_version,
        verification_error,
        verification_claimed_at
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
        v_replay_path,
        p_replay_size_bytes,
        v_replay_sha256,
        'pending',
        null,
        null,
        null,
        null,
        null
    )
    on conflict (game_id, user_id) do update
    set score = excluded.score,
        achieved_at = now(),
        updated_at = now(),
        is_hardcore = excluded.is_hardcore,
        credits_used = excluded.credits_used,
        rom_sha256 = excluded.rom_sha256,
        replay_path = excluded.replay_path,
        replay_size_bytes = excluded.replay_size_bytes,
        replay_sha256 = excluded.replay_sha256,
        verification_status = 'pending',
        verified_score = null,
        verified_at = null,
        verifier_version = null,
        verification_error = null,
        verification_claimed_at = null
    where excluded.score > public.high_scores.score
       or public.high_scores.verification_status in ('rejected', 'error', 'unverified')
       or (
           excluded.score = public.high_scores.score
           and public.high_scores.replay_path is null
       );

    v_is_new_record := (v_prev_score is null or p_score > v_prev_score);

    select score, achieved_at
      into v_best_score, v_achieved_at
    from public.high_scores
    where game_id = p_game_id
      and user_id = v_uid
      and verification_status in ('pending', 'verifying', 'verified');

    if v_best_score is not null then
        select count(*) + 1
          into v_rank
        from public.high_scores o
        join public.profiles op on op.id = o.user_id
        where o.game_id = p_game_id
          and o.is_hardcore = true
          and o.credits_used = 1
          and o.verification_status in ('pending', 'verifying', 'verified')
          and op.is_anonymous = false
          and (
              o.score > v_best_score
              or (o.score = v_best_score and o.achieved_at < v_achieved_at)
          );
    end if;

    return jsonb_build_object(
        'accepted', true,
        'is_new_record', v_is_new_record,
        'best_score', coalesce(v_best_score, p_score),
        'rank', v_rank,
        'reason', null
    );
end;
$$;

create or replace function public.complete_replay_verification(
    p_score_id uuid,
    p_status text,
    p_verified_score bigint default null,
    p_verifier_version text default null,
    p_error text default null,
    p_expected_replay_path text default null,
    p_replay_sha256 text default null
)
returns boolean
language plpgsql
security definer
set search_path = public
as $$
declare
    v_expected_path text := nullif(btrim(coalesce(p_expected_replay_path, '')), '');
    v_replay_sha256 text := nullif(lower(btrim(coalesce(p_replay_sha256, ''))), '');
    v_row public.high_scores%rowtype;
    v_eff_sha256 text;
begin
    if p_status not in ('verified', 'rejected', 'error') then
        raise exception 'Invalid verification status: %', p_status;
    end if;

    select *
      into v_row
    from public.high_scores
    where id = p_score_id
      and verification_status = 'verifying'
      and (v_expected_path is null or replay_path = v_expected_path)
      and (
          replay_sha256 is null
          or v_replay_sha256 is null
          or lower(replay_sha256) = v_replay_sha256
      )
      and (
          p_status <> 'verified'
          or (p_verified_score is not null and p_verified_score = score)
      )
    for update;

    if not found then
        return false;
    end if;

    if p_status = 'verified' then
        v_eff_sha256 := coalesce(
            v_replay_sha256,
            nullif(lower(btrim(coalesce(v_row.replay_sha256, ''))), '')
        );

        if v_eff_sha256 is not null and exists (
            select 1
            from public.high_scores o
            where o.id <> p_score_id
              and lower(o.replay_sha256) = v_eff_sha256
              and (
                  o.user_id <> v_row.user_id
                  or o.game_id <> v_row.game_id
                  or o.verification_status in ('pending', 'verifying', 'verified')
              )
        ) then
            update public.high_scores
            set verification_status = 'rejected',
                verified_score = null,
                verified_at = now(),
                verifier_version = p_verifier_version,
                verification_error = 'replay_already_used',
                verification_claimed_at = null
            where id = p_score_id;

            return false;
        end if;
    end if;

    update public.high_scores
    set verification_status = p_status,
        verified_score = p_verified_score,
        verified_at = now(),
        verifier_version = p_verifier_version,
        verification_error = left(p_error, 500),
        verification_claimed_at = null,
        replay_sha256 = coalesce(public.high_scores.replay_sha256, v_replay_sha256)
    where id = p_score_id;

    return true;
end;
$$;

-- ============================================================================
-- 4. DEFENSIVE GUARDS: update_room_slot('leave') & follow_user
-- ============================================================================
create or replace function public.update_room_slot(
    p_room_id uuid,
    p_action text,
    p_slot_index integer default null,
    p_ready boolean default null,
    p_status text default null,
    p_endpoint text default null,
    p_voice_endpoint text default null
)
returns public.rooms
language plpgsql
security definer
set search_path = public
as $$
declare
    v_uid uuid := auth.uid();
    v_username text;
    v_room public.rooms;
    v_slots jsonb;
    v_my_slot_key text;
    v_target_key text;
    v_slot_count integer;
    v_existing_endpoint text;
    v_existing_voice text;
    v_eff_endpoint text;
    v_eff_voice text;
    v_new_slot jsonb;
    v_next_ready boolean;
    v_next_status text;
    v_i integer;
begin
    if v_uid is null then
        raise exception 'Not authenticated';
    end if;

    select p.username
      into v_username
    from public.profiles p
    where p.id = v_uid
      and p.is_anonymous = false;

    if not found then
        raise exception 'Registered user required';
    end if;

    select *
      into v_room
    from public.rooms r
    where r.id = p_room_id
    for update;

    if not found then
        raise exception 'Room not found';
    end if;

    v_slots := case
        when jsonb_typeof(v_room.slots_json) = 'object' then v_room.slots_json
        else '{}'::jsonb
    end;
    v_next_status := v_room.status;

    select e.key
      into v_my_slot_key
    from jsonb_each(v_slots) e
    where e.value ->> 'user_id' = v_uid::text
    limit 1;

    if p_action in ('join', 'claim') then
        if v_room.status <> 'waiting' then
            raise exception 'Room is not accepting players';
        end if;

        if p_slot_index is null then
            if v_my_slot_key is not null then
                v_target_key := v_my_slot_key;
            else
                for v_i in 0 .. (v_room.max_players - 1) loop
                    if not (v_slots ? v_i::text) then
                        v_target_key := v_i::text;
                        exit;
                    end if;
                end loop;
                if v_target_key is null then
                    raise exception 'Room is full';
                end if;
            end if;
        else
            if p_slot_index < 0 or p_slot_index >= v_room.max_players then
                raise exception 'Invalid slot index';
            end if;
            v_target_key := p_slot_index::text;
        end if;

        if (v_slots ? v_target_key)
           and (v_slots -> v_target_key ->> 'user_id') <> v_uid::text then
            raise exception 'Slot already occupied';
        end if;

        if v_my_slot_key is not null then
            v_existing_endpoint := nullif(btrim(coalesce(v_slots -> v_my_slot_key ->> 'endpoint', '')), '');
            v_existing_voice := nullif(btrim(coalesce(v_slots -> v_my_slot_key ->> 'voice_endpoint', '')), '');
            if v_my_slot_key <> v_target_key then
                v_slots := v_slots - v_my_slot_key;
            end if;
        end if;

        v_eff_endpoint := coalesce(nullif(left(btrim(coalesce(p_endpoint, '')), 128), ''), v_existing_endpoint, '');
        v_eff_voice := coalesce(nullif(left(btrim(coalesce(p_voice_endpoint, '')), 128), ''), v_existing_voice);
        v_next_ready := case
            when v_uid = v_room.host_id then true
            else coalesce(p_ready, false)
        end;

        v_new_slot := jsonb_build_object(
            'user_id', v_uid::text,
            'username', v_username,
            'endpoint', v_eff_endpoint,
            'ready', v_next_ready
        );
        if v_eff_voice is not null then
            v_new_slot := v_new_slot || jsonb_build_object('voice_endpoint', v_eff_voice);
        end if;
        v_slots := v_slots || jsonb_build_object(v_target_key, v_new_slot);

    elsif p_action = 'ready' then
        if v_room.status <> 'waiting' then
            raise exception 'Room is not in waiting state';
        end if;
        if v_my_slot_key is null then
            raise exception 'User is not seated in this room';
        end if;
        v_next_ready := coalesce(
            p_ready,
            not coalesce((v_slots -> v_my_slot_key ->> 'ready')::boolean, false)
        );
        v_slots := jsonb_set(v_slots, array[v_my_slot_key, 'ready'], to_jsonb(v_next_ready));

    elsif p_action = 'leave' then
        if v_my_slot_key is null then
            raise exception 'User is not seated in this room';
        end if;
        v_slots := v_slots - v_my_slot_key;

    elsif p_action = 'kick' then
        if v_uid <> v_room.host_id then
            raise exception 'Only host can kick players';
        end if;
        if p_slot_index is null or p_slot_index < 0 or p_slot_index >= v_room.max_players then
            raise exception 'Invalid slot index';
        end if;
        if (v_slots -> p_slot_index::text ->> 'user_id') = v_uid::text then
            raise exception 'Host cannot kick self';
        end if;
        v_slots := v_slots - p_slot_index::text;

    elsif p_action in ('status', 'start', 'close_stale') then
        v_next_status := coalesce(
            nullif(btrim(coalesce(p_status, '')), ''),
            case
                when p_action = 'start' then 'playing'
                when p_action = 'close_stale' then 'closed'
                else v_room.status
            end
        );
        if v_next_status not in ('waiting', 'playing', 'closed') then
            raise exception 'Invalid room status';
        end if;

        if v_uid <> v_room.host_id then
            if v_next_status = 'closed'
               and v_my_slot_key is not null
               and v_room.status = 'waiting'
               and v_room.updated_at < now() - interval '12 seconds' then
                null;
            else
                raise exception 'Only host can change room status';
            end if;
        end if;

        if v_next_status = 'playing' then
            if v_room.status <> 'waiting' then
                raise exception 'Room already started or closed';
            end if;
            select count(*) into v_slot_count from jsonb_object_keys(v_slots);
            if v_slot_count < 2 then
                raise exception 'At least 2 players required to start';
            end if;
            if exists (
                select 1
                from jsonb_each(v_slots) e
                where coalesce((e.value ->> 'ready')::boolean, false) = false
            ) then
                raise exception 'All seated players must be ready';
            end if;
        end if;

    else
        raise exception 'Unsupported room action: %', p_action;
    end if;

    update public.rooms
    set slots_json = v_slots,
        status = v_next_status,
        updated_at = now()
    where id = p_room_id
    returning * into v_room;

    return v_room;
end;
$$;

create or replace function public.follow_user(p_target_user_id uuid)
returns boolean
language plpgsql
security definer
set search_path = public
as $$
declare
    v_uid uuid := auth.uid();
begin
    if v_uid is null then
        raise exception 'Not authenticated';
    end if;

    if not public._is_registered_user(v_uid) then
        raise exception 'Registered user required';
    end if;

    if p_target_user_id is null or p_target_user_id = v_uid then
        raise exception 'Invalid target user';
    end if;

    if not exists (
        select 1 from public.profiles
        where id = p_target_user_id and is_anonymous = false
    ) then
        raise exception 'User not found';
    end if;

    insert into public.user_follows (follower_id, following_id)
    values (v_uid, p_target_user_id)
    on conflict (follower_id, following_id) do nothing;

    return true;
end;
$$;

-- ============================================================================
-- 5. PRIVILEGE HYGIENE
-- ============================================================================
revoke all on function public.submit_high_score(uuid, bigint, text, smallint, boolean, text, integer, text) from public, anon;
grant execute on function public.submit_high_score(uuid, bigint, text, smallint, boolean, text, integer, text) to authenticated;

revoke all on function public.complete_replay_verification(uuid, text, bigint, text, text, text, text) from public, anon, authenticated;
grant execute on function public.complete_replay_verification(uuid, text, bigint, text, text, text, text) to service_role;

revoke all on function public.update_room_slot(uuid, text, integer, boolean, text, text, text) from public, anon;
grant execute on function public.update_room_slot(uuid, text, integer, boolean, text, text, text) to authenticated;

revoke all on function public.follow_user(uuid) from public, anon;
grant execute on function public.follow_user(uuid) to authenticated;

commit;
