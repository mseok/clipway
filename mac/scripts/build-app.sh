#!/bin/bash
# Builds Clipway.app with SwiftPM (no Xcode needed) and ad-hoc signs it.
#   scripts/build-app.sh            -> mac/dist/Clipway.app
#   scripts/build-app.sh --install  -> also copies to /Applications and relaunches
set -euo pipefail
cd "$(dirname "$0")/.."

swift build -c release --product Clipway
BIN="$(swift build -c release --show-bin-path)/Clipway"
APP="dist/Clipway.app"

rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS"
cp "$BIN" "$APP/Contents/MacOS/Clipway"
cp Info.plist "$APP/Contents/Info.plist"

# The icon is drawn once, in assets/icon.svg; sips renders the sizes macOS wants.
ICONSET="$(mktemp -d)/AppIcon.iconset"
mkdir -p "$ICONSET" "$APP/Contents/Resources"
for size in 16 32 128 256 512; do
  sips -s format png -z $size $size ../assets/icon.svg --out "$ICONSET/icon_${size}x${size}.png" >/dev/null
  sips -s format png -z $((size * 2)) $((size * 2)) ../assets/icon.svg --out "$ICONSET/icon_${size}x${size}@2x.png" >/dev/null
done
iconutil -c icns "$ICONSET" -o "$APP/Contents/Resources/AppIcon.icns"
rm -rf "$(dirname "$ICONSET")"

# The version comes from the VERSION file at the top of the repository, like the APK's.
VERSION="$(tr -d '[:space:]' < ../VERSION)"
IFS=. read -r MAJOR MINOR PATCH <<< "$VERSION"
/usr/libexec/PlistBuddy -c "Set :CFBundleShortVersionString $VERSION" \
  -c "Set :CFBundleVersion $((MAJOR * 1000000 + MINOR * 1000 + PATCH))" "$APP/Contents/Info.plist"
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
