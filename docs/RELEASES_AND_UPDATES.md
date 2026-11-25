# Releases, Versionamiento y Auto-Actualizador In-App

Este documento describe la arquitectura de versionamiento, publicación automatizada de releases y actualización in-app de **bald.dog ARCADE** (`dog.bald.arcade`).

---

## 1. Versionamiento SemVer y `CHANGELOG.md`

- El proyecto sigue **Semantic Versioning 2.0.0** (`MAJOR.MINOR.PATCH`).
- `app/build.gradle.kts` resuelve `versionName` y `versionCode` dinámicamente en tiempo de compilación:
  - Propiedades de Gradle: `-PversionName=X.Y.Z` y `-PversionCode=N`
  - Variables de entorno: `VERSION_NAME` y `VERSION_CODE`
  - Fallback local por defecto: `versionName = "0.1.0"`, `versionCode = 1`
- Ambos valores se exponen en Kotlin mediante `BuildConfig.VERSION_NAME` y `BuildConfig.VERSION_CODE`.
- `CHANGELOG.md` sigue el estándar **Keep a Changelog** (`[Unreleased]`, `[X.Y.Z] - YYYY-MM-DD`, secciones `Added`, `Changed`, `Fixed`, `Security`).

---

## 2. Flujo de Release Automatizado (`.github/workflows/release.yml`)

Al hacer push de un tag `v*.*.*` (por ejemplo `v0.2.0`) o ejecutar `workflow_dispatch`:

1. **Toolchain:** Configura JDK 21, Android SDK/NDK (`arm64-v8a`), Rust (`aarch64-linux-android` + `cargo-ndk`) y cachés.
2. **Nativo:** Compila/cachea `libfbneo_libretro.so` (`scripts/build-fbneo-android.sh`) y compila `libbalddog_android.so` en `--release` (`scripts/build-rust.sh release`) conservando copia *unstripped* en `.cache/unstripped-jniLibs/arm64-v8a/` y aplicando `llvm-strip --strip-all` al `.so` empaquetado.
3. **Firma y Build:** Decodifica `ANDROID_KEYSTORE_BASE64` y `GOOGLE_SERVICES_JSON_BASE64` a archivos temporales y ejecuta `./gradlew assembleRelease -PversionName=... -PversionCode=...`.
4. **Verificación:** Valida la firma con `apksigner verify --verbose` y genera `app-release.apk.sha256`.
5. **Atestación y Escaneo:** Emite atestación SLSA (`actions/attest-build-provenance`) y sube el APK a VirusTotal API v3 de forma informativa (`continue-on-error: true`).
6. **Publicación:** Extrae las notas de la versión desde `CHANGELOG.md` y publica el GitHub Release con `app-release.apk` y `app-release.apk.sha256`.

---

## 3. Auto-Actualizador In-App (`AppUpdateManager.kt`)

Ubicado en `app/src/main/java/dog/bald/arcade/data/AppUpdateManager.kt` e integrado en `ProfileScreen.kt` (tarjeta **VERSIÓN Y ACTUALIZACIONES**):

- **Consulta:** Hace `GET` a `https://api.github.com/repos/balddogarcade/Balddog-Android-App/releases/latest` (sin tokens embebidos).
- **Comparación SemVer:** `AppUpdateManager.isNewerVersion(remote, current)` compara `MAJOR.MINOR.PATCH` ignorando prefijos `v` y sufijos pre-release.
- **Descarga y Verificación SHA-256:**
  - Descarga `app-release.apk` en `cacheDir/updates/` reportando progreso en tiempo real (`Downloading(progress, downloadedBytes, totalBytes)`).
  - Descarga `app-release.apk.sha256`, calcula el `SHA-256` local del APK y **aborta eliminando el archivo temporal si no coinciden**.
- **Instalación:**
  - Verifica `packageManager.canRequestPackageInstalls()`. Si falta el permiso, abre `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES`.
  - Expone el APK mediante `FileProvider` (`${applicationId}.fileprovider` + `res/xml/file_paths.xml`) y lanza `Intent.ACTION_VIEW` con `FLAG_GRANT_READ_URI_PERMISSION`.
