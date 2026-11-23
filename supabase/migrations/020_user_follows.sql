-- Fase 3: Sistema de seguidores asimétrico (tipo Twitter) e índices

create table if not exists public.user_follows (
    follower_id uuid not null references public.profiles(id) on delete cascade,
    following_id uuid not null references public.profiles(id) on delete cascade,
    created_at timestamptz not null default now(),
    primary key (follower_id, following_id),
    constraint chk_user_follows_no_self check (follower_id <> following_id)
);

create index if not exists idx_user_follows_follower
    on public.user_follows(follower_id, created_at desc);

create index if not exists idx_user_follows_following
    on public.user_follows(following_id, created_at desc);

alter table public.user_follows enable row level security;

drop policy if exists "user_follows_select_auth" on public.user_follows;
create policy "user_follows_select_auth" on public.user_follows
    for select to authenticated
    using (true);

drop policy if exists "user_follows_insert_own" on public.user_follows;
create policy "user_follows_insert_own" on public.user_follows
    for insert to authenticated
    with check (auth.uid() = follower_id);

drop policy if exists "user_follows_delete_own" on public.user_follows;
create policy "user_follows_delete_own" on public.user_follows
    for delete to authenticated
    using (auth.uid() = follower_id);

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

create or replace function public.unfollow_user(p_target_user_id uuid)
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

    delete from public.user_follows
    where follower_id = v_uid
      and following_id = p_target_user_id;

    return true;
end;
$$;

revoke all on function public.follow_user(uuid) from public;
grant execute on function public.follow_user(uuid) to authenticated;

revoke all on function public.unfollow_user(uuid) from public;
grant execute on function public.unfollow_user(uuid) to authenticated;
