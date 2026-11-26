-- Fix mslug3 and snowbros leaderboard score_config expressions
-- mslug3: 0xED16..0xED19 (4-byte Big-Endian BCD) instead of shifted 0xED18..0xED1B
-- snowbros: 0x1403..0x1406 (4-byte Big-Endian BCD) instead of 0x03FC..0x03FF

UPDATE public.games
SET score_config = jsonb_set(
    COALESCE(score_config, '{}'::jsonb),
    '{lboard}',
    '"STA:0xHED5C=1::CAN:0=1::SUB:0xHED5C=0::VAL:A:b0xHED16*1000000_A:b0xHED17*10000_A:b0xHED18*100_M:b0xHED19"'::jsonb
)
WHERE slug = 'mslug3';

UPDATE public.games
SET score_config = jsonb_set(
    COALESCE(score_config, '{}'::jsonb),
    '{lboard}',
    '"STA:0xH0036=1::CAN:0=1::SUB:0xH0036=0::VAL:A:b0xH1403*1000000_A:b0xH1404*10000_A:b0xH1405*100_M:b0xH1406"'::jsonb
)
WHERE slug = 'snowbros';
