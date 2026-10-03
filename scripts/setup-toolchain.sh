#!/bin/bash
# Installs the JDK, Android SDK and Gradle into .toolchain/ (project-local).
# Safe to re-run: each step is skipped when its output already exists.
set -euo pipefail
. "$(dirname "$0")/env.sh"

# Exact versions with their published SHA-256, so a changed download is refused.
JDK_URL="https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.20.1%2B1/OpenJDK17U-jdk_aarch64_mac_hotspot_17.0.20.1_1.tar.gz"
JDK_SHA256="196d13ba5f10414bef7f6a05a9b3f00edacb18ebacef2b99485db9e2ee18f0e8"
CMDLINE_URL="https://dl.google.com/android/repository/commandlinetools-mac-13114758_latest.zip"
CMDLINE_SHA256="5673201e6f3869f418eeed3b5cb6c4be7401502bd0aae1b12a29d164d647a54e"
GRADLE_VERSION="8.14.3"
GRADLE_URL="https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip"
GRADLE_SHA256="bd71102213493060956ec229d946beee57158dbd89d0e62b91bca0fa2c5f3531"
ANDROID_PLATFORM="platforms;android-36"
ANDROID_BUILD_TOOLS="build-tools;36.0.0"

mkdir -p "$CW_TOOLCHAIN/dl" "$GRADLE_USER_HOME"
cd "$CW_TOOLCHAIN"

fetch() {  # fetch <url> <sha256> <file>
  curl -fL --retry 3 -o "$3" "$1"
  if [ "$(shasum -a 256 "$3" | cut -d' ' -f1)" != "$2" ]; then
    rm -f "$3"
    echo "checksum mismatch for $1" >&2
    exit 1
  fi
}

if [ ! -x "$JAVA_HOME/bin/java" ]; then
  echo "[jdk] downloading"
  fetch "$JDK_URL" "$JDK_SHA256" dl/jdk.tar.gz
  rm -rf jdk && mkdir jdk
  tar -xzf dl/jdk.tar.gz -C jdk --strip-components=1
fi
"$JAVA_HOME/bin/java" -version 2>&1 | head -1

if [ ! -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]; then
  echo "[android] downloading command-line tools"
  fetch "$CMDLINE_URL" "$CMDLINE_SHA256" dl/cmdline-tools.zip
  rm -rf "$ANDROID_HOME/cmdline-tools" dl/cmdline-tools
  mkdir -p "$ANDROID_HOME/cmdline-tools"
  unzip -q dl/cmdline-tools.zip -d dl
  mv dl/cmdline-tools "$ANDROID_HOME/cmdline-tools/latest"
fi

if [ ! -x "$ANDROID_HOME/platform-tools/adb" ] || [ ! -d "$ANDROID_HOME/platforms/android-36" ]; then
  echo "[android] installing SDK packages"
  yes | sdkmanager --licenses >/dev/null 2>&1 || true
  sdkmanager "platform-tools" "$ANDROID_PLATFORM" "$ANDROID_BUILD_TOOLS" | grep -v "^\[" || true
fi
"$ANDROID_HOME/platform-tools/adb" version | head -1

if [ ! -x "$CW_TOOLCHAIN/gradle/bin/gradle" ]; then
  echo "[gradle] downloading $GRADLE_VERSION"
  fetch "$GRADLE_URL" "$GRADLE_SHA256" dl/gradle.zip
  rm -rf gradle "gradle-${GRADLE_VERSION}"
  unzip -q dl/gradle.zip
  mv "gradle-${GRADLE_VERSION}" gradle
fi
"$CW_TOOLCHAIN/gradle/bin/gradle" --version | grep -E "^Gradle"

rm -rf dl
du -sh "$CW_TOOLCHAIN" | awk '{print "toolchain size: " $1}'
