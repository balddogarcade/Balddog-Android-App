-- 029_welcome_email_sent_flag.sql
-- Adds welcome_email_sent flag to public.profiles to track idempotent
-- welcome email delivery for newly registered users (email or Google OAuth).

alter table public.profiles
    add column if not exists welcome_email_sent boolean not null default false;
