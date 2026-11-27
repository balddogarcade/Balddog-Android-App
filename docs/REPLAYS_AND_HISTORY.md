# Arquitectura de Replays Deterministas e Historial de Partidas

Este documento describe en detalle el diseño, las reglas de negocio, el esquema de base de datos, el motor nativo en Rust y la integración Android para el sistema de **Replays Deterministas (`.bsv.gz`)**, exportación multimedia local (**PNG / MP4**) y el **Historial de Partidas** de **bald.dog ARCADE**.

---

## 1. Sistema de Replays Deterministas (`.bsv.gz`)

### 1.1 Principio de funcionamiento
En lugar de grabar y almacenar archivos de video pesados en la nube, **bald.dog ARCADE** registra exclusivamente los **inputs del jugador frame a frame** en el formato estándar **Libretro BSV1**, comprimidos con **gzip** (`flate2`):

- **Cabecera BSV1 (16 bytes):** Magic `"BSV1"`, `rom_crc32` (4 bytes), `state_size` (4 bytes) y campo reservado (4 bytes, 0 para cold boot).
- **Datos por frame (8 bytes crudos a 60 fps):** `[u16; 4]` representando los 4 puertos de entrada (`RetroJoypad`).
- **Compresión gzip:** Debido a la alta redundancia de inputs consecutivos en juegos arcade, la compresión gzip reduce el tamaño entre **10x y 20x**. Una partida completa de 15–20 minutos pesa en promedio **~25 KB** (vs. 150–300 MB en MP4).

### 1.2 Reglas de elegibilidad y reemplazo automático
1. **Solo partidas en solitario de 1 ficha (1CC):**
   - La grabación y envío de replays de ranking solo aplica cuando `!isNetplay && !isReplayMode` y existe `score_config->>'lboard'` para el juego.
   - El jugador debe mantener `rankingEligible = true` (sin cargar *Save States*) y `creditsUsed == 1` (sin usar *Continue* / segunda moneda).
2. **Reemplazo exclusivo por récord máximo:**
   - Cada usuario conserva su replay de récord en Supabase Storage bajo la ruta:
     `<game_id>/<user_id>/<uuid>.bsv.gz` (dentro del bucket `replays`).
   - La verificación headless (`verify_replay` / `ReplayPlayer::from_gzip_file_strict`) aplica un límite de descompresión de 16 MiB y rechaza replays con `state_size > 0` o `rom_crc32 = 0`.
   - Antes de subir un replay (en `GameActivity.submitScoreWithReplay`), el cliente consulta el mejor puntaje actual del jugador (`fetchMyGameRank(gameId)`). **Solo si `score >= currentBest`** se sube y reemplaza el archivo en Storage, evitando que una partida posterior con menor puntaje sobrescriba el replay del récord máximo.
   - En PostgreSQL, `public.submit_high_score` aplica `on conflict (game_id, user_id) do update ... where excluded.score > public.high_scores.score ...`, actualizando `replay_path`, `replay_size_bytes` y `replay_sha256` únicamente cuando corresponde.

### 1.3 Determinismo en el motor Rust
- **Ubicación:**
  - `rust/balddog-core/src/retro/replay.rs` (`ReplayRecorder`, `ReplayPlayer`)
  - `rust/balddog-core/src/session.rs` (integración en el loop de emulación)
  - `rust/balddog-android/src/lib.rs` (puente UniFFI y `OfflineReplayRenderer`)
- **Aislamiento de NVRAM / EEPROM:** Al iniciar una sesión con ranking o reproducción de replay, se limpia/aísla el estado de guardado en disco para garantizar que la máquina arcade arranque exactamente desde el mismo estado inicial limpio (*cold boot*), asegurando que la reproducción frame a frame alcance exactamente el mismo puntaje y secuencia.
- **Funciones UniFFI expuestas:**
  - `startReplayGame(nativeLibDir, romPath, systemDir, saveDir, replayPath, scoreDef)`
  - `setReplaySpeed(speedMultiplier)` (`1x`, `2x`, `4x`)
  - `restartReplay()`
  - `getReplayHudInfo() -> ReplayHudInfo` (`is_replay`, `current_frame`, `total_frames`, `speed`, `finished`, `paused`)
  - `exportRecordedReplay(destPath)`
  - `captureScreenshotPng(destPath)`
  - `OfflineReplayRenderer` (renderizado headless sin superficie gráfica para exportación de video MP4).

### 1.4 Reproducción en Android y Exportación Multimedia Local
- **Puntos de entrada para ver un replay:**
  1. **Ranking Global del Juego (`GameLeaderboardSheet`):** Botón `▶ REPLAY` en cualquier fila con `replay_path`.
  2. **Perfil del Usuario (`ProfileScreen` -> pestaña `REPLAYS`):** Lista dedicada con todos los replays de puntajes máximos del usuario y botón `VER`.
- **Controles durante la reproducción (`ReplayHudOverlay`):**
  - Selector de velocidad: `1x`, `2x`, `4x`.
  - Barra de progreso por frames / tiempo estimado.
  - Botón **Reiniciar** (`restartReplay`).
  - **Captura de pantalla PNG (`MediaExportHelper.savePngToGallery`):** Exporta el frame actual a `Pictures/balddog ARCADE` vía `MediaStore`.
  - **Exportar Clip MP4 (`ReplayVideoExporter.exportToGallery`):** Ejecuta `OfflineReplayRenderer` en segundo plano, codificando video H.264 con `android.media.MediaCodec` y empaquetándolo con `android.media.MediaMuxer` hacia `Movies/balddog ARCADE` sin consumir almacenamiento ni ancho de banda en la nube.

---

## 2. Historial de Partidas (`public.match_history`)

### 2.1 Alcance y Regla de Privacidad
El historial de partidas registra sesiones reales jugadas por el usuario en tres modos:
- **`local` (SOLO):** Partida en solitario.
- **`lan` (LAN):** Partida multijugador en red local.
- **`online` (ONLINE):** Partida multijugador por Internet en **salas públicas**.

> [!IMPORTANT]
> **Exclusión estricta de salas privadas:**
> Las partidas jugadas en salas marcadas con `is_private = true` **NO se registran ni se muestran** en el historial de partidas. Esta regla se hace cumplir en tres capas:
> 1. **Cliente (`GameActivity.recordMatchHistoryOnExit`):** Omite el registro si `isNetplay && roomIsPrivate`.
> 2. **RPC (`public.record_match_history`):** Ignora silenciosamente la inserción si `p_is_private = true` o si la sala referenciada por `p_room_id` tiene `rooms.is_private = true`.
> 3. **RLS (`match_history_select_auth`):** Solo permite `select` cuando `is_private = false` y `auth.uid() = any(participant_ids)`.

### 2.2 Idempotencia y Duración Mínima
- **Idempotencia (`client_session_id`):**
  - En partidas **Solo (`local`)**, `GameActivity` genera un `UUID` único por sesión.
  - En partidas **Multijugador (`lan` / `online`)**, se utiliza `room.id` como `client_session_id`. De este modo, aunque todos los jugadores participantes envíen el cierre de partida al salir de `GameActivity`, `on conflict (client_session_id)` consolida **un único registro** compartido para todos los `participant_ids`, conservando el mayor `duration_seconds` y el `ended_at` más reciente.
- **Filtro de sesiones accidentales:** Solo se registran partidas con al menos **5 segundos** de tiempo activo acumulado (`accumulatedActiveMs >= 5000`). Las sesiones en modo Replay (`isReplayMode`) nunca se registran como partidas jugadas.

---

## 3. Esquema de Base de Datos y RPCs (Supabase)

### Migración `015_replays_storage_and_schema.sql`
- Bucket `replays` (público en lectura, límite `5242880` bytes / 5 MB, MIME `application/gzip`, `application/x-gzip`, `application/octet-stream`).
- Políticas RLS en `storage.objects`: lectura pública (`anon`, `authenticated`); escritura/actualización/borrado restringido a `name like '%/' || auth.uid()::text || '.bsv.gz'`.
- Columnas en `public.high_scores`: `replay_path`, `replay_size_bytes`, `replay_sha256`.
- Actualización de `submit_high_score(...)` y `get_game_leaderboard(...)` para almacenar y devolver `replay_path`.

### Migración `016_match_history_and_profile_replays.sql`
- **`public.get_my_high_scores()`:** Actualizado para devolver también `replay_path` y `replay_size_bytes`.
- **Columnas añadidas a `public.match_history`:**
  - `client_session_id uuid` (índice único parcial `idx_match_history_client_session`).
  - `room_id uuid references public.rooms(id) on delete set null`.
  - `is_private boolean not null default false`.
  - `participant_ids uuid[] not null default '{}'` (índice GIN `idx_match_history_participants`).
  - `duration_seconds integer not null default 0`.
  - `score bigint`.
- **`public.record_match_history(...)`:** RPC `security definer` idempotente que valida autenticación, modo (`local`, `lan`, `online`), membresía en `participant_ids` y exclusión de salas privadas.
- **`public.get_my_match_history(p_limit, p_offset)`:** Devuelve el historial paginado del usuario autenticado ordenado por `coalesce(ended_at, started_at) desc`.

---

## 4. Pantallas y Componentes en Android

| Archivo | Responsabilidad |
| :--- | :--- |
| `app/src/main/java/dog/bald/arcade/data/Models.kt` | DTOs `MyHighScoreDto` (con `replayPath`, `replaySizeBytes`), `MatchHistoryPlayerDto`, `MatchHistoryDto`. |
| `app/src/main/java/dog/bald/arcade/data/ArcadeRepository.kt` | Métodos `uploadReplay`, `downloadReplay`, `recordMatchHistory`, `fetchMyMatchHistory`. |
| `app/src/main/java/dog/bald/arcade/MainActivity.kt` | Envío de extras de replay (`EXTRA_REPLAY_*`) y metadatos de sala (`EXTRA_NETPLAY_MODE`, `EXTRA_ROOM_IS_PRIVATE`, `EXTRA_ROOM_PLAYERS_JSON`) hacia `GameActivity`. |
| `app/src/main/java/dog/bald/arcade/GameActivity.kt` | Ciclo de vida de grabación/reproducción, envío de récord + replay, registro de `recordMatchHistoryOnExit()`. |
| `app/src/main/java/dog/bald/arcade/ui/components/ProfileComponents.kt` | Filas `ProfileRecordRow`, `ProfileReplayRow`, `ProfileMatchHistoryRow`, `ProfileActivityRow`. |
| `app/src/main/java/dog/bald/arcade/ui/screens/ProfileScreen.kt` | Pestañas del perfil: **RÉCORDS**, **REPLAYS**, **HISTORIAL** y **TIEMPO**. |
| `app/src/main/java/dog/bald/arcade/ui/screens/MainShellScreen.kt` | Carga de `myHighScores` y `myMatchHistory`, y lanzamiento de replays desde el Perfil y desde el Leaderboard. |
