# Notes for coding agents

Clipway syncs clipboard text, copied pictures and SMS verification codes between an
Android phone and a Mac. `mac/` is a SwiftPM menu bar app, `android/` a Kotlin app; they share the
protocol described in `testvectors/generate.py`.

- Installing Clipway for someone: follow [docs/agent-setup.md](docs/agent-setup.md).
- Build tools live in `.toolchain/` (created by `scripts/setup-toolchain.sh`); do not
  install global tools. No Xcode or Android Studio is needed.
- Tests: `mac/scripts/test.sh` and `scripts/build-android.sh` (runs the unit tests,
  then builds the APK). Both sides must keep passing `testvectors/handshake.json`;
  regenerate it with `uv run --with cryptography python testvectors/generate.py` only
  when the protocol changes.
- `tools/fake_phone.py` stands in for the phone when testing the Mac app.
- Never log clipboard contents or pairing keys. Existing logs print lengths only.
- Values from the other device are untrusted even after pairing: bound them before
  arithmetic (Swift traps on overflow), check sizes and types, and never let one bad
  record end the process.
- The Android signing key (`android/*.jks`, `android/keystore.properties`) and the release
  signing key (`release.key`) are not in git and must be kept: installed apps only accept
  updates signed with them. Never print them or pass them as command-line arguments.
- The version of both apps is the `VERSION` file. A release is the tag `v<VERSION>` with
  every file from `scripts/package-release.sh` as its assets; the apps look at
  `releases/latest/download/release.json` and fetch the rest from that version's tag.
- Testing updates without publishing: serve a `dist/` laid out as `latest/download/` and
  `download/v<version>/` and start the Mac app with `-ReleasesURL http://127.0.0.1:<port>`;
  for the phone build a debug APK with `-Pclipway.releasesUrl=...` and use `adb reverse`.
  `pkill -USR2 -x Clipway` checks and installs without the menu.
