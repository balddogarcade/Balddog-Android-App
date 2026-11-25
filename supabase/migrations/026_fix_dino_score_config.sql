-- Fix score_config for Cadillacs and Dinosaurs (dino, Capcom CPS1)
-- Empirical RAM probe on dino.zip showed 0xB275 flickers 1 -> 0 -> 1 during character select
-- (frames 1455..1623), causing false-positive 2-credit detection in CoinTracker.
-- Verified stable CpsRamFF offsets (16-bit word-swapped):
--   0xB274 (M68K 0xFFB275): 0 in attract/char-select, 1 continuously during active stage gameplay
--   0x84D1 (M68K 0xFF84D0 kStep): 0x04 = char select, 0x0C (12) = playing, 0x10 (16) = game over / continue
--   0xB374..0xB377 (M68K 0xFFB374 kScore): P1 score in BCD (two 16-bit LE words)

UPDATE public.games
SET score_config = jsonb_build_object(
    'format', 'rcheevos',
    'lboard', 'STA:0xHB274=1_0xH84D1=12::CAN:0=1::SUB:0xH84D1=16::VAL:A:b0x B374*10000_M:b0x B376'
)
WHERE slug = 'dino';
