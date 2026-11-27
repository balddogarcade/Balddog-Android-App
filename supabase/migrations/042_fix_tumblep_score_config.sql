-- Corrige score_config de Tumble Pop (tumblep).
-- El byte/word en 0x3D02 no forma parte del puntaje (sumaba 8*10000 sobre el 8800 de 0x3D04).
UPDATE public.games
SET score_config = jsonb_build_object(
    'lboard', 'STA:0xH040D=1::CAN:0=1::SUB:0xH040D=0::VAL:b0x 3D04',
    'max_valid_score', 99999999,
    'verified', true
)
WHERE slug = 'tumblep';

-- Corrige el puntaje del registro afectado en high_scores (verificado con verify_replay: 8800).
UPDATE public.high_scores
SET score = 8800,
    verified_score = 8800,
    verification_status = 'verified'
WHERE id = 'a36a4203-8cc5-4f7a-81f9-c002e10813c1';
