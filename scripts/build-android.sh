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
  # Created private from the start, and the password goes to keytool through the
  # environment so that it never appears in the process list.
  (
    umask 077
    CLIPWAY_KEY_PASS="$(openssl rand -hex 16)"
    export CLIPWAY_KEY_PASS
    keytool -genkeypair -keystore clipway.jks -alias clipway -keyalg RSA -keysize 2048 \
      -validity 10000 -storepass:env CLIPWAY_KEY_PASS -keypass:env CLIPWAY_KEY_PASS \
      -dname "CN=Clipway" 2>/dev/null
    printf 'storeFile=clipway.jks\nkeyAlias=clipway\npassword=%s\n' "$CLIPWAY_KEY_PASS" > keystore.properties
  )
fi

"$CW_TOOLCHAIN/gradle/bin/gradle" -q testReleaseUnitTest assembleRelease
APK="$CW_ROOT/android/app/build/outputs/apk/release/app-release.apk"
echo "built $APK"

if [ "${1:-}" = "--install" ]; then
  adb install -r "$APK"
fi
