-- Corrige score_config de Snow Bros.: Nick & Tom (snowbros).
-- El puntaje de P1 reside en 0x159E..0x15A1 en formato word-swapped BCD (bcd4_sw), verificado con ram_probe --scan.
UPDATE public.games
SET score_config = jsonb_build_object(
    'lboard', 'STA:0xH0036=1::CAN:0=1::SUB:0xH0036=0::VAL:A:b0x 159E*10000_M:b0x 15A0',
    'max_valid_score', 99999999,
    'verified', true
)
WHERE slug = 'snowbros';
