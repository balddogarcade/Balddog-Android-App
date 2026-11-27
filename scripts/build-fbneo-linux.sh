#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

FBNEO_COMMIT="5f6bdff9451d5c3dedd3d992d7412660eb202cf4"
FBNEO_DIR="${FBNEO_DIR:-$APP_ROOT/.cache/FBNeo}"
OUT_DIR="${OUT_DIR:-$APP_ROOT/.cache/fbneo-linux}"
OUT_SO="${OUT_SO:-$OUT_DIR/fbneo_libretro.so}"

if [ ! -d "$FBNEO_DIR/.git" ]; then
  echo "Clonando FBNeo en $FBNEO_DIR..."
  mkdir -p "$(dirname "$FBNEO_DIR")"
  git clone --depth 1 https://github.com/libretro/FBNeo.git "$FBNEO_DIR"
fi

CURRENT_COMMIT="$(git -C "$FBNEO_DIR" rev-parse HEAD)"
if [ "$CURRENT_COMMIT" != "$FBNEO_COMMIT" ]; then
  echo "Ajustando FBNeo al commit fijado $FBNEO_COMMIT..."
  git -C "$FBNEO_DIR" fetch --depth 1 origin "$FBNEO_COMMIT"
  git -C "$FBNEO_DIR" checkout -q "$FBNEO_COMMIT"
fi

echo "Compilando fbneo_libretro.so para Linux x86_64 (flags deterministas)..."
NPROC="$(nproc 2>/dev/null || echo 4)"

make -C "$FBNEO_DIR/src/burner/libretro" clean >/dev/null 2>&1 || true
make -C "$FBNEO_DIR/src/burner/libretro" -j"$NPROC" \
  platform=unix \
  PLATFORM_DEFINES="-fno-fast-math -ffp-contract=off" \
  USE_CYCLONE=0 \
  HAVE_NEON=0 \
  EXTERNAL_ZLIB=0

mkdir -p "$(dirname "$OUT_SO")"
cp "$FBNEO_DIR/src/burner/libretro/fbneo_libretro.so" "$OUT_SO"

echo "=== Verificación ==="
file "$OUT_SO"
ls -lh "$OUT_SO"
