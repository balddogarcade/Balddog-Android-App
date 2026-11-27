# Firebase Performance Monitoring

**bald.dog ARCADE** integra **Firebase Performance Monitoring** (`com.google.firebase:firebase-perf` vía `firebase-bom:35.2.0`) para detectar cuellos de botella en el arranque de la aplicación, renderizado de pantallas y trazas personalizadas críticas del emulador y Netplay P2P.

> **Nota de Toolchain (AGP 9.5.3):** Se utiliza el SDK `firebase-perf` directamente sin el plugin de Gradle `com.google.firebase.firebase-perf`, ya que dicho plugin aún referencia la API legacy `Transform` eliminada en AGP 9.

## Wrapper Seguro (`PerformanceHelper`)

Toda la instrumentación de trazas personalizadas pasa por [`PerformanceHelper.kt`](../app/src/main/java/dog/bald/arcade/diagnostics/PerformanceHelper.kt):
- Encapsula `FirebasePerformance.getInstance().newTrace(...)` con `runCatching`.
- Provee `startTrace(name)`, `stopTrace(trace, attributes)` y `measureTrace(name, attributes) { ... }`.

## Trazas Personalizadas

| Traza | Propósito | Atributos |
| :--- | :--- | :--- |
| `rom_startup_trace` | Medir el tiempo de inicialización del juego y motor Libretro/FBNeo. | `rom_slug`, `mode` |
| `netplay_sync_trace` | Medir el tiempo de sincronización P2P/GGRS hasta alcanzar `SessionState::Running`. | `rom_slug`, `mode` |
| `matchmaking_enqueue_rpc` | Medir la latencia de la RPC `find_or_enqueue_match`. | `rom_slug`, `result_status`, `source` |
| `matchmaking_wait_to_match` | Medir el tiempo total en cola hasta recibir el `room_id` por Realtime/poll. | `rom_slug`, `via` |
| `matchmaking_stats_fetch` | Medir la latencia de `get_matchmaking_stats()`. | — |
| `deeplink_room_join` | Medir la resolución y validación de ingreso vía Link Mágico / QR. | `outcome` |
| `ipfs_rom_download_trace` | Medir descarga y verificación SHA-256/CRC32 de ROMs desde IPFS. | `rom_file`, `outcome` (+ métricas `bytes_downloaded`, `gateway_attempts`) |
| `exit_sync_trace` | Medir la espera de sincronización de scores/replays al salir de partida. | `outcome` (+ métrica `jobs_count`) |
| `catalog_fetch_trace` | Medir la carga inicial del catálogo de juegos desde Supabase. | — |
| `room_create_trace` | Medir la creación de sala multijugador en Supabase. | `mode`, `outcome` |
| `replay_upload_trace` | Medir la subida de archivos `.bsv.gz` a Supabase Storage. | `game_id`, `outcome` (+ métrica `bytes_uploaded`) |
| `replay_video_export_trace` | Medir la exportación offline de replay a MP4 con `MediaCodec`. | `game_title`, `outcome` (+ métrica `frames_exported`) |

## Verificación en Logcat

Para inspeccionar trazas localmente antes de que aparezcan en **Firebase Console → Performance**:
```bash
adb shell setprop log.tag.FirebasePerformance DEBUG
adb logcat -s FirebasePerformance
```
