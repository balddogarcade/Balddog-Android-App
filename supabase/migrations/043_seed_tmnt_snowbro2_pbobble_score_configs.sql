-- Certifica e integra los leaderboards 1CC (score_config) para tmnt2, tmnt, snowbro2 y pbobble.
-- Expresiones verificadas empíricamente en RAM contra fbneo_libretro.so y replays .bsv.gz con verify_replay.

UPDATE public.games
SET max_valid_score = 9999,
    score_config = jsonb_build_object(
        'lboard', 'STA:0xH00B0=1::CAN:0=1::SUB:0xH00B0=0::VAL:b0x 0104',
        'max_valid_score', 9999,
        'verified', true
    )
WHERE slug = 'tmnt2';

UPDATE public.games
SET max_valid_score = 9999,
    score_config = jsonb_build_object(
        'lboard', 'STA:0xH00A0=1::CAN:0=1::SUB:0xH00A0=0::VAL:b0x 0104',
        'max_valid_score', 9999,
        'verified', true
    )
WHERE slug = 'tmnt';

UPDATE public.games
SET max_valid_score = 9999999,
    score_config = jsonb_build_object(
        'lboard', 'STA:0xH00DE=1::CAN:0=1::SUB:0xH00DE=2::VAL:A:b0x 00DA*10000_M:b0x 00DC',
        'max_valid_score', 9999999,
        'verified', true
    )
WHERE slug = 'snowbro2';

UPDATE public.games
SET max_valid_score = 99999999,
    score_config = jsonb_build_object(
        'lboard', 'STA:0xH26E4=1::CAN:0=1::SUB:0xH26E4=0::VAL:A:0x 7058*65536_M:0x 705A',
        'max_valid_score', 99999999,
        'verified', true
    )
WHERE slug = 'pbobble';
