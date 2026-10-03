#!/bin/bash
# Runs the unit tests and builds the signed release APK with the project-local toolchain.
#   scripts/build-android.sh            -> android/app/build/outputs/apk/release/app-release.apk
#   scripts/build-android.sh --install  -> also installs it on the device connected over adb
set -euo pipefail
. "$(dirname "$0")/env.sh"
cd "$CW_ROOT/android"

# The signing key is created once and stays out of git. Keep android/clipway.jks and
# android/keystore.properties: an update can only replace the app if it is signed with them.
if [ ! -f keystore.properties ]; then
  PASS="$(openssl rand -hex 16)"
  keytool -genkeypair -keystore clipway.jks -alias clipway -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass "$PASS" -keypass "$PASS" -dname "CN=Clipway" 2>/dev/null
  printf 'storeFile=clipway.jks\nkeyAlias=clipway\npassword=%s\n' "$PASS" > keystore.properties
  chmod 600 keystore.properties clipway.jks
fi

"$CW_TOOLCHAIN/gradle/bin/gradle" -q testReleaseUnitTest assembleRelease
APK="$CW_ROOT/android/app/build/outputs/apk/release/app-release.apk"
echo "built $APK"

if [ "${1:-}" = "--install" ]; then
  adb install -r "$APK"
fi
