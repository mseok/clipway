#!/bin/bash
# Sets up Clipway on an Android phone that is connected over adb, and pairs it
# with the Clipway app running on this Mac. Safe to re-run.
#
#   scripts/setup-phone.sh [path to Clipway-android.apk]
#
# Without an argument the APK built by scripts/build-android.sh (or dist/) is used.
# With several devices attached, choose one with ANDROID_SERIAL.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
[ -f "$ROOT/scripts/env.sh" ] && [ -d "$ROOT/.toolchain" ] && . "$ROOT/scripts/env.sh"

PKG=dev.mseok.clipway
SHIZUKU=moe.shizuku.privileged.api
APK="${1:-}"
for candidate in "$ROOT/dist/Clipway-android.apk" "$ROOT/android/app/build/outputs/apk/release/app-release.apk"; do
  [ -z "$APK" ] && [ -f "$candidate" ] && APK="$candidate"
done
[ -f "$APK" ] || { echo "APK not found; pass its path as the first argument" >&2; exit 1; }
command -v adb >/dev/null || { echo "adb not found (install Android platform-tools)" >&2; exit 1; }
[ "$(adb get-state 2>/dev/null)" = "device" ] || { echo "no phone connected over adb" >&2; exit 1; }

echo "[1/5] installing $(basename "$APK")"
adb install -r "$APK" | tail -1

echo "[2/5] granting permissions"
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS
# Installed through adb, so the SMS permission is not held back by "restricted settings".
adb shell pm grant $PKG android.permission.RECEIVE_SMS
adb shell dumpsys deviceidle whitelist +$PKG >/dev/null

echo "[3/5] Shizuku (automatic copy detection)"
if adb shell pm path $SHIZUKU >/dev/null 2>&1; then
  adb shell dumpsys deviceidle whitelist +$SHIZUKU >/dev/null
  if adb shell 'ps -A -o NAME' | grep -q shizuku_server; then
    echo "      already running"
  elif ! adb shell pm list packages -i $SHIZUKU | grep -q 'installer=com.android.vending'; then
    # Its starter runs with shell privileges, so only a copy from the Play Store is started here.
    echo "      installed from outside the Play Store: start it yourself from the Shizuku app"
  else
    # Started from a computer, Shizuku stops at the next reboot. Starting it once from the
    # Shizuku app ("무선 디버깅으로 시작") makes it come back by itself after a reboot.
    base="$(adb shell pm path $SHIZUKU | head -1 | tr -d '\r' | sed 's/^package://; s/base\.apk$//')"
    adb shell "${base}lib/arm64/libshizuku.so" | tail -1
  fi
else
  echo "      not installed: install Shizuku from the Play Store to enable automatic detection"
fi

echo "[4/5] starting the app"
adb shell am start -n $PKG/.ui.MainActivity >/dev/null
sleep 2

echo "[5/5] pairing with this Mac"
LINK_FILE="$HOME/Library/Application Support/Clipway/pending-pairing-link.txt"
if pgrep -x Clipway >/dev/null; then
  pkill -USR1 -x Clipway
  for _ in 1 2 3 4 5; do [ -f "$LINK_FILE" ] && break; sleep 0.5; done
  if [ -f "$LINK_FILE" ]; then
    # The link holds the pairing key. It goes to the phone on stdin so that it never shows
    # up in this Mac's process list; the Mac app only emits unreserved URL characters.
    printf "am start -a android.intent.action.VIEW -d '%s' -p %s\n" "$(cat "$LINK_FILE")" "$PKG" | adb shell >/dev/null 2>&1
    echo "      the phone now asks whether to pair: tap \"페어링\" on the phone (waiting up to 60 s)"
    for _ in $(seq 1 60); do [ -f "$LINK_FILE" ] || break; sleep 1; done
    [ -f "$LINK_FILE" ] && echo "      not paired: confirm on the phone, and check that both are on the same Wi-Fi" || echo "      paired"
  else
    echo "      the Mac app did not produce a pairing link"
  fi
else
  echo "      Clipway is not running on this Mac; pair later with the QR code"
fi

cat <<'DONE'

On the phone, in Clipway:
  - "Shizuku 권한 허용" -> "항상 허용"  (only if the app shows that button)
The copy detection line should then read "켜짐".
DONE
