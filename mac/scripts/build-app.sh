#!/bin/bash
# Builds Clipway.app with SwiftPM (no Xcode needed) and ad-hoc signs it.
#   scripts/build-app.sh            -> mac/dist/Clipway.app
#   scripts/build-app.sh --install  -> also copies to /Applications and relaunches
set -euo pipefail
cd "$(dirname "$0")/.."

swift build -c release
BIN="$(swift build -c release --show-bin-path)/Clipway"
APP="dist/Clipway.app"

rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS"
cp "$BIN" "$APP/Contents/MacOS/Clipway"
cp Info.plist "$APP/Contents/Info.plist"
codesign --force --sign - "$APP"
echo "built $APP"

if [ "${1:-}" = "--install" ]; then
  pkill -x Clipway 2>/dev/null || true
  # Wait for the old instance to exit; `open` would otherwise just activate it.
  for _ in $(seq 1 25); do pgrep -x Clipway >/dev/null || break; sleep 0.2; done
  rm -rf /Applications/Clipway.app
  cp -R "$APP" /Applications/Clipway.app
  open /Applications/Clipway.app
  echo "installed and launched /Applications/Clipway.app"
fi
