-- Corrige score_config de Double Dragon II: The Revenge (ddragon2).
-- Los bytes 0x03ED y 0x03EE no forman parte del puntaje BCD (0x03ED=0x50 causaba +500000);
-- el puntaje de P1 reside en los 2 bytes BCD little-endian 0x03EB..0x03EC.
UPDATE public.games
SET score_config = jsonb_build_object(
    'lboard', 'STA:0xH000F=1::CAN:0=1::SUB:0xH000F=0::VAL:A:b0xH03EC*100_M:b0xH03EB',
    'max_valid_score', 99999999,
    'verified', true
)
WHERE slug = 'ddragon2';

-- Corrige el puntaje del registro afectado en high_scores (verificado con verify_replay: 1100).
UPDATE public.high_scores
SET score = 1100,
    verified_score = 1100,
    verification_status = 'verified'
WHERE id = 'f14cc470-4885-4326-ae1a-5457fdc1caa0';
