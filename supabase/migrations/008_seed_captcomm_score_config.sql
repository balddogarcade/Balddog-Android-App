-- Fase 4 (Piloto): Configuración rcheevos verificada contra RETRO_MEMORY_SYSTEM_RAM de FBNeo para Captain Commando (captcomm)
-- CpsRamFF (64KB, 16-bit word-swapped):
--   0xA855: P1 active (1 = en partida / continue, 0 = game over / attract)
--   0xAA7D: P1 lives (>0 al iniciar)
--   0xAA74..0xAA77: P1 score en BCD (dos palabras de 16 bits LE)

update public.games
set score_config = jsonb_build_object(
        'format', 'rcheevos',
        'lboard', 'STA:0xHA855=1_0xHAA7D>0::CAN:0=1::SUB:0xHA855=0::VAL:A:b0x AA74*10000_M:b0x AA76'
    ),
    max_valid_score = 9999999
where slug = 'captcomm';
