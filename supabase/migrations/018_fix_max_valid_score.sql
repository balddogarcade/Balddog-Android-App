-- Fix max_valid_score mismatch for ssriders and simpsons (9999 -> 99999999)
-- Aligns public.games.max_valid_score with score_config->>'max_valid_score'
update public.games
set max_valid_score = 99999999
where slug in ('ssriders', 'simpsons')
  and coalesce(max_valid_score, 0) < 99999999;
