# Changelog

All notable changes to **bald.dog ARCADE** will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Fixed
- Fixed `GameLeaderboardSheet` displaying outdated player avatars by adding `avatar_seed` to `LeaderboardEntryDto` and passing `entry.avatarSeed` (falling back to `entry.username`) to `DiceBearPixelArtAvatar`, prevented `AUDITORÍA`/`✓ VERIF.`/`TÚ` badges from wrapping onto multiple lines, and aligned the `"TU POSICIÓN ACTUAL"` row with replay playback actions.
- Fixed `scripts/verify-replays-worker.py` ROM lookup failing with `HTTP 400 Bad Request` by querying `ipfs_cid,system` instead of the non-existent `rom_url` column and downloading ROMs from IPFS gateways with fallback.

### Changed
- Rotated the official release signing keystore (`.cache/balddog-release.keystore`, RSA 4096), backed up signing credentials in `local.properties`, synchronized GitHub Actions Secrets, and updated `assetlinks.json` with the new SHA-256 fingerprint.
- Pinned all GitHub Actions in `.github/workflows/verify-replays.yml` (`actions/checkout`, `actions/cache`, `dtolnay/rust-toolchain`, `Swatinem/rust-cache`) to full commit SHAs to satisfy OpenSSF Scorecard `Pinned-Dependencies` checks.
- Improved replay seeking UX with an in-memory keyframe cache (`ReplayKeyframeCache` every 300 frames, up to 60 states) in `balddog-core` for faster forward/backward jumps, plus an `Avanzando...` progress indicator in `ReplayHudOverlay` while seeking is active.

### Added
- Comprehensive Firebase Analytics (GA4) instrumentation across `AuthScreen`, `MainShellScreen` (tabs, overlays, catalog search/filters, ROM downloads), `GameActivity` (1CC score submissions, new records, in-game quick menu, media exports, replay controls), `RoomLobbyScreen`, `PublicProfileScreen`, `FollowListScreen`, and `AppUpdateManager`, with parameter sanitization (`AnalyticsHelper.sanitizeParam`), `AnalyticsHelperTest`, and unified Web+Android taxonomy in `docs/ANALYTICS.md`.
- Quick Matchmaking system (`048_matchmaking_and_room_bans.sql`, `QuickMatchmakingCard`, `MatchmakingPolicy`) with atomic public `waiting`-room backfill (prioritizing most populated, then oldest), 25s heartbeat, Supabase Realtime + fallback queue matching, floating `MatchmakingMiniBanner` across tabs, and `GameDetailScreen` quick-match shortcut.
- Live matchmaking and active-player metrics (`get_matchmaking_stats()`) refreshed every 12s in the Rooms tab and displayed alphabetically per game.
- Per-room host ban support (`ban_room_player` RPC + `banned_user_ids`), automatic host promotion on waiting-room leave, automatic closing of empty rooms, and automatic background BIOS+ROM download with progress bar on the Lobby Ready button.
- Android App Links (`https://bald.dog/room/<uuid>` and `balddog://room/<uuid>`) with eligibility checks, room-switch confirmation, and Firebase Analytics/Crashlytics/Performance instrumentation.
- Global navigation links to `GameDetailScreen` from the game catalog (`ArcadeGameCard`), `RankingsScreen`, `GameRankingScreen`, `ProfileScreen`, `PublicProfileScreen`, and `RoomLobbyScreen` whenever a game cover or title is tapped.
- Automated serverless replay verification pipeline via GitHub Actions (`.github/workflows/verify-replays.yml`), Linux deterministic FBNeo build script (`scripts/build-fbneo-linux.sh`), and on-demand ROM fetching in `scripts/verify-replays-worker.py`.
- Comprehensive replay verification architecture and operations guide in `docs/REPLAY_VERIFICATION.md`.

## [0.4.0] - 2026-11-27

### Added
- Comprehensive Firebase Analytics (GA4) instrumentation across `AuthScreen`, `MainShellScreen` (tabs, overlays, catalog search/filters, ROM downloads), `GameActivity` (1CC score submissions, new records, in-game quick menu, media exports, replay controls), `RoomLobbyScreen`, `PublicProfileScreen`, `FollowListScreen`, and `AppUpdateManager`, with parameter sanitization (`AnalyticsHelper.sanitizeParam`), `AnalyticsHelperTest`, and unified Web+Android taxonomy in `docs/ANALYTICS.md`.
- Certified 1CC `rcheevos` leaderboard `score_config` expressions (`043_seed_tmnt_snowbro2_pbobble_score_configs.sql`) and lifecycle unit tests in `leaderboard.rs` for *TMNT: Turtles in Time* (`tmnt2`: `0x00B0` / `b0x 0104`), *Teenage Mutant Ninja Turtles* (`tmnt`: `0x00A0` / `b0x 0104`), *Snow Bros. 2: With New Elves* (`snowbro2`: `0x00DE` / `0x00DA..0x00DC`), and *Puzzle Bobble / Bust-A-Move* (`pbobble`: `0x26E4` / `0x7058..0x705A`), verified against headless `.bsv.gz` replays with `verify_replay`.

### Fixed
- Fixed Snow Bros. 1 (`snowbros`) P1 `rcheevos` leaderboard score extraction in `leaderboard.rs` and Supabase (`046_fix_snowbros_score_config_1580.sql`) to read `0x1580..0x1583` in word-swapped BCD (`bcd4_sw`) with an initial-zero `STA` guard (`0x 1580=0_0x 1582=0`) to avoid boot RAM-test false triggers.
- Fixed Metal Slug 3 (`mslug3`) P1 `rcheevos` leaderboard score extraction in `leaderboard.rs` and Supabase (`044_fix_mslug3_score_config_bcd4_sw.sql`) to read `0xED18..0xED1B` in word-swapped BCD (`bcd4_sw`) instead of static `0xF04C..0xF04F`.
- Fixed `DiceBearPixelArtAvatar` SVG parsing (`parseDiceBearSvgLayers`) to resolve `<defs><g id="...">` groups and `<use transform="translate(x y)" href="#...">` references from DiceBear 11.x SVGs, preventing avatar parts from rendering stacked at `(0, 0)` and ensuring the **ALEATORIO** button in `EditProfileDialog` updates the avatar cleanly.
- Hardened `CoinTracker` in `balddog-core` (`session.rs`) to count `RETRO_COIN_MASK` edges across P1–P4 from frame 0 (boot, title screen, character select, active gameplay, and continue screen), immediately disqualifying ranked 1CC sessions when 2+ credits are inserted at any point and keeping scores permanently frozen after a continue; updated the multi-credit notice in `GameActivity.kt`.
- Fixed P1 `rcheevos` leaderboard score extraction in `leaderboard.rs` and Supabase (`039`–`042`) for `ddragon2` (`0x03EB..0x03EC`), `mslug3` (`0xF04C..0xF04F`), `snowbros` (`0x0D70..0x0D73`), and `tumblep` (`0x3D04`), verified against real `.bsv.gz` replays and unit tests.
- Fixed netplay NVRAM desync (`clear_deterministic_nvram`), stale `audio_scratch` replay in `take_audio_samples()`, `ReplayRecorder` cutoff after 1CC disqualification, `SeekReplay` leaderboard evaluation, `pending_submit_score` compare-exchange race, and `GgrsEvent::Synchronized` exposure for 0ms LAN sync.
- Fixed `GameActivity` `configChanges` (`screenLayout|smallestScreenSize|uiMode|density`), `rom_startup_trace` leak, transient `fetchRoomById` netplay disconnects, and `LaunchedEffect(Unit)` polling.

### Performance
- Moved game cover images (`public.games.cover_url`) from Pinata IPFS back to Supabase Storage (`covers` public bucket, `046_supabase_storage_covers.sql`, `scripts/upload-covers-supabase.py`) for faster catalog loading, while keeping ROMs (`ipfs_cid`) and secondary detail gallery images on IPFS.
- Optimized `GameCoverImage` with shared `OkHttpClient`, in-flight deduplication, atomic disk writes, `inSampleSize` downsampling, and KB-bounded `LruCache`.
- Parallelized `MainShellScreen.refreshAll()` with `coroutineScope { async ... }` and skipped Opus decoding when voice `listen_enabled` is false.
- Added `-Wl,-z,max-page-size=16384` to `scripts/build-rust.sh` for 16 KB page alignment.

### Added
- Comprehensive Firebase Analytics (GA4) instrumentation across `AuthScreen`, `MainShellScreen` (tabs, overlays, catalog search/filters, ROM downloads), `GameActivity` (1CC score submissions, new records, in-game quick menu, media exports, replay controls), `RoomLobbyScreen`, `PublicProfileScreen`, `FollowListScreen`, and `AppUpdateManager`, with parameter sanitization (`AnalyticsHelper.sanitizeParam`), `AnalyticsHelperTest`, and unified Web+Android taxonomy in `docs/ANALYTICS.md`.
- Added in-app **Eliminar cuenta** flow in `ProfileScreen` and legal links (`https://bald.dog/privacy`, `https://bald.dog/terms`).

### Changed
- Redesigned the avatar card in `EditProfileDialog` by removing the redundant `"AVATAR PIXEL-ART"` / `"Generado al azar"` text block, enlarging the avatar preview (`76.dp` container / `66.dp` image), and updating the button to `"CAMBIAR AVATAR"` with a `"Genera un nuevo avatar al azar"` caption.
- Removed the recurring in-game `"¡Nuevo récord personal: ...!"` floating notice in `GameActivity` during score submissions while keeping score submission, offline queuing, and logging intact.
- Simplified `ArcadeGameCard` in the catalog by moving player count (`SportsEsports` icon) and ROM size (`Storage` icon) into the cover image overlay, removing the `CO-OP` badge, and leaving only the ROM title and primary action button in the card body.
- Added immediate loading indicator (`ABRIENDO...`) and double-tap protection on the catalog **JUGAR** button (`ArcadeGameCard` / `CatalogTab`) when launching a downloaded game.
- Aligned `DiceBearPixelArtAvatar` with DiceBear `11.x/pixel-art` (`api.dicebear.com/11.x/pixel-art/svg`) using `ktor-client-okhttp` with in-memory + disk SVG caching and offline v9.4.2 fallback so avatars match `bald.dog` identically.
- Migrated all game cover images (`cover_url`) from bundled `assets/covers/*.webp` to Pinata IPFS (`public.games.cover_url`), refactored `GameCoverImage` and `RemoteImage` to share a unified memory + disk cache (`RemoteImageCache`), and removed local cover assets from the APK.

### Added
- Comprehensive Firebase Analytics (GA4) instrumentation across `AuthScreen`, `MainShellScreen` (tabs, overlays, catalog search/filters, ROM downloads), `GameActivity` (1CC score submissions, new records, in-game quick menu, media exports, replay controls), `RoomLobbyScreen`, `PublicProfileScreen`, `FollowListScreen`, and `AppUpdateManager`, with parameter sanitization (`AnalyticsHelper.sanitizeParam`), `AnalyticsHelperTest`, and unified Web+Android taxonomy in `docs/ANALYTICS.md`.
- Added `GameDetailScreen` (`GameDetailHeader`, `GameDetailGallery`, `GameDetailSpecsCard`, `GameDetailHistoryCard`) in Android to display full Arcade Database metadata, IPFS image gallery, hardware specs, history, and YouTube gameplay links, instrumented with `AnalyticsHelper`, `CrashlyticsHelper`, and `PerformanceHelper`.
- Expanded `GameDto` with 22 Arcade Database metadata and IPFS media fields (`manufacturer`, `year`, `genre`, `serie`, `players`, `nplayers`, `input_controls`, `input_buttons`, `buttons_colors`, `history`, `youtube_video_id`, and IPFS gallery URLs) plus `AnalyticsHelper` / `CrashlyticsHelper` instrumentation for ROM detail views.
- Centralized `PerformanceHelper` (`dog.bald.arcade.diagnostics.PerformanceHelper`) wrapping Firebase Performance Monitoring (`firebase-perf`) with custom traces (`rom_startup_trace`, `netplay_sync_trace`) in `GameActivity`.
- Centralized `AnalyticsHelper` (`dog.bald.arcade.diagnostics.AnalyticsHelper`) wrapping Google Analytics for Firebase (`firebase-analytics`) without PII, initialized in `BalddogApplication` and tracking `game_launch` and `game_session_end` in `GameActivity`.

### Security
- Hardened Supabase RPCs and RLS (`037`, `038`): idempotent `update_room_slot('leave')`, preserved guest `ready` state on `'claim'`, anonymous signup block and username collision handling in `handle_new_user()`, closed direct `rooms` UPDATE DML, enforced `is_private` across public/leaderboard RPCs, added partial indexes, and revoked `PUBLIC` execute on `cleanup_stale_rooms()`.
- Hardened `send-welcome-email` Edge Function against HTML injection and TOCTOU duplicate sends, and added `delete-account` Edge Function for in-app account & replay deletion.
- Enforced strict BSV1 replay verification (`from_gzip_file_strict`, 16 MiB gzip cap, no embedded `initial_state` or `rom_crc32=0` bypass) and aligned `scripts/verify-replays-worker.py` with migration `023` parameters and `error` status mapping.
- Removed plaintext `SharedPreferences` fallback in `EncryptedSessionManager`, required `.sha256` verification in `AppUpdateManager`, and removed the blanket R8 `-keep class dog.bald.arcade.**` rule in `proguard-rules.pro`.
- Added `LICENSE` (MIT) and `cargo test` execution in `.github/workflows/quality.yml` (resolving OpenSSF Scorecard `License` and `CI-Tests`).
- Pinned all GitHub Actions in `.github/workflows/*.yml` to full commit SHAs (resolving OpenSSF Scorecard `Pinned-Dependencies`).
- Added `SECURITY.md` with vulnerability reporting policy and supported versions.
- Scoped `permissions` in `.github/workflows/release.yml` to `read-all` globally and job-level write permissions only on `build-and-release`.

## [0.3.0] - 2026-11-26

### Changed
- Upgraded Android toolchain and SDK BOMs: Gradle Wrapper `9.9.2`, Android Gradle Plugin `9.5.3` (with built-in Kotlin and `compileSdk` `37.2`), Kotlin plugins `2.4.21`, Jetpack Compose BOM `2026.11.01`, Supabase BOM `3.9.0` (migrating `EncryptedSessionManager` to `loadSession()` / `loadSessionOrNull()`), and Firebase BOM `35.2.0`.
- Updated Rust dependencies in `rust/balddog-core/Cargo.toml` (`png` 0.18.1, `libloading` 0.9.1, `pollster` 1.0.3, `ringbuf` 0.6.0) with all unit tests passing.
- Updated GitHub Actions dependencies in `.github/workflows/release.yml` (`gradle/actions` v6, `actions/cache` v6, `actions/setup-java` v6, `actions/attest-build-provenance` v4, `softprops/action-gh-release` v3) and `cc` crate (`1.11.3`) in `rust/Cargo.lock`.
- Replaced horizontal scrolling `LazyRow` catalog filters with a full-width non-scrolling `Row` of square-bordered `CatalogFilterChip` items (`Nuevos`, `Más descargados`, `Más jugados`, `Biblioteca`) in `MainShellScreen.kt`.
- Removed residual anonymous/guest accounts (`is_anonymous = true`) and enforced registered-only user accounts across the platform (`028_public_web_stats_rpc.sql`).

### Added
- Comprehensive Firebase Analytics (GA4) instrumentation across `AuthScreen`, `MainShellScreen` (tabs, overlays, catalog search/filters, ROM downloads), `GameActivity` (1CC score submissions, new records, in-game quick menu, media exports, replay controls), `RoomLobbyScreen`, `PublicProfileScreen`, `FollowListScreen`, and `AppUpdateManager`, with parameter sanitization (`AnalyticsHelper.sanitizeParam`), `AnalyticsHelperTest`, and unified Web+Android taxonomy in `docs/ANALYTICS.md`.
- Public read-only Supabase RPC `get_public_web_profile(p_username)` for web user profile pages (`/u/[username]`), respecting `is_private` and enriching 1CC high scores with replay playback metadata (`031_public_web_profile_rpc.sql`).
- Automatic idempotent welcome email delivery (`send-welcome-email` Edge Function via Resend) triggered on first profile load for newly registered users (email sign-up and Google OAuth), tracked with `public.profiles.welcome_email_sent` (`029_welcome_email_sent_flag.sql`).
- Public read-only Supabase RPC `get_public_arcade_stats()` for aggregated landing page metrics and Top 6 game rankings (`028_public_web_stats_rpc.sql`).

### Fixed
- Fixed *Metal Slug 3* (`mslug3`) and *Snow Bros.* (`snowbros`) leaderboard `score_config` expressions to read 4-byte Big-Endian BCD scores at `0xED16..0xED19` and `0x1403..0x1406` respectively (`033_fix_mslug3_and_snowbros_score_config.sql`).
- Fixed *The Simpsons* (`simpsons`) leaderboard `score_config` to read the 16-bit Big-Endian BCD enemy-defeat counter at `0x11D4..0x11D5` (`VAL:A:b0xH11D4*100_M:b0xH11D5`) instead of the spurious `0xX1B04` address, and cleared prior invalid scores (`032_fix_simpsons_score_config.sql`).
- Reactive Supabase `SessionStatus` observation in `MainActivity` with `ProfileCache` persistence and neutral `ArcadeAvatarOrPlaceholder` fallback, preventing wrong avatars or lost user data when minimizing/reopening the app.
- Google OAuth flow in `AuthScreen` now waits for the `dog.bald.arcade://login-callback` deeplink instead of navigating prematurely to the catalog without an active session.
- `NewRecordDialog` now triggers only when returning from `GameActivity` (`ActivityResultContracts.StartActivityForResult`) with initial scores loaded, preventing false-positive record modals on Google login or app resume.

## [0.2.0] - 2026-11-25

### Added
- Comprehensive Firebase Analytics (GA4) instrumentation across `AuthScreen`, `MainShellScreen` (tabs, overlays, catalog search/filters, ROM downloads), `GameActivity` (1CC score submissions, new records, in-game quick menu, media exports, replay controls), `RoomLobbyScreen`, `PublicProfileScreen`, `FollowListScreen`, and `AppUpdateManager`, with parameter sanitization (`AnalyticsHelper.sanitizeParam`), `AnalyticsHelperTest`, and unified Web+Android taxonomy in `docs/ANALYTICS.md`.
- Dynamic SemVer versioning (`-PversionName` / `-PversionCode` and environment variables) in `app/build.gradle.kts`.
- Consistent release signing configuration supporting `ANDROID_KEYSTORE_*` environment variables and `local.properties` fallback.
- Automated GitHub Actions release pipeline with SLSA build provenance attestation and informative VirusTotal API v3 scanning.
- Continuous security workflows (`cargo-audit`, GitHub CodeQL, and Dependabot).
- In-app updater (`AppUpdateManager`) with SHA-256 verification and Profile screen integration.

### Fixed
- Fixed *Metal Slug 3* (`mslug3`) and *Snow Bros.* (`snowbros`) leaderboard `score_config` expressions to read 4-byte Big-Endian BCD scores at `0xED16..0xED19` and `0x1403..0x1406` respectively (`033_fix_mslug3_and_snowbros_score_config.sql`).
- Fixed *The Simpsons* (`simpsons`) leaderboard `score_config` to read the 16-bit Big-Endian BCD enemy-defeat counter at `0x11D4..0x11D5` (`VAL:A:b0xH11D4*100_M:b0xH11D5`) instead of the spurious `0xX1B04` address, and cleared prior invalid scores (`032_fix_simpsons_score_config.sql`).
- Bottom navigation bar tab switching desynchronization with `HorizontalPager` in `MainShellScreen.kt`.

## [0.1.0] - 2026-11-25

### Added
- Comprehensive Firebase Analytics (GA4) instrumentation across `AuthScreen`, `MainShellScreen` (tabs, overlays, catalog search/filters, ROM downloads), `GameActivity` (1CC score submissions, new records, in-game quick menu, media exports, replay controls), `RoomLobbyScreen`, `PublicProfileScreen`, `FollowListScreen`, and `AppUpdateManager`, with parameter sanitization (`AnalyticsHelper.sanitizeParam`), `AnalyticsHelperTest`, and unified Web+Android taxonomy in `docs/ANALYTICS.md`.
- Initial release of **bald.dog ARCADE** (`dog.bald.arcade`) for Android (`arm64-v8a`).
- Rust core (`balddog-android` / `balddog-core`) with Libretro + FinalBurn Neo (`libfbneo_libretro.so`).
- P2P Netplay with GGRS rollback, dual-candidate endpoint resolution, and voice chat.
- 1CC leaderboards, replay recording/playback, and Firebase Crashlytics NDK diagnostics.

[Unreleased]: https://github.com/balddogarcade/Balddog-Android-App/compare/v0.4.0...HEAD
[0.4.0]: https://github.com/balddogarcade/Balddog-Android-App/releases/tag/v0.4.0
[0.3.0]: https://github.com/balddogarcade/Balddog-Android-App/releases/tag/v0.3.0
[0.2.0]: https://github.com/balddogarcade/Balddog-Android-App/releases/tag/v0.2.0
[0.1.0]: https://github.com/balddogarcade/Balddog-Android-App/releases/tag/v0.1.0
