# Setting up Clipway — guide for AI agents

This guide is for an AI coding agent (Claude Code, Codex, …) that a person asked to
install Clipway. It lists exact commands, what only the person can do, and how to
verify each step. Work on the Mac that should run Clipway; the phone is reached over adb.

## What gets installed

| Part | Details |
|---|---|
| Mac app | `/Applications/Clipway.app`, menu bar only, Apple Silicon, macOS 14+. Listens on TCP 47823 and advertises `_clipway._tcp`. State: `~/Library/Application Support/Clipway/state.json` |
| Android app | package `dev.mseok.clipway`, Android 13+. Needs notification and SMS permissions and a battery-optimisation exemption |
| Shizuku | package `moe.shizuku.privileged.api`. Optional, but without it copies on the phone are only sent manually |

## Ask the person first

1. To unlock the phone and keep it on the same Wi-Fi as the Mac.
2. To enable developer options (Settings → About phone → Software information → tap
   "Build number" seven times) and one of:
   - **USB debugging**, then plug the phone in and accept the prompt on the phone, or
   - **Wireless debugging**, then open "Pair device with pairing code" and tell you the
     six-digit code, the pairing `IP:port`, and the `IP:port` on the main wireless
     debugging screen. The pairing screen must stay open until you have paired.
3. On Samsung phones, to turn off Settings → Security and privacy → Auto Blocker if
   installs or USB commands are refused.

## 1. Mac app

```sh
curl -fsSL https://github.com/mseok/clipway/releases/latest/download/install-mac.sh | bash
```

or, from a checkout: `mac/scripts/build-app.sh --install` (needs only the Xcode
Command Line Tools).

Verify: `pgrep -x Clipway` prints a pid and `lsof -iTCP:47823 -sTCP:LISTEN -P -n`
shows the listener.

## 2. Connect to the phone

```sh
adb pair <pairing ip:port> <code>      # wireless only, once
adb connect <ip:port from the main wireless debugging screen>
adb devices                             # must list the phone as "device"
```

`adb` comes from Android platform-tools (`brew install android-platform-tools`, or
`scripts/setup-toolchain.sh` in a checkout, then `. scripts/env.sh`).

**"No route to host" although `ping <phone ip>` works.** macOS Local Network privacy
is blocking adb. This is a privacy setting of the person's Mac, so tell them and let them
decide: they can allow adb under System Settings → Privacy & Security → Local Network,
plug the phone in over USB instead, or agree that you relay through the system Python:

```sh
/usr/bin/python3 tools/lan_relay.py <port>:<phone ip>:<port> &
adb connect 127.0.0.1:<port>
```

The wireless debugging port changes whenever wireless debugging restarts. Find the
current one with `dns-sd -B _adb-tls-connect._tcp` followed by
`dns-sd -L <instance> _adb-tls-connect._tcp`.

## 3. Install, grant and pair

```sh
scripts/setup-phone.sh [path/to/Clipway-android.apk]
```

It installs the APK, grants the notification and SMS permissions, exempts the app
from battery optimisation, starts Shizuku if it is installed but not running,
launches the app and pairs it with the Clipway app running on this Mac.

Pairing by hand, without the QR code:

```sh
pkill -USR1 -x Clipway
link="$(cat "$HOME/Library/Application Support/Clipway/pending-pairing-link.txt")"
printf "am start -a android.intent.action.VIEW -d '%s' -p dev.mseok.clipway\n" "$link" | adb shell
```

Send the command on stdin as shown, never as an argument: arguments are visible to every
user of the Mac in the process list.

The phone then shows a dialog asking whether to pair with that Mac. The person has to
tap "페어링": pairing is never completed without that confirmation, because a pairing
link can be sent to the phone by any app or web page.

The link contains a one-time pairing key: do not print or log it. The file disappears
once the phone has paired, and after ten minutes otherwise; the two devices then keep a
different key that never left them. A link from outside the app cannot replace a Mac
that is already paired: unpair it on the phone first ("해제").

## 4. Shizuku (automatic copy detection)

1. The person installs Shizuku from the Play Store. `setup-phone.sh` only starts a copy
   that came from the Play Store, because its starter runs with shell privileges.
2. In Clipway on the phone, tap "Shizuku 권한 허용", then "항상 허용".
   The app must then show "복사 자동 감지 … 켜짐".
3. For Shizuku to come back after a reboot it has to be started once from the Shizuku
   app itself: "무선 디버깅으로 시작" → "페어링" (enter the system pairing code in
   Shizuku's notification) → "시작". Starting it restarts wireless debugging, so the
   adb port changes and you must reconnect. After that Shizuku starts by itself at
   boot when the phone is on Wi-Fi.

Plain navigation on the phone can be driven with `adb shell uiautomator dump` plus
`adb shell input tap`, if the person asked you to. Do not tap anything that asks for the
person's consent: the Clipway pairing dialog, the Shizuku permission dialog, Android
permission prompts. Those exist so that a person decides; ask them to tap. Do not read
the notification shade: it shows other apps' notifications.

## 5. Verify

```sh
# Mac -> phone
printf 'clipway-test' | pbcopy
adb logcat -d -s Clipway:V | tail -2          # expect "clip from Mac: 12 chars"

# phone -> Mac (manual path)
adb shell am start -a android.intent.action.SEND -t text/plain \
  --es android.intent.extra.TEXT clipway-test-2 -n dev.mseok.clipway/.ShareToMacActivity
pbpaste                                        # expect clipway-test-2
```

Automatic detection is verified by copying something in another app on the phone
while Clipway is in the background: `adb logcat -s ClipwayWatcher:V Clipway:V` shows
"clipboard changed" and "local copy: N chars -> 1 of 1 Mac(s)". The apps log lengths
only, never clipboard contents. Mac-side log:
`/usr/bin/log stream --level info --predicate 'subsystem == "dev.mseok.clipway"'`.

Verification codes cannot be simulated; the person has to receive a real SMS. The
Mac shows a banner at the top right and copies the code.

## Updating

Both apps check for a newer release once a day and tell the person. To update the Mac
app from a script: `pkill -USR2 -x Clipway` (it downloads the newest release, verifies its
signature, replaces `/Applications/Clipway.app` and relaunches), or re-run the install
command from step 1. The phone app updates from its own screen ("업데이트"); the system
asks the person to confirm the install, so do not tap that for them.

## Rules while you work

- Save the Mac clipboard before testing and restore it afterwards; tests overwrite
  the clipboard on both devices.
- Do not print clipboard contents, pairing links or keys, and do not pass keys as
  command-line arguments (`tools/fake_phone.py` reads `CLIPWAY_PSK` from the environment).
- Ask before uninstalling other apps, rebooting the phone or changing phone settings.
- Things only the person can do: unlock the phone, answer PIN or biometric prompts,
  receive an SMS, click "Open Anyway" if macOS blocked an app downloaded in a browser.

## Uninstall

```sh
pkill -x Clipway; rm -rf /Applications/Clipway.app "$HOME/Library/Application Support/Clipway"
adb uninstall dev.mseok.clipway
```
