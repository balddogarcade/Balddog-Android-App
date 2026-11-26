# Auditoría de Puntajes y Leaderboards (`rcheevos` vs Pantalla vs Supabase)

Este documento registra la auditoría programática del catálogo completo de **bald.dog ARCADE** (30 ROMs).
Para cada juego se contrasta:
- El puntaje visible en pantalla durante la partida (emulación *headless* con `fbneo_libretro.so`).
- El puntaje calculado por la expresión `rcheevos` configurada en `public.games.score_config`.
- La estabilidad del ciclo de vida 1CC (`STA` / `SUB`).

## Resumen
- **Total de juegos:** 30
- **Con `score_config` en Supabase:** 22
- **Sin `score_config` en Supabase:** 8

## Estado por ROM

| # | Slug | Título | Sistema | `score_config` | Estado | Modificación / Notas |
|---|------|--------|---------|----------------|--------|----------------------|
| 1 | `altbeast` | Altered Beast | Sega System 16B | Sí | PENDIENTE | - |
| 2 | `btoads` | Battletoads Arcade | Arcade | No | SIN_SCORE_CONFIG | - |
| 3 | `captcomm` | Captain Commando | CPS1 | Sí | PENDIENTE | - |
| 4 | `dbz2` | Dragon Ball Z 2: Super Battle | Banpresto | Sí | PENDIENTE | - |
| 5 | `ddragon2` | Double Dragon II: The Revenge | Technos Japan | Sí | PENDIENTE | - |
| 6 | `ddtod` | Dungeons & Dragons: Tower of Doom | CPS2 | Sí | PENDIENTE | - |
| 7 | `dino` | Cadillacs and Dinosaurs | CPS1 | Sí | PENDIENTE | - |
| 8 | `ffight` | Final Fight | CPS1 | Sí | PENDIENTE | - |
| 9 | `ghouls` | Ghouls 'n Ghosts | CPS1 | Sí | PENDIENTE | - |
| 10 | `knights` | Knights of the Round | CPS1 | Sí | PENDIENTE | - |
| 11 | `kod` | The King of Dragons | CPS1 | Sí | PENDIENTE | - |
| 12 | `mslug` | Metal Slug: Super Vehicle-001 | NEOGEO | Sí | PENDIENTE | - |
| 13 | `mslug3` | Metal Slug 3 | NEOGEO | Sí | PENDIENTE | - |
| 14 | `nbajam` | NBA Jam | Midway | No | SIN_SCORE_CONFIG | - |
| 15 | `nbbatman` | Ninja Baseball Bat Man | Irem | Sí | PENDIENTE | - |
| 16 | `pbobble` | Puzzle Bobble / Bust-A-Move | NEOGEO | No | SIN_SCORE_CONFIG | - |
| 17 | `powerins` | Power Instinct | Atlus | Sí | PENDIENTE | - |
| 18 | `punisher` | The Punisher | CPS1 | Sí | PENDIENTE | - |
| 19 | `robocop` | RoboCop | Data East | Sí | PENDIENTE | - |
| 20 | `simpsons` | The Simpsons | Konami | Sí | PENDIENTE | - |
| 21 | `snowbro2` | Snow Bros. 2: With New Elves | Toaplan | No | SIN_SCORE_CONFIG | - |
| 22 | `snowbros` | Snow Bros.: Nick & Tom | Toaplan | Sí | PENDIENTE | - |
| 23 | `spidman` | Spider-Man: The Videogame | Sega System 32 | No | SIN_SCORE_CONFIG | - |
| 24 | `ssriders` | Sunset Riders | Konami | Sí | PENDIENTE | - |
| 25 | `tmnt` | Teenage Mutant Ninja Turtles | Konami | No | SIN_SCORE_CONFIG | - |
| 26 | `tmnt2` | TMNT: Turtles in Time | Konami | No | SIN_SCORE_CONFIG | - |
| 27 | `tumblep` | Tumble Pop | Data East | Sí | PENDIENTE | - |
| 28 | `uccops` | Undercover Cops | Irem | Sí | PENDIENTE | - |
| 29 | `viostorm` | Violent Storm | Konami | Sí | PENDIENTE | - |
| 30 | `wwfwfestu` | WWF WrestleFest | Technos Japan | No | SIN_SCORE_CONFIG | - |
