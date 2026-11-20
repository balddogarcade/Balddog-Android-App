# bald.dog ARCADE (Android)

Cliente nativo de **bald.dog ARCADE** para Android (`arm64-v8a`). Combina una interfaz moderna en **Kotlin + Jetpack Compose** con un motor de emulación y netplay rollback escrito en **Rust** (`wgpu`, `oboe`, `ggrs`) sobre **FinalBurn Neo (libretro)**, respaldado por **Supabase** e **IPFS** para catálogo, salas multijugador y descarga verificada de ROMs/BIOS.

---

## Características principales

- **Motor nativo en Rust (`balddog-core`)**:
  - Carga dinámica del core `libfbneo_libretro.so` mediante `libloading`.
  - Renderizado de video con `wgpu` conectado a la superficie nativa de Android (`Surface` / `ANativeWindow` vía JNI).
  - Audio de baja latencia con `oboe` y buffer circular (`ringbuf`).
- **Multijugador LAN y Online (`balddog-netplay`)**:
  - Netplay P2P con rollback basado en **GGRS**.
  - Descubrimiento de partidas en red local mediante Android NSD (`LanNsdManager`).
  - Lobbies, slots de jugadores y chat en tiempo real con **Supabase Realtime**.
- **Gestión de ROMs y BIOS (`IpfsRomManager`)**:
  - Descarga desde IPFS con verificación de integridad (`sha256` / `crc32`) según el catálogo y BIOS (`game_bios`) definidos en Supabase.
- **Controles adaptativos**:
  - Soporte para gamepads físicos (`ControllerManager`, `RetroJoypad`).
  - Overlay táctil personalizable (`ArcadeTouchOverlay`, `TouchLayoutPrefs`) y menú rápido in-game (`InGameQuickMenu`).
- **Autenticación y seguridad**:
  - Inicio de sesión por correo o como invitado/anónimo con **Supabase Auth**.
  - Almacenamiento cifrado de tokens mediante `androidx.security:security-crypto`.

---

## Estructura del proyecto

```text
.
├── app/                          # Aplicación Android (Kotlin + Jetpack Compose)
│   └── src/main/
│       ├── java/dog/bald/arcade/
│       │   ├── data/             # Repositorio, modelos, Supabase, IPFS ROM manager
│       │   ├── engine/           # Puente JNI/Surface hacia Rust
│       │   ├── input/            # Gamepads, RetroJoypad y preferencias táctiles
│       │   ├── net/              # Descubrimiento LAN (NSD)
│       │   └── ui/               # Pantallas (Auth, MainShell, RoomLobby) y tema
│       ├── java/uniffi/          # Bindings Kotlin generados por UniFFI
│       └── jniLibs/arm64-v8a/    # Binarios nativos (.so)
├── rust/                         # Workspace Rust
│   ├── balddog-core/             # Núcleo de emulación (libretro + wgpu + oboe + ggrs)
│   ├── balddog-netplay/          # Transporte LAN y P2P rollback (GGRS)
│   └── balddog-android/          # Bridge UniFFI + JNI y generador uniffi-bindgen
├── scripts/                      # Scripts de toolchain y compilación
│   ├── setup-toolchain.sh        # Instala/configura JDK, Android SDK/NDK y cargo-ndk
│   ├── build-fbneo-android.sh    # Compila libfbneo_libretro.so (arm64-v8a)
│   ├── build-rust.sh             # Compila libbalddog_android.so y genera bindings UniFFI
│   └── build-release.sh          # Genera APK y AAB de release
└── supabase/migrations/          # Esquema SQL, RLS, Realtime y catálogo inicial
```

---

## Requisitos

- **Sistema operativo**: Linux (para los scripts de toolchain y compilación nativa).
- **Android**:
  - `minSdk`: 26 (Android 8.0)
  - `targetSdk` / `compileSdk`: 36
  - Arquitectura soportada: `arm64-v8a`
  - Android NDK `27.2.12479018`
  - JDK 17 o 21
- **Rust**: `rustup`, `cargo`, target `aarch64-linux-android` y `cargo-ndk`.
- **Herramientas base**: `git`, `curl`, `unzip`, `make`.

---

## Configuración inicial

### 1. Instalar el toolchain

El script `scripts/setup-toolchain.sh` verifica o descarga JDK 21, Android Command Line Tools, Platform 36, Build-Tools 36, NDK `27.2.12479018`, agrega el target de Rust `aarch64-linux-android`, instala `cargo-ndk` y genera `scripts/env.sh`:

```bash
./scripts/setup-toolchain.sh
source scripts/env.sh
```

### 2. Configurar variables de Supabase y firma

Agrega las propiedades necesarias en `local.properties` (en la raíz del proyecto) o expórtalas como variables de entorno:

```properties
# Ruta al Android SDK (generada normalmente por Android Studio / Gradle)
sdk.dir=/ruta/a/Android/Sdk

# Configuración de Supabase (obligatoria para BuildConfig)
SUPABASE_URL=https://tu-proyecto.supabase.co
SUPABASE_ANON_KEY=tu-anon-key

# Opcional: firma para builds release (APK / AAB)
BALDDOG_KEYSTORE_PATH=/ruta/al/keystore.jks
BALDDOG_KEYSTORE_PASSWORD=tu_password
BALDDOG_KEY_ALIAS=tu_alias
BALDDOG_KEY_PASSWORD=tu_key_password
```

### 3. Base de datos Supabase

Aplica las migraciones ubicadas en `supabase/migrations/` en tu proyecto de Supabase:

1. `supabase/migrations/001_initial_schema.sql` — Crea las tablas `profiles`, `game_bios`, `games`, `rooms`, `room_messages`, triggers, políticas RLS y Realtime.
2. `supabase/migrations/002_seed_curated_games.sql` — Inserta el catálogo curado inicial de juegos.

---

## Compilación

### 1. Compilar el core FinalBurn Neo (`libfbneo_libretro.so`)

Clona FBNeo en `.cache/FBNeo` en el commit fijado y compila el core determinista para `arm64-v8a`:

```bash
./scripts/build-fbneo-android.sh
```

### 2. Compilar el motor Rust y generar bindings Kotlin

Para compilación en modo `debug` (por defecto) o `release`:

```bash
./scripts/build-rust.sh debug
# o para release:
./scripts/build-rust.sh release
```

Este paso genera `app/src/main/jniLibs/arm64-v8a/libbalddog_android.so`, copia `libc++_shared.so` y actualiza los bindings UniFFI en `app/src/main/java/uniffi/balddog_android/`.

### 3. Compilar la aplicación Android

**Debug APK:**

```bash
./gradlew assembleDebug
```

**Release (APK + AAB):**

```bash
./scripts/build-release.sh
```

Los artefactos generados quedan en:
- `app/build/outputs/apk/release/`
- `app/build/outputs/bundle/release/`

---

## Licencia

Consulta los archivos del repositorio (workspace Rust bajo licencia `MIT`).
