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
  echo "El AAB/APK de release se generará SIN FIRMAR y Play Console lo rechazará." >&2
  echo "Configura BALDDOG_KEYSTORE_PATH, BALDDOG_KEYSTORE_PASSWORD, BALDDOG_KEY_ALIAS y BALDDOG_KEY_PASSWORD." >&2
fi

echo "=== [1/3] Compilando Rust en modo RELEASE (LTO + strip) ==="
"$SCRIPT_DIR/build-rust.sh" release

echo "=== [2/3] Compilando APK Release (R8/ProGuard) ==="
cd "$APP_ROOT"
./gradlew assembleRelease --no-daemon

echo "=== [3/3] Generando AAB Release (Play Store ready) ==="
./gradlew bundleRelease --no-daemon

echo "=== Artefactos generados ==="
ls -lh "$APP_ROOT/app/build/outputs/apk/release/" "$APP_ROOT/app/build/outputs/bundle/release/"
