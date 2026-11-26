-- Fix score_config for The Simpsons (simpsons, Konami 6809)
-- Empirical RAM probe on simpsons.zip showed 0x1B04 is not the P1 score
-- (reading u32 LE at 0x1B04 produced spurious thousands/millions).
-- Verified Konami 6809 Work RAM offsets:
--   0x1050: 0 in boot/attract, 1 continuously during active gameplay, 0 on Continue/Game Over
--   0x11D4..0x11D5: P1 enemy-defeat counter in 16-bit Big-Endian BCD (0x09 -> 0x10 -> 0x11)

UPDATE public.games
SET score_config = jsonb_build_object(
    'lboard', 'STA:0xH1050=1::CAN:0=1::SUB:0xH1050=0::VAL:A:b0xH11D4*100_M:b0xH11D5',
    'max_valid_score', 99999999,
    'verified', true
)
WHERE slug = 'simpsons';

-- Clean up previous invalid scores recorded with the broken 0x1B04 expression
DELETE FROM public.high_scores
WHERE game_id = (SELECT id FROM public.games WHERE slug = 'simpsons');
