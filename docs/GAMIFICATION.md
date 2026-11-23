# Sistema de Rankings, Gamificación y Perfiles Públicos (`bald.dog ARCADE`)

Este documento define las reglas de negocio, fórmulas matemáticas, umbrales de progresión, criterios de privacidad y contratos de datos para la capa social y competitiva de **bald.dog ARCADE**.

---

## 1. Elegibilidad y Reglas de Puntaje (1CC Hardcore)

- Todo puntaje competitivo en `public.high_scores` exige:
  - `is_hardcore = true`
  - `credits_used = 1` (**1CC — One Credit Clear / 1 Crédito**)
  - Hash `rom_sha256` coincidente con `public.games.sha256`
  - `score > 0` y `score <= max_valid_score` (cuando está definido en `public.games`)
- Solo los usuarios **registrados** participan en la plataforma. No existen sesiones anónimas ni cuentas de invitado.

---

## 2. Puntos Globales de Jugador

Cada juego del catálogo que posea leaderboard activo (`score_config.lboard` no vacío) otorga **Puntos Globales** según la posición del jugador en el ranking 1CC de ese juego:

| Posición en el juego | Puntos Globales |
| :--- | :--- |
| `#1` (Oro) | **100 pts** |
| `#2` (Plata) | **75 pts** |
| `#3` (Bronce) | **50 pts** |
| `#4` – `#10` (Top 10) | **25 pts** |
| `#11+` (1CC validado) | **10 pts** |

- **Suma sin límite:** Todos los juegos con leaderboard activo suman al total del jugador (`global_points`).
- **Visibilidad en Ranking Global:** Solo aparecen jugadores con `global_points > 0` (al menos 1 récord 1CC validado).

---

## 3. Reglas de Desempate

### Ranking Global de Jugadores
1. `global_points DESC` (mayor puntaje global acumulado).
2. `total_play_seconds ASC` (**menor** tiempo total acumulado en la plataforma — premia la eficiencia).
3. `created_at ASC` (cuenta más antigua).

### Ranking 1CC por Juego
1. `score DESC` (mayor puntaje 1CC).
2. `user_game_stats.play_seconds ASC` (**menor** tiempo acumulado en ese juego específico).
3. `achieved_at ASC` (quien logró el récord primero).

### Ranking de Tiempo Jugado por Juego (`playtime`)
1. `play_seconds DESC` (mayor tiempo acumulado en ese juego).
2. `play_count DESC`, luego `last_played_at DESC`.

---

## 4. Rangos Arcade (Progresión por Puntos Globales)

| Rango | Umbral mínimo (`global_points`) |
| :--- | :--- |
| **Novato** | `0` pts |
| **Retador** | `250` pts |
| **Luchador** | `600` pts |
| **Maestro** | `1,200` pts |
| **Gran Maestro** | `2,500` pts |
| **Leyenda** | `5,000` pts |

---

## 5. Medallas (Badges)

Las medallas se calculan a partir de datos verificados en Supabase y se exhiben en el Perfil (propio y público):

| Medalla | Criterio de desbloqueo | Niveles / Notas |
| :--- | :--- | :--- |
| **Rey del Arcade** | Ser `#1` en al menos 1 juego | Único (muestra cantidad de `#1`s) |
| **Podio** | Estar en Top 3 (`#1`–`#3`) en al menos 1 juego | Único (muestra cantidad de podios) |
| **1CC Master** | Tener al menos 1 récord 1CC validado | Único (muestra total de juegos 1CC) |
| **Replay Verificado** | Tener al menos 1 replay verificado (`replay_status = 'verified'`) | Único |
| **Veterano** | Tiempo total acumulado (`total_play_seconds`) | **Bronce:** 25h · **Plata:** 100h · **Oro:** 250h |
| **Anfitrión Netplay** | Partidas multijugador completadas en sala | **Bronce:** 25 · **Plata:** 100 · **Oro:** 250 |

---

## 6. Perfiles Públicos, Privacidad y Redes Sociales

- **Campos públicos por defecto:** `username`, `avatar_seed` (DiceBear pixel-art generado únicamente al azar), `country_code`, `bio` (máx. 140 caracteres), rango arcade, `global_points`, medallas, contadores de `Seguidores` / `Siguiendo` y récords 1CC por juego.
- **Toggle único de Privacidad (`is_private = true`):**
  - **Oculta** dentro del perfil público del usuario: redes sociales (`social_links`), tiempo total jugado (`total_play_seconds`), tiempo por juego e historial reciente de partidas.
  - **Mantiene visibles:** récords 1CC públicos, rango arcade, medallas, contadores de seguidores/siguiendo y aparición en el ranking de tiempo por juego.
- **Validación de perfil y redes:**
  - `username`: 3 a 20 caracteres `[a-zA-Z0-9_]`, único insensible a mayúsculas (`lower(username)`).
  - `x` (Twitter/X), `instagram`, `youtube`, `twitch`, `github`: URL completa `https://...` cuyo host corresponda a la plataforma.
  - `website`: cualquier URL válida `https://...`.
  - `discord`: acepta URL `https://...` (se abre en navegador) o usuario/tag (se copia al portapapeles al tocar).

---

## 7. Sistema de Seguidores (`user_follows`)

- Relación asimétrica tipo Twitter (`follower_id -> following_id`), sin auto-seguimiento (`follower_id <> following_id`).
- **Integraciones clave:**
  - Contadores clicables `SEGUIDORES` y `SIGUIENDO` en perfil propio y público.
  - Filtro `TODOS | SIGUIENDO` en el Ranking Global y en el Ranking Individual de cada juego.
  - Etiqueta `[SIGUIENDO]` y filtro `"Solo salas de seguidos"` en la pestaña Salas, más botón `SEGUIR` al inspeccionar jugadores dentro de un lobby.
