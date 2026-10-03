#!/bin/bash
# Runs the Swift Testing suite with Command Line Tools only (no Xcode).
# CLT ships Testing.framework outside SwiftPM's default search path, and its
# Foundation cross-import overlay has no module files, so the overlay is disabled.
set -euo pipefail
cd "$(dirname "$0")/.."
F=/Library/Developer/CommandLineTools/Library/Developer/Frameworks
swift test \
  -Xswiftc -F -Xswiftc "$F" \
  -Xswiftc -Xfrontend -Xswiftc -disable-cross-import-overlays \
  -Xlinker -F -Xlinker "$F" -Xlinker -rpath -Xlinker "$F" "$@"
