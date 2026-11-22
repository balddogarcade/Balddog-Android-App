-- Fase 6: Límites de cordura (max_valid_score) para el catálogo curado
-- Las definiciones `score_config` (formato {"format": "rcheevos", "lboard": "STA:...::CAN:...::SUB:...::VAL:..."})
-- se configuran por ROM una vez verificados sus offsets exactos en RETRO_MEMORY_SYSTEM_RAM de FBNeo.

update public.games
set max_valid_score = case slug
    when 'pbobble' then 99999999
    when 'mslug' then 9999999
    when 'mslug3' then 99999999
    when 'dino' then 9999999
    when 'ffight' then 9999999
    when 'captcomm' then 9999999
    when 'knights' then 9999999
    when 'kod' then 9999999
    when 'punisher' then 9999999
    when 'ghouls' then 9999999
    when 'ddtod' then 99999999
    when 'tmnt' then 9999
    when 'tmnt2' then 9999
    when 'ssriders' then 9999
    when 'simpsons' then 9999
    when 'viostorm' then 9999999
    when 'snowbros' then 9999999
    when 'snowbro2' then 9999999
    when 'tumblep' then 9999999
    when 'robocop' then 9999999
    when 'altbeast' then 9999999
    when 'ddragon2' then 9999999
    when 'btoads' then 9999999
    when 'nbajam' then 999
    when 'nbbatman' then 9999999
    when 'uccops' then 9999999
    when 'spidman' then 9999999
    when 'wwfwfestu' then 9999999
    when 'dbz2' then 9999999
    when 'powerins' then 9999999
    else coalesce(max_valid_score, 99999999)
end
where max_valid_score is null;
