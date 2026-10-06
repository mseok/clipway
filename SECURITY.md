# Security

Clipway moves clipboard contents and SMS verification codes between your own devices,
so security reports are taken seriously.

## Reporting a problem

Please do not open a public issue for a vulnerability. Use GitHub's private reporting
instead: the repository's **Security** tab → **Report a vulnerability**. Include what an
attacker needs (same Wi-Fi, an app on the phone, …), what they gain, and steps to
reproduce.

## What Clipway defends against

- Anyone on the network without the pairing key: they cannot read, modify, replay or
  inject clipboard contents or codes. The handshake carries no device identifiers; the
  Mac does announce the Clipway service on the local network under its computer name.
- A leaked pairing QR code after pairing has finished: the QR key is used for one
  handshake, and both devices then switch to a key derived from that handshake.
- Hosts on the public internet: the Mac only accepts connections from private networks,
  Tailscale and its own subnets.
- Other apps and web pages on the phone: they cannot pair the phone with a device of
  their choosing without the user confirming it, and cannot make Clipway read the
  clipboard for them.
- Forged or malformed input from a paired device: sizes, timestamps, verification codes
  and pictures (type and dimensions) are checked on receipt, and a bad record ends that
  connection, not the app.
- Another app on the phone posing as a Mac: addresses of the phone itself are never
  dialled, a pairing request from outside the app cannot change a dialog that is already
  open, its button only works after a pause, and other apps cannot draw over it.

- A forged or replayed update. The Mac app installs an update only if its manifest
  (`release.json`) carries an Ed25519 signature from the release key built into the app,
  the downloaded archive matches the hash in that manifest, and the version is newer than
  the running one. On Android the system installer only accepts an APK signed with the
  same key as the installed app, and refuses downgrades.

## What it does not defend against

- Someone who obtains the pairing QR code while it is on screen and uses it before or
  during your own pairing, or who obtains the stored keys from a device.
- A person who is tricked into confirming a pairing with a device that is not theirs.
  Both devices show a four-digit check code for each pairing; if the Mac shows no new
  pairing, or a different code, unpair on the phone.
- Any app on the phone copying a `content://` picture reference: the watcher reads it
  with shell privileges and sends it to the paired Macs (never back to that app).
- A paired device, or anyone who can text the phone, changing what is on the clipboard:
  clipboard sync carries whatever is copied, and verification codes are copied on arrival
  (rate limited, digits only).
- An attacker on the same network making connections fail (denial of service).
- Malware already running as you on the Mac, or with root on the phone.
- A paired device that is itself compromised: paired devices trust each other with the
  clipboard.
- The first install. It is trusted as far as the GitHub release page is; the apps are not
  notarised by Apple or distributed through Google Play.
- Someone who holds the release signing key (for the Mac app) or the Android signing key
  and can also publish on the release page.

The protocol is described in `testvectors/generate.py`.
