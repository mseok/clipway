#!/bin/bash
# Installs or updates Clipway for macOS (Apple Silicon, macOS 14 or later).
#
#   curl -fsSL https://github.com/mseok/clipway/releases/latest/download/install-mac.sh | bash
#   bash install-mac.sh ~/Downloads/Clipway-mac.zip   (installs a zip you already have)
#
# The app is downloaded with curl, so macOS does not quarantine it. Pairings and
# settings live in ~/Library/Application Support/Clipway and survive updates.
set -euo pipefail

BASE_URL="${CLIPWAY_BASE_URL:-https://github.com/mseok/clipway/releases/latest/download}"
LOCAL_ZIP="${1:-}"
APP="/Applications/Clipway.app"

fail() { echo "설치 실패: $1" >&2; exit 1; }

[ "$(uname -s)" = "Darwin" ] || fail "macOS에서만 설치할 수 있습니다."
[ "$(uname -m)" = "arm64" ] || fail "Apple Silicon Mac만 지원합니다."
[ "$(sw_vers -productVersion | cut -d. -f1)" -ge 14 ] || fail "macOS 14 이상이 필요합니다."

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

if [ -n "$LOCAL_ZIP" ]; then
  [ -f "$LOCAL_ZIP" ] || fail "파일을 찾을 수 없습니다: $LOCAL_ZIP"
  cp "$LOCAL_ZIP" "$TMP/Clipway-mac.zip"
else
  echo "Clipway를 내려받는 중..."
  curl -fL --progress-bar -o "$TMP/Clipway-mac.zip" "$BASE_URL/Clipway-mac.zip"
  # The checksum guards against a damaged or incomplete download. It comes from the same
  # release, so it does not replace trusting the release itself.
  curl -fsSL -o "$TMP/SHA256SUMS" "$BASE_URL/SHA256SUMS" || fail "체크섬 파일을 내려받지 못했습니다."
  expected="$(grep 'Clipway-mac.zip' "$TMP/SHA256SUMS" | cut -d' ' -f1)"
  actual="$(shasum -a 256 "$TMP/Clipway-mac.zip" | cut -d' ' -f1)"
  [ -n "$expected" ] && [ "$expected" = "$actual" ] || fail "내려받은 파일의 체크섬이 다릅니다."
fi

ditto -x -k "$TMP/Clipway-mac.zip" "$TMP/unpacked"
[ -d "$TMP/unpacked/Clipway.app" ] || fail "압축 파일에 앱이 없습니다."

pkill -x Clipway 2>/dev/null || true
# Wait for the old instance to exit; `open` would otherwise just activate it.
for _ in $(seq 1 25); do pgrep -x Clipway >/dev/null || break; sleep 0.2; done
rm -rf "$APP"
ditto "$TMP/unpacked/Clipway.app" "$APP"
xattr -dr com.apple.quarantine "$APP" 2>/dev/null || true
open "$APP"

cat <<'DONE'

설치가 끝났습니다. 메뉴바에 폰 모양 아이콘이 생겼습니다.
  1. 아이콘을 눌러 "새 폰 페어링"을 선택하면 QR 코드가 나옵니다.
  2. 폰의 Clipway 앱에서 "Mac 추가 (QR 스캔)"으로 스캔하세요.
  3. "로그인 시 실행"을 켜 두면 재시동 후에도 자동으로 실행됩니다.
DONE
