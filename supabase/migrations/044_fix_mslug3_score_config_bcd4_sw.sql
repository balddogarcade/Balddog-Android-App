-- Corrige score_config de Metal Slug 3 (mslug3).
-- El puntaje de P1 reside en 0xED18..0xED1B en formato word-swapped BCD (bcd4_sw):
-- [b1, b0, b3, b2] -> 0xED19*1000000 + 0xED18*10000 + 0xED1B*100 + 0xED1A.
-- 0xF04C..0xF04F contenía un valor estático (470600) ajeno al puntaje de P1.
UPDATE public.games
SET score_config = jsonb_build_object(
    'lboard', 'STA:0xHED5C=1::CAN:0=1::SUB:0xHED5C=0::VAL:A:b0xHED19*1000000_A:b0xHED18*10000_A:b0xHED1B*100_M:b0xHED1A',
    'max_valid_score', 99999999,
    'verified', true
)
WHERE slug = 'mslug3';
