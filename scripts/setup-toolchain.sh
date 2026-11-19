#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ANDROID_SDK_DIR="${ANDROID_HOME:-$HOME/Android/Sdk}"
NDK_VERSION="27.2.12479018"
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"

echo "=== [1/5] Verificando Java JDK 17/21 ==="
LOCAL_JDK_DIR="$HOME/Android/jdk-21"
if [ -d "/usr/lib/jvm/java-21-openjdk-amd64" ]; then
  JAVA_HOME_DIR="/usr/lib/jvm/java-21-openjdk-amd64"
elif [ -d "/usr/lib/jvm/java-17-openjdk-amd64" ]; then
  JAVA_HOME_DIR="/usr/lib/jvm/java-17-openjdk-amd64"
elif [ -x "$LOCAL_JDK_DIR/bin/java" ]; then
  JAVA_HOME_DIR="$LOCAL_JDK_DIR"
else
  echo "Descargando Eclipse Temurin JDK 21 en $LOCAL_JDK_DIR (sin sudo)..."
  mkdir -p "$HOME/Android"
  TMP_JDK="$(mktemp /tmp/temurin-jdk21-XXXXXX.tar.gz)"
  curl -fSL "https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jdk/hotspot/normal/eclipse" -o "$TMP_JDK"
  mkdir -p "$LOCAL_JDK_DIR"
  tar -xzf "$TMP_JDK" -C "$LOCAL_JDK_DIR" --strip-components=1
  rm -f "$TMP_JDK"
  JAVA_HOME_DIR="$LOCAL_JDK_DIR"
fi

export JAVA_HOME="$JAVA_HOME_DIR"
export PATH="$JAVA_HOME/bin:$PATH"
echo "Usando JAVA_HOME=$JAVA_HOME"
java -version

echo "=== [2/5] Verificando Android Command Line Tools ==="
CMDLINE_BIN="$ANDROID_SDK_DIR/cmdline-tools/latest/bin"
if [ ! -x "$CMDLINE_BIN/sdkmanager" ]; then
  echo "Descargando Android Command Line Tools en $ANDROID_SDK_DIR..."
  mkdir -p "$ANDROID_SDK_DIR/cmdline-tools"
  TMP_ZIP="$(mktemp /tmp/cmdline-tools-XXXXXX.zip)"
  curl -fSL "$CMDLINE_TOOLS_URL" -o "$TMP_ZIP"
  TMP_EXTRACT="$(mktemp -d /tmp/cmdline-extract-XXXXXX)"
  unzip -q "$TMP_ZIP" -d "$TMP_EXTRACT"
  rm -rf "$ANDROID_SDK_DIR/cmdline-tools/latest"
  mv "$TMP_EXTRACT/cmdline-tools" "$ANDROID_SDK_DIR/cmdline-tools/latest"
  rm -rf "$TMP_ZIP" "$TMP_EXTRACT"
fi

export ANDROID_HOME="$ANDROID_SDK_DIR"
export ANDROID_SDK_ROOT="$ANDROID_SDK_DIR"
export PATH="$CMDLINE_BIN:$ANDROID_SDK_DIR/platform-tools:$PATH"

echo "=== [3/5] Instalando SDK Platform 34, Build-Tools 34 y NDK $NDK_VERSION ==="
yes | sdkmanager --licenses >/dev/null 2>&1 || true
sdkmanager --install \
  "platform-tools" \
  "platforms;android-34" \
  "build-tools;34.0.0" \
  "ndk;$NDK_VERSION"

export ANDROID_NDK_HOME="$ANDROID_SDK_DIR/ndk/$NDK_VERSION"

echo "=== [4/5] Configurando Rust target aarch64-linux-android y cargo-ndk ==="
rustup target add aarch64-linux-android
if ! command -v cargo-ndk >/dev/null 2>&1; then
  cargo install cargo-ndk --locked
fi

echo "=== [5/5] Generando $SCRIPT_DIR/env.sh ==="
cat > "$SCRIPT_DIR/env.sh" <<EOF
# Generado por setup-toolchain.sh
export JAVA_HOME="$JAVA_HOME_DIR"
export ANDROID_HOME="$ANDROID_SDK_DIR"
export ANDROID_SDK_ROOT="$ANDROID_SDK_DIR"
export ANDROID_NDK_HOME="$ANDROID_SDK_DIR/ndk/$NDK_VERSION"
export PATH="\$JAVA_HOME/bin:\$ANDROID_HOME/cmdline-tools/latest/bin:\$ANDROID_HOME/platform-tools:\$HOME/.cargo/bin:\$PATH"
EOF

echo "Toolchain listo. Puedes cargarlo con: source $SCRIPT_DIR/env.sh"
