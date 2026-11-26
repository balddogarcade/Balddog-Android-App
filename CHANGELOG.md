# Changelog

All notable changes to **bald.dog ARCADE** will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed
- Replaced horizontal scrolling `LazyRow` catalog filters with a full-width non-scrolling `Row` of square-bordered `CatalogFilterChip` items (`Nuevos`, `Más descargados`, `Más jugados`, `Biblioteca`) in `MainShellScreen.kt`.
- Removed residual anonymous/guest accounts (`is_anonymous = true`) and enforced registered-only user accounts across the platform (`028_public_web_stats_rpc.sql`).

### Added
- Public read-only Supabase RPC `get_public_arcade_stats()` for aggregated landing page metrics and Top 6 game rankings (`028_public_web_stats_rpc.sql`).

## [0.2.0] - 2026-11-25

### Added
- Dynamic SemVer versioning (`-PversionName` / `-PversionCode` and environment variables) in `app/build.gradle.kts`.
- Consistent release signing configuration supporting `ANDROID_KEYSTORE_*` environment variables and `local.properties` fallback.
- Automated GitHub Actions release pipeline with SLSA build provenance attestation and informative VirusTotal API v3 scanning.
- Continuous security workflows (`cargo-audit`, GitHub CodeQL, and Dependabot).
- In-app updater (`AppUpdateManager`) with SHA-256 verification and Profile screen integration.

### Fixed
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
