# Notes for coding agents

Clipway syncs clipboard text and SMS verification codes between an Android phone and
a Mac. `mac/` is a SwiftPM menu bar app, `android/` a Kotlin app; they share the
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
- The Android signing key (`android/*.jks`, `android/keystore.properties`) is not in
  git and must be kept: updates have to be signed with the same key.
