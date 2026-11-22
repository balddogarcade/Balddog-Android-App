-- Fase 9 / Complemento: score_configs verificados con ram_probe + captura HUD
-- para ghouls, uccops, ddragon2, simpsons y robocop.

-- Ghouls 'n Ghosts (ghouls, CPS1) — 65536 bytes
--   P1_ACTIVE: 0x02D4, SCORE (BCD word-swapped): 0x07F2/0x07F4 (HUD: 1100)
UPDATE public.games
SET score_config = jsonb_build_object(
    'lboard', 'STA:0xH02D4=1::CAN:0=1::SUB:0xH02D4=0::VAL:A:b0x 07F2*10000_M:b0x 07F4',
    'max_valid_score', 99999999,
    'verified', true
)
WHERE slug = 'ghouls';

-- Undercover Cops (uccops, Irem M92) — 155688 bytes
--   P1_ACTIVE: 0x2137, SCORE (BCD little-endian): 0x14E34..0x14E37 (único match 4040 en toda la RAM)
UPDATE public.games
SET score_config = jsonb_build_object(
    'lboard', 'STA:0xH2137=1::CAN:0=1::SUB:0xH2137=0::VAL:A:b0xH14E37*1000000_A:b0xH14E36*10000_A:b0xH14E35*100_M:b0xH14E34',
    'max_valid_score', 99999999,
    'verified', true
)
WHERE slug = 'uccops';

-- Double Dragon II: The Revenge (ddragon2, Technos) — 22656 bytes
--   P1_ACTIVE: 0x000F, SCORE (BCD little-endian): 0x03EB..0x03EE (único match 1700 en toda la RAM)
UPDATE public.games
SET score_config = jsonb_build_object(
    'lboard', 'STA:0xH000F=1::CAN:0=1::SUB:0xH000F=0::VAL:A:b0xH03EE*1000000_A:b0xH03ED*10000_A:b0xH03EC*100_M:b0xH03EB',
    'max_valid_score', 99999999,
    'verified', true
)
WHERE slug = 'ddragon2';

-- The Simpsons (simpsons, Konami) — 22530 bytes
--   P1_ACTIVE: 0x1050, SCORE (u32 little-endian): 0x1B04 (HUD: 12)
UPDATE public.games
SET score_config = jsonb_build_object(
    'lboard', 'STA:0xH1050=1::CAN:0=1::SUB:0xH1050=0::VAL:0xX1B04',
    'max_valid_score', 99999999,
    'verified', true
)
WHERE slug = 'simpsons';

-- RoboCop (robocop, Data East) — 101680 bytes
--   P1_ACTIVE: 0x04A1, SCORE (BCD word-swapped): 0x3214/0x3216 (HUD: 1400)
UPDATE public.games
SET score_config = jsonb_build_object(
    'lboard', 'STA:0xH04A1=1::CAN:0=1::SUB:0xH04A1=0::VAL:A:b0x 3214*10000_M:b0x 3216',
    'max_valid_score', 99999999,
    'verified', true
)
WHERE slug = 'robocop';
