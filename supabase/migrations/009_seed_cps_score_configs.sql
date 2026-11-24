-- Fase 4/5: Configuraciones rcheevos verificadas contra RETRO_MEMORY_SYSTEM_RAM (CpsRamFF) para CPS1 y CPS2
-- Captain Commando (captcomm) CpsRamFF (64KB, 16-bit word-swapped):
--   0xA855: P1 active (1 = en partida / continue, 0 = game over / attract)
--   0xAA7D: P1 lives (>0 al iniciar)
--   0xAA74..0xAA77: P1 score en BCD (dos palabras de 16 bits LE)

update public.games
set score_config = case slug
    when 'captcomm' then jsonb_build_object(
        'format', 'rcheevos',
        'lboard', 'STA:0xHA855=1_0xHAA7D>0::CAN:0=1::SUB:0xHA855=0::VAL:A:b0x AA74*10000_M:b0x AA76'
    )
    when 'dino' then jsonb_build_object(
        'format', 'rcheevos',
        'lboard', 'STA:0xHB345=1::CAN:0=1::SUB:0xHB345=0::VAL:A:b0x B374*10000_M:b0x B376'
    )
    when 'ffight' then jsonb_build_object(
        'format', 'rcheevos',
        'lboard', 'STA:0xH8568=1::CAN:0=1::SUB:0xH8568=0::VAL:A:b0x 85EC*10000_M:b0x 85EE'
    )
    when 'knights' then jsonb_build_object(
        'format', 'rcheevos',
        'lboard', 'STA:0xHAC54=1::CAN:0=1::SUB:0xHAC54=0::VAL:A:b0x AC8C*10000_M:b0x AC8E'
    )
    when 'kod' then jsonb_build_object(
        'format', 'rcheevos',
        'lboard', 'STA:0xH125A=1::CAN:0=1::SUB:0xH125A=0::VAL:A:b0x 12C2*10000_M:b0x 12C4'
    )
    when 'punisher' then jsonb_build_object(
        'format', 'rcheevos',
        'lboard', 'STA:0xH5AE1=1::CAN:0=1::SUB:0xH5AE1=0::VAL:A:b0x 5BAA*10000_M:b0x 5BAC'
    )
    when 'ddtod' then jsonb_build_object(
        'format', 'rcheevos',
        'lboard', 'STA:0xH86C8=1::CAN:0=1::SUB:0xH86C8=0::VAL:A:b0x 8748*10000_M:b0x 874A'
    )
    else score_config
end,
max_valid_score = case slug
    when 'captcomm' then 9999999
    else max_valid_score
end
where slug in ('captcomm', 'dino', 'ffight', 'knights', 'kod', 'punisher', 'ddtod');
