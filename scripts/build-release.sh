#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
source "$SCRIPT_DIR/env.sh"

echo "=== [1/3] Compilando Rust en modo RELEASE (LTO + strip) ==="
"$SCRIPT_DIR/build-rust.sh" release

echo "=== [2/3] Compilando APK Release (R8/ProGuard) ==="
cd "$APP_ROOT"
./gradlew assembleRelease --no-daemon

echo "=== [3/3] Generando AAB Release (Play Store ready) ==="
./gradlew bundleRelease --no-daemon

echo "=== Artefactos generados ==="
ls -lh "$APP_ROOT/app/build/outputs/apk/release/" "$APP_ROOT/app/build/outputs/bundle/release/"
