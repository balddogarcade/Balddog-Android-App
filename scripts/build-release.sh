#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
source "$SCRIPT_DIR/env.sh"

HAS_KEYSTORE=0
if [ -n "${BALDDOG_KEYSTORE_PATH:-}" ]; then
  HAS_KEYSTORE=1
elif [ -f "$APP_ROOT/local.properties" ] && grep -qE '^BALDDOG_KEYSTORE_PATH=.+' "$APP_ROOT/local.properties"; then
  HAS_KEYSTORE=1
fi

if [ "$HAS_KEYSTORE" -eq 0 ]; then
  echo "ADVERTENCIA: BALDDOG_KEYSTORE_PATH no está configurada (ni en env ni en local.properties)." >&2
  echo "El AAB/APK de release se generará con firma DEBUG de fallback (no apto para Play Console producción)." >&2
  echo "Configura BALDDOG_KEYSTORE_PATH, BALDDOG_KEYSTORE_PASSWORD, BALDDOG_KEY_ALIAS y BALDDOG_KEY_PASSWORD." >&2
fi

FBNEO_SO="$APP_ROOT/app/src/main/jniLibs/arm64-v8a/libfbneo_libretro.so"
if [ ! -f "$FBNEO_SO" ]; then
  echo "=== Compilando libfbneo_libretro.so faltante ==="
  "$SCRIPT_DIR/build-fbneo-android.sh"
fi

echo "=== [1/3] Compilando Rust en modo RELEASE (LTO + strip) ==="
"$SCRIPT_DIR/build-rust.sh" release

STRIP_BIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
if [ -x "$STRIP_BIN" ]; then
  echo "Aplicando llvm-strip --strip-all a libbalddog_android.so..."
  "$STRIP_BIN" --strip-all "$APP_ROOT/app/src/main/jniLibs/arm64-v8a/libbalddog_android.so"
fi

echo "=== [2/3] Compilando APK Release (R8/ProGuard) ==="
cd "$APP_ROOT"
unset GRADLE_USER_HOME
./gradlew --no-daemon -Dorg.gradle.workers.max=2 -Dorg.gradle.parallel=false assembleRelease
./gradlew --stop

echo "=== [3/3] Generando AAB Release (Play Store ready) ==="
unset GRADLE_USER_HOME
./gradlew --no-daemon -Dorg.gradle.workers.max=2 -Dorg.gradle.parallel=false bundleRelease
./gradlew --stop

echo "=== Artefactos generados ==="
ls -lh "$APP_ROOT/app/build/outputs/apk/release/" "$APP_ROOT/app/build/outputs/bundle/release/"
