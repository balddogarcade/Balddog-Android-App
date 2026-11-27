# Google Analytics for Firebase (GA4)

**bald.dog ARCADE** integra **Google Analytics for Firebase** (`com.google.firebase:firebase-analytics` vía `firebase-bom:35.2.0`) en el mismo proyecto Firebase (`balddog`) que la web pública (`Balddog-Main-Webpage`) para medir adopción de juegos, sesiones de emulación, salas Netplay P2P, descargas de ROMs, interacciones sociales y envíos de puntajes 1CC sin recopilar información personal identificable (PII).

## Arquitectura y Wrapper Seguro

Toda la instrumentación pasa por [`AnalyticsHelper.kt`](../app/src/main/java/dog/bald/arcade/diagnostics/AnalyticsHelper.kt):
- Se inicializa una sola vez en `BalddogApplication.onCreate()` mediante `AnalyticsHelper.init(this)`.
- Encapsula las llamadas a `FirebaseAnalytics` con `runCatching` para evitar fallos en entornos sin `google-services.json`.
- **Saneamiento:** Todos los parámetros de texto pasan por `sanitizeParam()` (truncado a 100 caracteres según el límite de GA4, con fallback `"unknown"` si está vacío).
- **Cero PII:** Nunca se envían correos electrónicos, nombres reales ni direcciones IP. `setUserId()` acepta únicamente el UUID anonimizado de Supabase (`MainActivity.kt` lo asigna en `Authenticated` y lo limpia con `null` en `NotAuthenticated`).

## Eventos Instrumentados

| Evento | Parámetros | Origen |
| :--- | :--- | :--- |
| `screen_view` | `screen_name`, `screen_class` | `AuthScreen`, `MainShellScreen` (tabs, overlays, sheets, `RoomLobbyScreen`) |
| `login` / `sign_up` | `method` (`email`, `google`) | `AuthScreen` |
| `password_reset_requested` | *(sin parámetros)* | `AuthScreen` |
| `catalog_search` | `search_term`, `results_count` | `MainShellScreen` (`CatalogTab`, debounce 600ms) |
| `filter_change` | `filter_type`, `value`, `source` | `MainShellScreen` (`CatalogTab`) |
| `rom_download_started` | `rom_slug` | `MainShellScreen.downloadOnly()` |
| `rom_download_completed` | `rom_slug`, `from_cache` | `MainShellScreen.downloadOnly()` |
| `rom_download_failed` | `rom_slug`, `error_type` | `MainShellScreen.downloadOnly()` |
| `game_detail_viewed` | `game_id`, `rom_slug` | `GameDetailScreen` |
| `game_gallery_opened` | `rom_slug`, `image_type` | `GameDetailScreen` |
| `game_trailer_opened` | `rom_slug` | `GameDetailScreen` |
| `game_launch` | `game_id`, `rom_slug`, `mode` (`offline`, `host`, `client`, `replay`) | `GameActivity.onCreate()` |
| `game_session_end` | `rom_slug`, `duration_sec`, `mode` | `GameActivity.onDestroy()` |
| `leaderboard_score_submit` | `rom_slug`, `score`, `credits_used`, `outcome` | `GameActivity.submitOrQueueScore()` |
| `new_record_achieved` | `rom_slug`, `score`, `rank` | `GameActivity.submitOrQueueScore()` |
| `ingame_menu_action` | `action` (`save_state`, `load_state`, `reset_layout`), `rom_slug` | `GameActivity` (`InGameQuickMenu`) |
| `media_export` | `type` (`png`), `rom_slug`, `success` | `GameActivity` (`MediaExportHelper`) |
| `replay_control` | `action` (`pause`, `resume`) | `GameActivity` (`ReplayHudOverlay`) |
| `share` | `content_type` (`replay`, `room`), `item_id`, `method` | `ProfileComponents.shareReplayLink()`, `RoomLobbyScreen` |
| `netplay_room_created` | `rom_slug`, `mode`, `is_private` | `MainShellScreen` (`RoomsTab`) |
| `netplay_room_joined` | `rom_slug`, `mode`, `source` | `MainShellScreen` (`RoomsTab`) |
| `room_lookup` | `found` | `MainShellScreen` (`RoomsTab`) |
| `matchmaking_started` | `rom_slug` | `MainShellScreen` |
| `matchmaking_matched` | `rom_slug`, `source` (`backfill`, `queue`), `wait_time_sec` | `MainShellScreen` |
| `matchmaking_cancelled` | `rom_slug`, `wait_time_sec`, `reason` | `MainShellScreen` |
| `matchmaking_failed` | `rom_slug`, `error_type` | `MainShellScreen` |
| `room_player_banned` | `rom_slug` | `RoomLobbyScreen` |
| `room_deeplink_opened` | `room_id_present`, `outcome` | `MainShellScreen` |
| `follow_toggled` | `following`, `source` (`public_profile`, `follow_list`) | `PublicProfileScreen`, `FollowListScreen` |
| `account_deleted` | *(sin parámetros)* | `MainShellScreen` |
| `app_update` | `stage` (`available`, `download_started`, `ready_to_install`), `version_tag` | `AppUpdateManager` |

## Verificación en Tiempo Real (DebugView)

1. Conectar el dispositivo por USB y habilitar el modo debug de Analytics:
   ```bash
   adb shell setprop debug.firebase.analytics.app dog.bald.arcade.debug
   ```
2. Abrir la aplicación, recorrer los flujos principales y observar los eventos en **Firebase Console → Analytics → DebugView**.
3. Desactivar el modo debug al finalizar:
   ```bash
   adb shell setprop debug.firebase.analytics.app .none.
   ```

## Analítica Web Pública (`Balddog-Main-Webpage`)

La web pública (`https://bald.dog`) comparte el mismo proyecto Firebase (`balddog`) mediante Firebase Analytics Web (`page_view`, `navigation_click`, `apk_download_click`, `modal_interaction`, `replay_control`, `share`, `catalog_search`, `filter_change`, `rooms_refresh`) y se complementa con **Cloudflare Web Analytics** (sin cookies).
