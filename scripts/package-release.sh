#!/bin/bash
# Builds both apps and collects the release files in dist/:
#   Clipway.dmg, Clipway-mac.zip, Clipway-android.apk, install-mac.sh, SHA256SUMS,
#   release.json, release.json.sig
#
# Upload them as the assets of a GitHub release tagged v<VERSION>. install-mac.sh and the
# apps' update check download from releases/latest of the repository; the apps then fetch
# the files of the version that release.json names.
set -euo pipefail
. "$(dirname "$0")/env.sh"
cd "$CW_ROOT"

# release.json is signed with this key, and installed Mac apps only accept updates signed
# with it. It stays out of git; keep it as carefully as the Android keystore.
[ -f release.key ] || {
  echo "release.key is missing. Restore it from your backup. For a fork, create one with" >&2
  echo "  ReleaseTool keygen release.key" >&2
  echo "and put the printed public key into mac/Sources/BridgeCore/Release.swift." >&2
  exit 1
}

mac/scripts/build-app.sh
scripts/build-android.sh
(cd mac && swift build -c release --product ReleaseTool)
TOOL="$(cd mac && swift build -c release --show-bin-path)/ReleaseTool"

rm -rf dist && mkdir dist
ditto -c -k --keepParent mac/dist/Clipway.app dist/Clipway-mac.zip

# Disk image for drag-and-drop installs: the app next to a link to /Applications.
STAGE="$(mktemp -d)"
cp -R mac/dist/Clipway.app "$STAGE/"
ln -s /Applications "$STAGE/Applications"
hdiutil create -quiet -volname Clipway -srcfolder "$STAGE" -ov -format UDZO dist/Clipway.dmg
rm -rf "$STAGE"

cp android/app/build/outputs/apk/release/app-release.apk dist/Clipway-android.apk
cp install-mac.sh dist/install-mac.sh
(cd dist && shasum -a 256 Clipway.dmg Clipway-mac.zip Clipway-android.apk > SHA256SUMS)

VERSION="$(tr -d '[:space:]' < VERSION)"
asset() {
  printf '{"file":"%s","sha256":"%s","size":%s}' \
    "$1" "$(shasum -a 256 "dist/$1" | cut -d' ' -f1)" "$(stat -f%z "dist/$1")"
}
printf '{"version":"%s","mac":%s,"android":%s}\n' \
  "$VERSION" "$(asset Clipway-mac.zip)" "$(asset Clipway-android.apk)" > dist/release.json
"$TOOL" sign release.key dist/release.json
"$TOOL" verify dist/release.json

echo "release $VERSION in $CW_ROOT/dist:"
ls -lh dist | awk 'NR>1 {print "  " $5, $9}'
