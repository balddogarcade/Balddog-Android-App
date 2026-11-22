-- Fase 8: score_configs verificados con ram_probe + captura HUD en fbneo_libretro.so
-- para altbeast, dbz2, nbbatman, powerins y tumblep.
-- Todos usan BCD word-swapped (A:b0x <HI>*10000_M:b0x <LO>) en RETRO_MEMORY_SYSTEM_RAM.

-- Altered Beast (altbeast) — 94208 bytes
--   P1_ACTIVE: 0x0820, SCORE: 0x2000/0x2002 (0 -> 500 -> 1800)
UPDATE public.games
SET score_config = jsonb_build_object(
    'lboard', 'STA:0xH0820=1::CAN:0=1::SUB:0xH0820=0::VAL:A:b0x 2000*10000_M:b0x 2002',
    'max_valid_score', 99999999,
    'verified', true
)
WHERE slug = 'altbeast';

-- Dragon Ball Z 2: Super Battle (dbz2) — 182276 bytes
--   P1_ACTIVE: 0x000B, SCORE: 0x62CE/0x62D0 (0 -> 400 -> 700)
UPDATE public.games
SET score_config = jsonb_build_object(
    'lboard', 'STA:0xH000B=1::CAN:0=1::SUB:0xH000B=0::VAL:A:b0x 62CE*10000_M:b0x 62D0',
    'max_valid_score', 99999999,
    'verified', true
)
WHERE slug = 'dbz2';

-- Ninja Baseball Bat Man (nbbatman) — 155688 bytes
--   P1_ACTIVE: 0x2453, SCORE: 0x135B2/0x135B4 (único match 540 en toda la RAM)
UPDATE public.games
SET score_config = jsonb_build_object(
    'lboard', 'STA:0xH2453=1::CAN:0=1::SUB:0xH2453=0::VAL:A:b0x 135B2*10000_M:b0x 135B4',
    'max_valid_score', 99999999,
    'verified', true
)
WHERE slug = 'nbbatman';

-- Power Instinct (powerins) — 98312 bytes
--   P1_ACTIVE: 0x834A, SCORE: 0x834C/0x834E (único match 2600 en toda la RAM)
UPDATE public.games
SET score_config = jsonb_build_object(
    'lboard', 'STA:0xH834A=1::CAN:0=1::SUB:0xH834A=0::VAL:A:b0x 834C*10000_M:b0x 834E',
    'max_valid_score', 99999999,
    'verified', true
)
WHERE slug = 'powerins';

-- Tumble Pop (tumblep) — 28673 bytes
--   P1_ACTIVE: 0x040D, SCORE: 0x3D02/0x3D04 (0 -> 200 -> 600)
UPDATE public.games
SET score_config = jsonb_build_object(
    'lboard', 'STA:0xH040D=1::CAN:0=1::SUB:0xH040D=0::VAL:A:b0x 3D02*10000_M:b0x 3D04',
    'max_valid_score', 99999999,
    'verified', true
)
WHERE slug = 'tumblep';
