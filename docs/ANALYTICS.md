# Google Analytics for Firebase (GA4)

**bald.dog ARCADE** integra **Google Analytics for Firebase** (`com.google.firebase:firebase-analytics` vía `firebase-bom:35.2.0`) para medir adopción de juegos, sesiones de emulación, salas Netplay P2P y envíos de puntajes 1CC sin recopilar información personal identificable (PII).

## Arquitectura y Wrapper Seguro

Toda la instrumentación pasa por [`AnalyticsHelper.kt`](../app/src/main/java/dog/bald/arcade/diagnostics/AnalyticsHelper.kt):
- Se inicializa una sola vez en `BalddogApplication.onCreate()` mediante `AnalyticsHelper.init(this)`.
- Encapsula las llamadas a `FirebaseAnalytics` con `runCatching` para evitar fallos en entornos sin `google-services.json`.
- **Cero PII:** Nunca se envían correos electrónicos, nombres reales ni direcciones IP. `setUserId()` acepta únicamente el UUID anonimizado de Supabase.

## Eventos Instrumentados

| Evento | Parámetros | Origen |
| :--- | :--- | :--- |
| `game_launch` | `game_id`, `rom_slug`, `mode` (`offline`, `host`, `client`, `replay`) | `GameActivity.onCreate()` |
| `game_session_end` | `rom_slug`, `duration_sec`, `mode` | `GameActivity.onDestroy()` |
| `login` / `sign_up` | `method` | `AnalyticsHelper.logAuthEvent()` |
| `netplay_room_created` | `rom_slug` | `AnalyticsHelper.logNetplayRoomCreated()` |
| `netplay_room_joined` | `rom_slug` | `AnalyticsHelper.logNetplayRoomJoined()` |
| `leaderboard_score_submit` | `rom_slug`, `score`, `credits_used` | `AnalyticsHelper.logLeaderboardSubmit()` |
| `matchmaking_started` | `rom_slug` | `AnalyticsHelper.logMatchmakingStarted()` |
| `matchmaking_matched` | `rom_slug`, `source` (`backfill`, `queue`), `wait_time_sec` | `AnalyticsHelper.logMatchmakingMatched()` |
| `matchmaking_cancelled` | `rom_slug`, `wait_time_sec`, `reason` | `AnalyticsHelper.logMatchmakingCancelled()` |
| `matchmaking_failed` | `rom_slug`, `error_type` | `AnalyticsHelper.logMatchmakingFailed()` |
| `room_player_banned` | `rom_slug` | `AnalyticsHelper.logRoomPlayerBanned()` |
| `room_deeplink_opened` | `room_id_present`, `outcome` | `AnalyticsHelper.logRoomDeepLinkOpened()` |

## Verificación en Tiempo Real (DebugView)

1. Conectar el dispositivo por USB y habilitar el modo debug de Analytics:
   ```bash
   adb shell setprop debug.firebase.analytics.app dog.bald.arcade
   ```
2. Abrir la aplicación, lanzar un juego y salir.
3. Inspeccionar los eventos en **Firebase Console → Analytics → DebugView**.
4. Desactivar el modo debug al finalizar:
   ```bash
   adb shell setprop debug.firebase.analytics.app .none.
   ```

## Analítica Web Pública (Cloudflare Web Analytics)

La web pública (`https://bald.dog`, alojada en Cloudflare Pages en el repositorio `Balddog-Main-Webpage`) complementa a Firebase Analytics (GA4) de la app Android utilizando **Cloudflare Web Analytics**:

- **Sin cookies y respetuosa con la privacidad:** No almacena identificadores personales ni requiere banner de consentimiento de cookies.
- **Inyección automática (`auto_install: True`):** Cloudflare inyecta el beacon (`static.cloudflareinsights.com/beacon.min.js`) automáticamente en las respuestas HTML servidas bajo la zona `bald.dog`, por lo que no se debe duplicar el snippet manualmente en el código fuente.
