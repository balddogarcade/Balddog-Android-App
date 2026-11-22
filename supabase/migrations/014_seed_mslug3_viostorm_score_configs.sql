-- Fase 2: score_configs verificados con ram_probe + captura HUD
-- para Metal Slug 3 (mslug3) y Violent Storm (viostorm).
-- Nota: Se verificó además que todas las ROMs Neo Geo del catálogo (mslug, mslug3)
-- son sets non-merged autocontenidos que incluyen sus archivos de BIOS dentro del .zip.

-- Metal Slug 3 (mslug3, NEOGEO) — 65536 bytes
--   P1_ACTIVE: 0xED5C, SCORE (BCD word-swapped): 0xED18/0xED1A (HUD: 7300; progresión 0 -> 9400 -> 13500)
UPDATE public.games
SET score_config = jsonb_build_object(
    'lboard', 'STA:0xHED5C=1::CAN:0=1::SUB:0xHED5C=0::VAL:A:b0x ED18*10000_M:b0x ED1A',
    'max_valid_score', 99999999,
    'verified', true
)
WHERE slug = 'mslug3';

-- Violent Storm (viostorm, Konami) — 154627 bytes
--   P1_ACTIVE: 0x0053, SCORE (BCD word-swapped): 0x0810/0x0812 (HUD: 7900)
UPDATE public.games
SET score_config = jsonb_build_object(
    'lboard', 'STA:0xH0053=1::CAN:0=1::SUB:0xH0053=0::VAL:A:b0x 0810*10000_M:b0x 0812',
    'max_valid_score', 99999999,
    'verified', true
)
WHERE slug = 'viostorm';
