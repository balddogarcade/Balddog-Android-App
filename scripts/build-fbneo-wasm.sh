#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
FBNEO_COMMIT="5f6bdff9451d5c3dedd3d992d7412660eb202cf4"
FBNEO_DIR="$APP_ROOT/.cache/FBNeo"
WEB_WASM_DIR="/media/carlos/DATA1/DEV/Balddog-Main-Webpage/public/wasm"

if [ -f "/tmp/emsdk/emsdk_env.sh" ]; then
  source "/tmp/emsdk/emsdk_env.sh" >/dev/null
fi

if ! command -v emcc >/dev/null 2>&1; then
  echo "ERROR: emcc no encontrado. Instala emsdk en /tmp/emsdk." >&2
  exit 1
fi

if [ ! -d "$FBNEO_DIR/.git" ]; then
  mkdir -p "$APP_ROOT/.cache"
  git clone --depth 1 https://github.com/libretro/FBNeo.git "$FBNEO_DIR"
fi

CURRENT_COMMIT="$(git -C "$FBNEO_DIR" rev-parse HEAD)"
if [ "$CURRENT_COMMIT" != "$FBNEO_COMMIT" ]; then
  git -C "$FBNEO_DIR" fetch --depth 1 origin "$FBNEO_COMMIT"
  git -C "$FBNEO_DIR" checkout -q "$FBNEO_COMMIT"
fi

echo "Compilando FBNeo Libretro como librería estática Emscripten..."
NPROC="$(nproc 2>/dev/null || echo 4)"
emmake make -C "$FBNEO_DIR/src/burner/libretro" -j"$NPROC" \
  platform=emscripten \
  STATIC_LINKING=1 \
  EXTERNAL_ZLIB=0

LIB_BC="$FBNEO_DIR/src/burner/libretro/fbneo_libretro_emscripten.bc"
if [ ! -f "$LIB_BC" ]; then
  LIB_BC="$FBNEO_DIR/src/burner/libretro/fbneo_libretro_emscripten.a"
fi

cp "$LIB_BC" /tmp/libfbneo_emscripten.a

echo "Compilando host C y libretro-common VFS..."
emcc -O2 -c "$APP_ROOT/wasm/libretro_wasm_host.c" -o /tmp/libretro_wasm_host.o
LIBRETRO_INC="$FBNEO_DIR/src/burner/libretro/libretro-common/include"
LIBRETRO_COMMON="$FBNEO_DIR/src/burner/libretro/libretro-common"
emcc -O2 -c -I"$LIBRETRO_INC" "$LIBRETRO_COMMON/streams/file_stream.c" -o /tmp/file_stream.o
emcc -O2 -c -I"$LIBRETRO_INC" "$LIBRETRO_COMMON/streams/file_stream_transforms.c" -o /tmp/file_stream_transforms.o
emcc -O2 -c -I"$LIBRETRO_INC" "$LIBRETRO_COMMON/vfs/vfs_implementation.c" -o /tmp/vfs_implementation.o
emcc -O2 -c -I"$LIBRETRO_INC" "$LIBRETRO_COMMON/string/stdstring.c" -o /tmp/stdstring.o
emcc -O2 -c -I"$LIBRETRO_INC" "$LIBRETRO_COMMON/file/retro_dirent.c" -o /tmp/retro_dirent.o
emcc -O2 -c -I"$LIBRETRO_INC" "$LIBRETRO_COMMON/file/file_path.c" -o /tmp/file_path.o
emcc -O2 -c -I"$LIBRETRO_INC" "$LIBRETRO_COMMON/file/file_path_io.c" -o /tmp/file_path_io.o
emcc -O2 -c -I"$LIBRETRO_INC" "$LIBRETRO_COMMON/compat/compat_strl.c" -o /tmp/compat_strl.o

mkdir -p "$WEB_WASM_DIR"
echo "Enlazando fbneo.js + fbneo.wasm..."
em++ -O2 \
  /tmp/libretro_wasm_host.o \
  /tmp/libfbneo_emscripten.a \
  /tmp/file_stream.o \
  /tmp/file_stream_transforms.o \
  /tmp/vfs_implementation.o \
  /tmp/stdstring.o \
  /tmp/retro_dirent.o \
  /tmp/file_path.o \
  /tmp/file_path_io.o \
  /tmp/compat_strl.o \
  -o "$WEB_WASM_DIR/fbneo.js" \
  -s MODULARIZE=1 \
  -s EXPORT_NAME="createFbneoModule" \
  -s ALLOW_MEMORY_GROWTH=1 \
  -s INITIAL_MEMORY=134217728 \
  -s FORCE_FILESYSTEM=1 \
  -s EXPORTED_RUNTIME_METHODS='["FS","ccall","cwrap","HEAPU8","HEAP16"]' \
  -s ENVIRONMENT='web'

ls -lh "$WEB_WASM_DIR/fbneo.js" "$WEB_WASM_DIR/fbneo.wasm"
