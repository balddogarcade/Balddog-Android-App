-- 027_auth_email_verified_and_google_profiles.sql
-- Adds email_verified flag to public.profiles (auto-verified for Google OAuth and @gmail.com),
-- updates handle_new_user() to support Google OAuth users without explicit username metadata,
-- and syncs email_verified when auth.users.email_confirmed_at is set.

alter table public.profiles
    add column if not exists email_verified boolean not null default false;

create or replace function public.handle_new_user()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
declare
    v_username text;
    v_provider text;
    v_is_gmail boolean;
    v_verified boolean;
    v_base text;
begin
    v_provider := coalesce(new.raw_app_meta_data->>'provider', 'email');
    v_is_gmail := lower(coalesce(new.email, '')) like '%@gmail.com';
    v_verified := (v_provider = 'google') or v_is_gmail or (new.email_confirmed_at is not null);

    v_username := nullif(trim(coalesce(new.raw_user_meta_data->>'username', '')), '');
    if v_username is null then
        v_base := regexp_replace(coalesce(split_part(new.email, '@', 1), 'player'), '[^a-zA-Z0-9_]', '_', 'g');
        v_base := left(v_base, 14);
        if length(v_base) < 3 then
            v_base := 'player';
        end if;
        v_username := v_base || '_' || substr(replace(new.id::text, '-', ''), 1, 5);
    end if;

    insert into public.profiles (id, username, is_anonymous, email_verified)
    values (
        new.id,
        v_username,
        coalesce((new.is_anonymous)::boolean, false),
        v_verified
    )
    on conflict (id) do update
        set email_verified = public.profiles.email_verified or excluded.email_verified;

    return new;
end;
$$;

create or replace function public.handle_user_email_confirmed()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
    if old.email_confirmed_at is null and new.email_confirmed_at is not null then
        update public.profiles
        set email_verified = true
        where id = new.id;
    end if;
    return new;
end;
$$;

drop trigger if exists on_auth_user_email_confirmed on auth.users;
create trigger on_auth_user_email_confirmed
    after update of email_confirmed_at on auth.users
    for each row
    execute procedure public.handle_user_email_confirmed();
