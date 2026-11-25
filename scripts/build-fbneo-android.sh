#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
source "$SCRIPT_DIR/env.sh"

FBNEO_COMMIT="5f6bdff9451d5c3dedd3d992d7412660eb202cf4"
FBNEO_DIR="$APP_ROOT/.cache/FBNeo"
OUT_DIR="$APP_ROOT/app/src/main/jniLibs/arm64-v8a"
OUT_SO="$OUT_DIR/libfbneo_libretro.so"

TOOLCHAIN_BIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
CC_BIN="$TOOLCHAIN_BIN/aarch64-linux-android26-clang"
CXX_BIN="$TOOLCHAIN_BIN/aarch64-linux-android26-clang++"
AR_BIN="$TOOLCHAIN_BIN/llvm-ar"
STRIP_BIN="$TOOLCHAIN_BIN/llvm-strip"

if [ ! -x "$CC_BIN" ]; then
  echo "ERROR: No se encontró $CC_BIN. Ejecuta setup-toolchain.sh primero." >&2
  exit 1
fi

if [ ! -d "$FBNEO_DIR/.git" ]; then
  echo "Clonando FBNeo en $FBNEO_DIR..."
  mkdir -p "$APP_ROOT/.cache"
  git clone --depth 1 https://github.com/libretro/FBNeo.git "$FBNEO_DIR"
fi

CURRENT_COMMIT="$(git -C "$FBNEO_DIR" rev-parse HEAD)"
if [ "$CURRENT_COMMIT" != "$FBNEO_COMMIT" ]; then
  echo "Ajustando FBNeo al commit fijado $FBNEO_COMMIT..."
  git -C "$FBNEO_DIR" fetch --depth 1 origin "$FBNEO_COMMIT"
  git -C "$FBNEO_DIR" checkout -q "$FBNEO_COMMIT"
fi

echo "Compilando fbneo_libretro.so para arm64-v8a (API 26, flags deterministas)..."
NPROC="$(nproc 2>/dev/null || echo 4)"

make -C "$FBNEO_DIR/src/burner/libretro" clean >/dev/null 2>&1 || true
make -C "$FBNEO_DIR/src/burner/libretro" -j"$NPROC" \
  platform=unix \
  CC="$CC_BIN" \
  CXX="$CXX_BIN" \
  AR="$AR_BIN" \
  PLATFORM_DEFINES="-DANDROID -march=armv8-a -fno-fast-math -ffp-contract=off -gline-tables-only" \
  USE_CYCLONE=0 \
  HAVE_NEON=0 \
  EXTERNAL_ZLIB=0 \
  LDFLAGS="-shared -Wl,-no-undefined -Wl,-z,max-page-size=16384 -Wl,--version-script=$FBNEO_DIR/src/burner/libretro/link.T -lm -llog -static-libstdc++"

UNSTRIPPED_DIR="$APP_ROOT/.cache/unstripped-jniLibs/arm64-v8a"
mkdir -p "$OUT_DIR" "$UNSTRIPPED_DIR"
cp "$FBNEO_DIR/src/burner/libretro/fbneo_libretro.so" "$UNSTRIPPED_DIR/libfbneo_libretro.so"
cp "$FBNEO_DIR/src/burner/libretro/fbneo_libretro.so" "$OUT_SO"
"$STRIP_BIN" --strip-unneeded "$OUT_SO"

echo "=== Verificación ==="
file "$OUT_SO"
ls -lh "$OUT_SO"
