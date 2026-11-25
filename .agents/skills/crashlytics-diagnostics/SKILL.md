---
name: crashlytics-diagnostics
description: >-
  Use this skill when adding Crashlytics instrumentation to new Android/Rust features,
  rebuilding native symbols (Rust + FBNeo) for Crashlytics NDK, uploading release symbols,
  or investigating Kotlin and native crashes/panics in Firebase Crashlytics.
---

# Crashlytics & NDK Diagnostics Runbook

## 1. Cuándo y cómo instrumentar nuevas funcionalidades
- **Nuevas pantallas / flujos críticos en Kotlin**: llama a `CrashlyticsHelper.log("...")` en transiciones clave y `CrashlyticsHelper.recordNonFatal(e)` en bloques `catch` relevantes (`dog.bald.arcade.diagnostics.CrashlyticsHelper`).
- **Nuevas sesiones o estados de juego/netplay**: agrega o actualiza claves en `CrashlyticsHelper` (`game_id`, `rom_name`, `netplay_mode`, `session_state`, etc.) evitando cualquier dato sensible o credencial.
- **Nuevos entrypoints nativos en Rust**: asegúrate de que `init_native_crash_reporting()` haya sido invocado (ya se llama en `ping_engine` y `GameActivity`) para capturar `panic!` en `logcat` (`balddog-panic`) antes de `abort()`.

## 2. Compilar nativos + APK Release + Subir símbolos
1. Compilar Rust con símbolos DWARF en `.cache/unstripped-jniLibs/arm64-v8a/` y strip en `jniLibs`:
   ```bash
   source scripts/env.sh && ./scripts/build-rust.sh release
   ```
2. Compilar FBNeo con `-gline-tables-only` (copia unstripped en `.cache/unstripped-jniLibs/arm64-v8a/`):
   ```bash
   source scripts/env.sh && ./scripts/build-fbneo-android.sh
   ```
3. Verificar separación:
   ```bash
   file .cache/unstripped-jniLibs/arm64-v8a/*.so
   file app/src/main/jniLibs/arm64-v8a/*.so
   ```
4. Compilar APK Release y subir mapping R8 + símbolos NDK:
   ```bash
   source scripts/env.sh && unset GRADLE_USER_HOME && ./gradlew assembleRelease uploadCrashlyticsSymbolFileRelease
   ```

## 3. Cómo consultar crashes y verificar simbolización
- **En dispositivo (`adb logcat`)**:
  - Panics de Rust: `adb logcat -s balddog-panic`
  - Inicialización / envíos de Crashlytics: `adb logcat -s FirebaseCrashlytics`
- **En Firebase Console**:
  - Ir a **Crashlytics** -> filtrar por versión (`version_name` / `version_code`) y revisar la pestaña **Keys** (`game_id`, `netplay_mode`, `session_state`, `abi`) y **Logs** (breadcrumbs).
  - Para crashes nativos (`libbalddog_android.so` / `libfbneo_libretro.so`), verificar que el stack trace muestre archivo y línea (`.rs` / `.cpp`) en lugar de offsets hexadecimales sin resolver.
