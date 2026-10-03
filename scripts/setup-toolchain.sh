#!/bin/bash
# Installs the JDK, Android SDK and Gradle into .toolchain/ (project-local).
# Safe to re-run: each step is skipped when its output already exists.
set -euo pipefail
. "$(dirname "$0")/env.sh"

JDK_URL="https://api.adoptium.net/v3/binary/latest/17/ga/mac/aarch64/jdk/hotspot/normal/eclipse"
CMDLINE_URL="https://dl.google.com/android/repository/commandlinetools-mac-13114758_latest.zip"
GRADLE_VERSION="8.14.3"
GRADLE_URL="https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip"
ANDROID_PLATFORM="platforms;android-36"
ANDROID_BUILD_TOOLS="build-tools;36.0.0"

mkdir -p "$CW_TOOLCHAIN/dl" "$GRADLE_USER_HOME"
cd "$CW_TOOLCHAIN"

if [ ! -x "$JAVA_HOME/bin/java" ]; then
  echo "[jdk] downloading"
  curl -fL --retry 3 -o dl/jdk.tar.gz "$JDK_URL"
  rm -rf jdk && mkdir jdk
  tar -xzf dl/jdk.tar.gz -C jdk --strip-components=1
fi
"$JAVA_HOME/bin/java" -version 2>&1 | head -1

if [ ! -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]; then
  echo "[android] downloading command-line tools"
  curl -fL --retry 3 -o dl/cmdline-tools.zip "$CMDLINE_URL"
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
  curl -fL --retry 3 -o dl/gradle.zip "$GRADLE_URL"
  rm -rf gradle "gradle-${GRADLE_VERSION}"
  unzip -q dl/gradle.zip
  mv "gradle-${GRADLE_VERSION}" gradle
fi
"$CW_TOOLCHAIN/gradle/bin/gradle" --version | grep -E "^Gradle"

rm -rf dl
du -sh "$CW_TOOLCHAIN" | awk '{print "toolchain size: " $1}'
