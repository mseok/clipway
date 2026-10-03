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
- Forged or malformed input from a paired device: sizes, timestamps and verification
  codes are validated on receipt.

## What it does not defend against

- Someone who obtains the pairing QR code while it is on screen and uses it before or
  during your own pairing, or who obtains the stored keys from a device.
- A paired device, or anyone who can text the phone, changing what is on the clipboard:
  clipboard sync carries whatever is copied, and verification codes are copied on arrival
  (rate limited, digits only).
- An attacker on the same network making connections fail (denial of service).
- Malware already running as you on the Mac, or with root on the phone.
- A paired device that is itself compromised: paired devices trust each other with the
  clipboard.

The protocol is described in `testvectors/generate.py`.
