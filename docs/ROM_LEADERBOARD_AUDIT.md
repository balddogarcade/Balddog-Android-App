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
| 1 | `altbeast` | Altered Beast | Sega System 16B | Sí | OK (con advertencia) | Puntaje pantalla `500` = memoria `500`, pero `STA:0xH0820=1` tuvo `flickers=10` durante boot/attract. |
| 2 | `btoads` | Battletoads Arcade | Arcade | No | SIN_SCORE_CONFIG | - |
| 3 | `captcomm` | Captain Commando | CPS1 | Sí | OK | `active=true`, `flickers=0`, `score=0` (sin enemigos alcanzados en ventana corta). |
| 4 | `dbz2` | Dragon Ball Z 2: Super Battle | Banpresto | Sí | OK | `active=true`, `flickers=0`, `score=0` (sin daño en ventana corta). |
| 5 | `ddragon2` | Double Dragon II: The Revenge | Technos Japan | Sí | ADVERTENCIA | `active=false` al final, `flickers=8` detectados durante boot/intro. |
| 6 | `ddtod` | Dungeons & Dragons: Tower of Doom | CPS2 | Sí | OK | `active=true`, `flickers=0`, `score=0` (sin enemigos alcanzados en ventana corta). |
| 7 | `dino` | Cadillacs and Dinosaurs | CPS1 | Sí | OK | `active=true`, `flickers=0`, `score=0` (sin enemigos alcanzados en ventana corta). |
| 8 | `ffight` | Final Fight | CPS1 | Sí | OK | `active=true`, `flickers=0`, `score=0` (sin enemigos alcanzados en ventana corta). |
| 9 | `ghouls` | Ghouls 'n Ghosts | CPS1 | Sí | OK | `active=false` (en intro/mapa inicial), `flickers=0`. |
| 10 | `knights` | Knights of the Round | CPS1 | Sí | OK | `active=true`, `flickers=0`, `score=0` (sin enemigos alcanzados en ventana corta). |
| 11 | `kod` | The King of Dragons | CPS1 | Sí | OK | `active=true`, `flickers=0`, `score=0` (sin enemigos alcanzados en ventana corta). |
| 12 | `mslug` | Metal Slug: Super Vehicle-001 | NEOGEO | Sí | OK | Puntaje pantalla `500` = memoria `500`, `flickers=0`. |
| 13 | `mslug3` | Metal Slug 3 | NEOGEO | Sí | DISCREPANCIA | Puntaje pantalla `100` vs memoria `200` (factor 2x o dirección incorrecta). |
| 14 | `nbajam` | NBA Jam | Midway | No | SIN_SCORE_CONFIG | - |
| 15 | `nbbatman` | Ninja Baseball Bat Man | Irem | Sí | OK | `active=false` (en intro/selección), `flickers=0`. |
| 16 | `pbobble` | Puzzle Bobble / Bust-A-Move | NEOGEO | No | SIN_SCORE_CONFIG | - |
| 17 | `powerins` | Power Instinct | Atlus | Sí | OK | Puntaje pantalla `300` = memoria `300`, `flickers=0`. |
| 18 | `punisher` | The Punisher | CPS1 | Sí | OK | Puntaje pantalla `4200` = memoria `4200`, `flickers=0`. |
| 19 | `robocop` | RoboCop | Data East | Sí | OK | Puntaje pantalla `1000` = memoria `1000`, `flickers=0`. |
| 20 | `simpsons` | The Simpsons | Konami | Sí | OK | Puntaje pantalla `1` = memoria `1`, `flickers=0`. |
| 21 | `snowbro2` | Snow Bros. 2: With New Elves | Toaplan | No | SIN_SCORE_CONFIG | - |
| 22 | `snowbros` | Snow Bros.: Nick & Tom | Toaplan | Sí | DISCREPANCIA | Puntaje pantalla `70` vs memoria `15728778` (`flickers=1`). |
| 23 | `spidman` | Spider-Man: The Videogame | Sega System 32 | No | SIN_SCORE_CONFIG | - |
| 24 | `ssriders` | Sunset Riders | Konami | Sí | OK | Puntaje pantalla `100` = memoria `100`, `flickers=0`. |
| 25 | `tmnt` | Teenage Mutant Ninja Turtles | Konami | No | SIN_SCORE_CONFIG | - |
| 26 | `tmnt2` | TMNT: Turtles in Time | Konami | No | SIN_SCORE_CONFIG | - |
| 27 | `tumblep` | Tumble Pop | Data East | Sí | OK | Puntaje pantalla `100` = memoria `100`, `flickers=0`. |
| 28 | `uccops` | Undercover Cops | Irem | Sí | DISCREPANCIA | Reporta `max_score=85848382` y `flickers=1` durante intro sin HUD. |
| 29 | `viostorm` | Violent Storm | Konami | Sí | OK | `active=false` (en intro), `flickers=0`. |
| 30 | `wwfwfestu` | WWF WrestleFest | Technos Japan | No | SIN_SCORE_CONFIG | - |

## Hallazgos Principales
- **Juegos verificados OK (puntaje pantalla = memoria `rcheevos`, `flickers=0`):**
  - `robocop` (`1000` = `1000`)
  - `simpsons` (`1` = `1`)
  - `mslug` (`500` = `500`)
  - `powerins` (`300` = `300`)
  - `punisher` (`4200` = `4200`)
  - `ssriders` (`100` = `100`)
  - `tumblep` (`100` = `100`)
- **Juegos con `active=true` estable (`flickers=0`) pero `score=0` en ventana corta:**
  - `captcomm`, `dino`, `dbz2`, `ddtod`, `ffight`, `knights`, `kod`, `ghouls`, `nbbatman`, `viostorm`.
- **Discrepancias detectadas (requieren ajuste de expresión `rcheevos`):**
  - `mslug3`: pantalla `100` vs memoria `200` (dirección desplazada; candidato `bcd4_be @ 0xED16`).
  - `snowbros`: pantalla `70` vs memoria `15728778` (`0x03FC` incorrecta; candidato `bcd4_be @ 0x1403`, `score=80` verificado).
  - `uccops`: `max_score=85848382` y `flickers=1` durante intro sin HUD.
- **Advertencias de estabilidad (`flickers > 0`):**
  - `altbeast`: puntaje `500` coincide, pero `flickers=10` durante boot/attract.
  - `ddragon2`: `flickers=8` durante boot/intro.
- **Juegos sin `score_config` en Supabase (8):**
  - `btoads`, `nbajam`, `pbobble`, `snowbro2`, `spidman`, `tmnt`, `tmnt2`, `wwfwfestu`.
