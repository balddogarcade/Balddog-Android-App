-- Corrige score_config de Snow Bros.: Nick & Tom (snowbros).
-- El puntaje de P1 reside en 0x0D70..0x0D73 como 4 bytes binarios en base 100 big-endian (no BCD).
UPDATE public.games
SET score_config = jsonb_build_object(
    'lboard', 'STA:0xH0036=1::CAN:0=1::SUB:0xH0036=0::VAL:A:0xH0D70*1000000_A:0xH0D71*10000_A:0xH0D72*100_M:0xH0D73',
    'max_valid_score', 99999999,
    'verified', true
)
WHERE slug = 'snowbros';
