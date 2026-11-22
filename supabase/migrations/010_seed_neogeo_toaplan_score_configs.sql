-- Fase 6: Configuraciones rcheevos verificadas contra RETRO_MEMORY_SYSTEM_RAM para NEOGEO (mslug) y Toaplan (snowbros)

update public.games
set score_config = case slug
    when 'mslug' then jsonb_build_object(
        'format', 'rcheevos',
        'lboard', 'STA:0xH8155=1::CAN:0=1::SUB:0xH8155=0::VAL:A:b0x 81B6*10000_M:b0x 81B8'
    )
    when 'snowbros' then jsonb_build_object(
        'format', 'rcheevos',
        'lboard', 'STA:0xH0036=1::CAN:0=1::SUB:0xH0036=0::VAL:A:0x 03FC*65536_M:0x 03FE'
    )
    else score_config
end
where slug in ('mslug', 'snowbros');
