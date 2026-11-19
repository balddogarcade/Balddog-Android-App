#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
RUST_DIR="$APP_ROOT/rust"
source "$SCRIPT_DIR/env.sh"

PROFILE="${1:-debug}"
CARGO_FLAGS=()
if [ "$PROFILE" = "release" ]; then
  CARGO_FLAGS+=(--release)
fi

cd "$RUST_DIR"

echo "=== [1/2] Compilando balddog-android ($PROFILE, arm64-v8a) ==="
RUSTFLAGS="${RUSTFLAGS:-} -l c++_shared" \
  cargo ndk -t arm64-v8a --platform 26 -o "$APP_ROOT/app/src/main/jniLibs" build -p balddog-android "${CARGO_FLAGS[@]}"

LIBCXX_SO="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so"
if [ -f "$LIBCXX_SO" ]; then
  cp "$LIBCXX_SO" "$APP_ROOT/app/src/main/jniLibs/arm64-v8a/libc++_shared.so"
fi

SO_PATH="$RUST_DIR/target/aarch64-linux-android/$PROFILE/libbalddog_android.so"
if [ ! -f "$SO_PATH" ]; then
  echo "ERROR: No se encontró $SO_PATH" >&2
  exit 1
fi

echo "=== [2/2] Generando bindings Kotlin con UniFFI ==="
cargo run -p balddog-android --bin uniffi-bindgen -- generate \
  --library "$SO_PATH" \
  --language kotlin \
  --out-dir "$APP_ROOT/app/src/main/java"

echo "Bindings y libbalddog_android.so generados correctamente."
