-- Enable pg_cron extension for automated database maintenance
CREATE EXTENSION IF NOT EXISTS pg_cron;

-- Function to close stale/orphaned rooms inactive for more than 30 minutes
CREATE OR REPLACE FUNCTION public.cleanup_stale_rooms()
RETURNS integer
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    closed_count integer;
BEGIN
    UPDATE public.rooms
    SET status = 'closed',
        updated_at = now()
    WHERE status IN ('waiting', 'playing')
      AND updated_at < now() - interval '30 minutes';

    GET DIAGNOSTICS closed_count = ROW_COUNT;
    RETURN closed_count;
END;
$$;

-- Unschedule existing job if present to keep migration idempotent
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM cron.job WHERE jobname = 'cleanup-stale-rooms') THEN
        PERFORM cron.unschedule('cleanup-stale-rooms');
    END IF;
END;
$$;

-- Schedule stale room cleanup every 5 minutes
SELECT cron.schedule(
    'cleanup-stale-rooms',
    '*/5 * * * *',
    $$SELECT public.cleanup_stale_rooms();$$
);
