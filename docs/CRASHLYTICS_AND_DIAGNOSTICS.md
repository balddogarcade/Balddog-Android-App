# Crashlytics y Diagnóstico (Kotlin + Rust + NDK)

Esta guía documenta la arquitectura, instrumentación y flujo de símbolos de **Firebase Crashlytics** y **Crashlytics NDK** en `Balddog-Android-App`.

## 1. Arquitectura y Flujo de Símbolos (*Unstripped* vs *Stripped*)

Para mantener el APK Release optimizado (~26 MB) sin perder la simbolización de crashes nativos en C/C++ y Rust:

```text
rust/Cargo.toml (debug = "line-tables-only", panic = "unwind")
  └─> scripts/build-rust.sh release
        ├─> .cache/unstripped-jniLibs/arm64-v8a/libbalddog_android.so  (con DWARF -> uploadCrashlyticsSymbolFileRelease)
        └─> app/src/main/jniLibs/arm64-v8a/libbalddog_android.so       (llvm-strip --strip-all -> APK)

scripts/build-fbneo-android.sh (-gline-tables-only)
  ├─> .cache/unstripped-jniLibs/arm64-v8a/libfbneo_libretro.so         (con DWARF -> uploadCrashlyticsSymbolFileRelease)
  └─> app/src/main/jniLibs/arm64-v8a/libfbneo_libretro.so              (llvm-strip --strip-unneeded -> APK)
```

- `app/build.gradle.kts` configura `unstrippedNativeLibsDir = file("${rootProject.projectDir}/.cache/unstripped-jniLibs")` y `nativeSymbolUploadEnabled = true` dentro de `buildTypes.release`.
- `.cache/` está en `.gitignore` y **no se elimina** con `./gradlew clean`.
- `app/proguard-rules.pro` preserva `SourceFile` y `LineNumberTable` para que R8 + `uploadCrashlyticsMappingFileRelease` generen stack traces Kotlin legibles.

---

## 2. Instrumentación y Custom Keys Estándar

La inicialización ocurre en `BalddogApplication.onCreate()` (`setCrashlyticsCollectionEnabled(!BuildConfig.DEBUG)`). Para futuras funcionalidades, usa siempre `dog.bald.arcade.diagnostics.CrashlyticsHelper`:

| Clave (`Custom Key`) | Origen | Propósito |
| :--- | :--- | :--- |
| `abi`, `version_name`, `version_code` | `BalddogApplication` | Identificar arquitectura (`arm64-v8a`) y versión exacta |
| `game_id`, `game_title`, `rom_name`, `rom_sha256` | `GameActivity` | Identificar qué juego/ROM estaba activo |
| `netplay_mode` | `GameActivity` | `offline`, `host`, `client` o `replay` |
| `session_state`, `room_id`, `netplay_ping_ms` | `GameActivity` | Estado GGRS (`synchronizing`, `running`, `ended`) |

### Reglas para nuevas funcionalidades
- **Nuevas Activities / flujos críticos**: registrar breadcrumbs con `CrashlyticsHelper.log(...)` y excepciones recuperables en `catch` con `CrashlyticsHelper.recordNonFatal(t)`.
- **Rust (`balddog-android`)**: `init_native_crash_reporting()` instala un `std::panic::set_hook` que escribe archivo, línea y payload en `logcat` (tag `balddog-panic`) antes de ejecutar `std::process::abort()` (`SIGABRT`) para que Crashlytics NDK capture el minidump.
- **Privacidad**: nunca enviar tokens, claves de API, contraseñas ni PII en Custom Keys o breadcrumbs.

---

## 3. Credenciales (`app/google-services.json`)

- `app/google-services.json` está ignorado en `.gitignore` y **nunca** debe commitearse.
- En entornos nuevos o CI, puede descargarse desde Firebase Management API usando la Service Account configurada en `local.properties` (`FIREBASE_PROJECT_ID`, `FIREBASE_CLIENT_EMAIL`, `FIREBASE_PRIVATE_KEY`) o inyectarse vía secreto Base64 (`GOOGLE_SERVICES_JSON_B64`).

---

## 4. Cómo Consultar Crashes y Verificar Símbolos

1. **Verificación local de binarios antes de compilar Release**:
   ```bash
   file .cache/unstripped-jniLibs/arm64-v8a/*.so
   file app/src/main/jniLibs/arm64-v8a/*.so
   ```
2. **Compilar y subir símbolos**:
   ```bash
   source scripts/env.sh && unset GRADLE_USER_HOME && ./gradlew assembleRelease uploadCrashlyticsSymbolFileRelease
   ```
3. **Inspección en vivo por USB (`adb logcat`)**:
   - Panics nativos de Rust: `adb logcat -s balddog-panic`
   - SDK de Crashlytics: `adb logcat -s FirebaseCrashlytics`
4. **Firebase Console**:
   - Filtrar por `version_name` / `version_code`, revisar pestañas **Keys** y **Logs**, y confirmar que los frames nativos muestran archivo y línea (`.rs` / `.cpp`).
