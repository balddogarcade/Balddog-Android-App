-- Corrige score_config de Snow Bros.: Nick & Tom (snowbros).
-- El puntaje de P1 reside en 0x1580..0x1583 en formato word-swapped BCD (bcd4_sw), verificado visualmente en Floor 1 (1P = 10 -> 50) y auditado sin flickers (flickers=0) exigiendo puntaje inicial 0 en STA para evitar falsos disparos durante el test de RAM del boot.
UPDATE public.games
SET score_config = jsonb_build_object(
    'lboard', 'STA:0xH0036=1_0x 1580=0_0x 1582=0::CAN:0=1::SUB:0xH0036=0::VAL:A:b0x 1580*10000_M:b0x 1582',
    'max_valid_score', 99999999,
    'verified', true
)
WHERE slug = 'snowbros';
