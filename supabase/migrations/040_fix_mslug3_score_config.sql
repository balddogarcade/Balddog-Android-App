-- Corrige score_config de Metal Slug 3 (mslug3).
-- El puntaje de P1 reside en 0xF04C..0xF04F (4 bytes BCD big-endian), no en 0xED16..0xED19.
UPDATE public.games
SET score_config = jsonb_build_object(
    'lboard', 'STA:0xHED5C=1::CAN:0=1::SUB:0xHED5C=0::VAL:A:b0xHF04C*1000000_A:b0xHF04D*10000_A:b0xHF04E*100_M:b0xHF04F',
    'max_valid_score', 99999999,
    'verified', true
)
WHERE slug = 'mslug3';

-- Corrige el puntaje del registro afectado en high_scores (verificado con verify_replay: 470600).
UPDATE public.high_scores
SET score = 470600,
    verified_score = 470600,
    verification_status = 'verified'
WHERE id = 'e3e9cffe-4d9d-423b-8439-716558cae6ce';
