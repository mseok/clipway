#!/bin/bash
# Builds both apps and collects the release files in dist/:
#   Clipway.dmg, Clipway-mac.zip, Clipway-android.apk, install-mac.sh, SHA256SUMS
#
# Upload them as the assets of a GitHub release; install-mac.sh downloads from
# releases/latest of the repository.
set -euo pipefail
. "$(dirname "$0")/env.sh"
cd "$CW_ROOT"

mac/scripts/build-app.sh
scripts/build-android.sh

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

VERSION="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' mac/Info.plist)"
echo "release $VERSION in $CW_ROOT/dist:"
ls -lh dist | awk 'NR>1 {print "  " $5, $9}'
