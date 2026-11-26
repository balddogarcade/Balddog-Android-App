# Changelog

All notable changes to **bald.dog ARCADE** will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- Expanded `GameDto` with 22 Arcade Database metadata and IPFS media fields (`manufacturer`, `year`, `genre`, `serie`, `players`, `nplayers`, `input_controls`, `input_buttons`, `buttons_colors`, `history`, `youtube_video_id`, and IPFS gallery URLs) plus `AnalyticsHelper` / `CrashlyticsHelper` instrumentation for ROM detail views.
- Centralized `PerformanceHelper` (`dog.bald.arcade.diagnostics.PerformanceHelper`) wrapping Firebase Performance Monitoring (`firebase-perf`) with custom traces (`rom_startup_trace`, `netplay_sync_trace`) in `GameActivity`.
- Centralized `AnalyticsHelper` (`dog.bald.arcade.diagnostics.AnalyticsHelper`) wrapping Google Analytics for Firebase (`firebase-analytics`) without PII, initialized in `BalddogApplication` and tracking `game_launch` and `game_session_end` in `GameActivity`.

### Security
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
- Initial release of **bald.dog ARCADE** (`dog.bald.arcade`) for Android (`arm64-v8a`).
- Rust core (`balddog-android` / `balddog-core`) with Libretro + FinalBurn Neo (`libfbneo_libretro.so`).
- P2P Netplay with GGRS rollback, dual-candidate endpoint resolution, and voice chat.
- 1CC leaderboards, replay recording/playback, and Firebase Crashlytics NDK diagnostics.

[Unreleased]: https://github.com/balddogarcade/Balddog-Android-App/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/balddogarcade/Balddog-Android-App/releases/tag/v0.2.0
[0.1.0]: https://github.com/balddogarcade/Balddog-Android-App/releases/tag/v0.1.0
