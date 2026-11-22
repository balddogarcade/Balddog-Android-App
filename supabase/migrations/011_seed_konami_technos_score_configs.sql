-- Fase 7: score_config verificado con ram_probe + captura HUD en fbneo_libretro.so
-- para Sunset Riders (ssriders).
-- Layout en RETRO_MEMORY_SYSTEM_RAM (39040 bytes):
--   P1_ACTIVE: 0x004C (0 -> 1 al iniciar partida)
--   SCORE (BCD word-swapped): 0x0206 (hi) / 0x0208 (lo) -> 0 -> 200 -> 300 verificados en HUD ($300)

UPDATE public.games
SET score_config = jsonb_build_object(
    'lboard', 'STA:0xH004C=1::CAN:0=1::SUB:0xH004C=0::VAL:A:b0x 0206*10000_M:b0x 0208',
    'max_valid_score', 99999999,
    'verified', true
)
WHERE slug = 'ssriders';
