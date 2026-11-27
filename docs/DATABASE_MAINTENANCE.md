# Mantenimiento de Base de Datos: Reinicio de Estadísticas y Replays

Este documento describe cómo reiniciar a cero todas las estadísticas de actividad de juegos y jugadores en Supabase sin afectar el catálogo de juegos ni las cuentas de usuario registradas.

## Qué se elimina y reinicia

1. **Supabase Storage (`replays`):**
   - Se vacía el bucket `replays` (`POST /storage/v1/bucket/replays/empty`).
2. **Tablas truncadas (`RESTART IDENTITY CASCADE`):**
   - `public.high_scores` (récords / leaderboards 1CC)
   - `public.match_history` (historial de partidas)
   - `public.user_game_stats` (estadísticas por jugador y juego)
   - `public.matchmaking_queue` (cola de emparejamiento)
   - `public.rooms`, `public.room_messages`, `public.room_session_snapshots` (salas y sesiones activas)
3. **Contadores reiniciados a `0`:**
   - `public.games`: `download_count = 0`, `total_play_seconds = 0`
   - `public.profiles`: `total_play_seconds = 0`, `total_plays = 0`

## Qué se preserva

- Catálogo de juegos en `public.games` (metadatos, covers, `score_config`).
- BIOS en `public.game_bios`.
- Cuentas de usuario en `auth.users` y `public.profiles`.
- Relaciones sociales (`public.friendships`, `public.user_follows`).

## Uso del script automático

El script [reset-arcade-stats.py](../scripts/reset-arcade-stats.py) lee las credenciales desde `local.properties` (o variables de entorno) y ejecuta todo el proceso con verificación automática.

```bash
# Ver conteos actuales sin hacer cambios
python3 scripts/reset-arcade-stats.py --dry-run

# Ejecutar reinicio con confirmación interactiva
python3 scripts/reset-arcade-stats.py

# Ejecutar reinicio de forma no interactiva (CI / agentes)
python3 scripts/reset-arcade-stats.py --yes
```
