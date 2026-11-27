---
name: reset-arcade-stats
description: Reinicia a cero todas las estadísticas de juegos y jugadores en Supabase (descargas, tiempo jugado, replays, récords e historial) preservando el catálogo de juegos y las cuentas de usuario.
---

# Skill: Reinicio de Estadísticas y Replays (`reset-arcade-stats`)

Usa este skill cuando el usuario solicite limpiar, borrar o reiniciar desde cero las estadísticas de juegos y jugadores (descargas, tiempo jugado, replays, récords, historial de partidas, salas o cola de matchmaking) en Supabase.

## Requisitos
- `local.properties` en la raíz del proyecto con `SUPABASE_PROJECT_ID`, `SUPABASE_ACCESS_TOKEN`, `SUPABASE_SERVICE_ROLE_KEY` y `SUPABASE_URL` (opcional, se infiere del `project_id`).
- Ejecutar los comandos con `BypassSandbox: true` (requiere acceso de red a Supabase API).

## Procedimiento

1. *(Opcional)* Para inspeccionar los conteos actuales sin modificar datos:
   ```bash
   python3 scripts/reset-arcade-stats.py --dry-run
   ```

2. Ejecutar el reinicio completo de forma no interactiva:
   ```bash
   python3 scripts/reset-arcade-stats.py --yes
   ```

3. Verificar en la salida del script que:
   - `games (total)` y `profiles (total)` se mantienen intactos.
   - `games (non-zero stats)`, `profiles (non-zero stats)`, `high_scores`, `match_history`, `user_game_stats`, `matchmaking_queue`, `rooms`, `room_messages`, `room_session_snapshots` y `storage.objects (replays)` quedan en `0`.
   - El código de salida es `0`.

4. Reportar al usuario el resumen de conteos antes y después de la ejecución.
