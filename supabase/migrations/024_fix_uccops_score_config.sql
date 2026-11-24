-- Fix score_config for Undercover Cops (uccops, Irem M92)
-- Replace spurious Video RAM address (0x2137) with stable CPU V33 Work RAM flags:
--   P1 Lives:  0x14E3B (MAME 0xE3E3B, DrvV33RAM offset 0x3E3B)
--   P1 Energy: 0x1117E (MAME 0xE017E, DrvV33RAM offset 0x017E)
--   P1 Score:  0x14E34..0x14E37 (MAME 0xE3E34, BCD little-endian)
UPDATE public.games
SET score_config = jsonb_build_object(
    'lboard', 'STA:0xH14E3B>0_0xH1117E>0::CAN:0=1::SUB:0xH14E3B=0_0xH1117E=0::VAL:A:b0xH14E37*1000000_A:b0xH14E36*10000_A:b0xH14E35*100_M:b0xH14E34',
    'max_valid_score', 99999999,
    'verified', true
)
WHERE slug = 'uccops';
