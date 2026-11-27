-- Corrige score_config de Snow Bros.: Nick & Tom (snowbros).
-- El puntaje de P1 reside en 0x0D70..0x0D73 (4 bytes BCD big-endian), no en 0x1403..0x1406.
UPDATE public.games
SET score_config = jsonb_build_object(
    'lboard', 'STA:0xH0036=1::CAN:0=1::SUB:0xH0036=0::VAL:A:b0xH0D70*1000000_A:b0xH0D71*10000_A:b0xH0D72*100_M:b0xH0D73',
    'max_valid_score', 99999999,
    'verified', true
)
WHERE slug = 'snowbros';

-- Corrige el puntaje del registro afectado en high_scores (verificado con verify_replay: 4700).
UPDATE public.high_scores
SET score = 4700,
    verified_score = 4700,
    verification_status = 'verified'
WHERE id = 'c498f3c3-de42-49e9-af91-f8c68057aef4';
