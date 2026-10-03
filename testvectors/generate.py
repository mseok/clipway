"""Generates handshake.json from an independent implementation of protocol v1.

Run: uv run --with cryptography python testvectors/generate.py

Protocol v1
  frame      = 4-byte big-endian length || payload
  phoneHello = JSON {"v":1,"eph":b64(X25519 pub)}   (plaintext, at most 1024 bytes)
  macHello   = JSON {"v":1,"eph":b64(X25519 pub)}   (plaintext, at most 1024 bytes)
  okm        = HKDF-SHA256(ikm = X25519 shared secret, salt = psk,
                           info = "clipway-v1" || SHA256(phoneHello || macHello), L = 96)
  phoneToMac = okm[0:32], macToPhone = okm[32:64], pairingKey = okm[64:96]
  record     = AES-256-GCM(key, nonce = 00000000 || counter_be64, aad = "") -> ciphertext || tag

The plaintext hellos name neither device. The phone's first record is
{"t":"hello","id":<phone id>,"name":..,"ts":..}; the Mac finds the pairing by trying its
stored keys on that record, then answers {"t":"hello","name":..,"ts":..,"now":..}.
Both hellos carry "now", the sender's clock in ms; each side adds (own now - peer now)
to every timestamp it receives, because "newest copy wins" compares across two clocks.

Pairing: the QR code carries a one-time psk. When a handshake is authenticated with
it, both sides store pairingKey as the long-term psk for that phone and forget the QR
key, so a copy of the QR code is worthless afterwards.

Pictures: image {mime, size, sensitive, ts}, either way, is followed by chunk records
until size bytes have arrived (at most 20 MiB). A chunk record's plaintext is a zero
byte followed by up to 256 KiB of the picture. JSON records start with "{", so the two
kinds cannot be confused and other records (ping, otp) may come between chunks.

Pairing check code: both devices show int(SHA256("clipway-code" || pairingKey)[0:4]) mod
10000 as four digits, so a person can see that the phone paired with this Mac.

Records: clip {text, sensitive, ts} both ways; otp {code, sender} and ping from the
phone, pong from the Mac; test {n} from the phone is answered with tested {n} and shown
on the Mac, which is how the phone app checks that phone -> Mac delivery works.
"""

import base64
import hashlib
import json
import pathlib

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF

INFO = b"clipway-v1"


def b64(data: bytes) -> str:
    return base64.b64encode(data).decode()


def raw_public(private: X25519PrivateKey) -> bytes:
    return private.public_key().public_bytes(
        serialization.Encoding.Raw, serialization.PublicFormat.Raw
    )


def nonce(counter: int) -> bytes:
    return b"\x00" * 4 + counter.to_bytes(8, "big")


psk = bytes(range(0x10, 0x30))
phone_private_raw = bytes(range(0x40, 0x60))
mac_private_raw = bytes(range(0x80, 0xA0))
phone_private = X25519PrivateKey.from_private_bytes(phone_private_raw)
mac_private = X25519PrivateKey.from_private_bytes(mac_private_raw)

phone_hello = json.dumps(
    {"v": 1, "eph": b64(raw_public(phone_private))}, separators=(",", ":")
).encode()
mac_hello = json.dumps(
    {"v": 1, "eph": b64(raw_public(mac_private))}, separators=(",", ":")
).encode()

shared = phone_private.exchange(mac_private.public_key())
transcript = hashlib.sha256(phone_hello + mac_hello).digest()
okm = HKDF(algorithm=hashes.SHA256(), length=96, salt=psk, info=INFO + transcript).derive(shared)
phone_to_mac, mac_to_phone, pairing_key = okm[:32], okm[32:64], okm[64:]

records = []
for direction, key, counter, plaintext in [
    ("phoneToMac", phone_to_mac, 0, '{"t":"hello","id":"11111111-2222-3333-4444-555555555555","name":"Android Phone","ts":0}'),
    ("phoneToMac", phone_to_mac, 1, '{"t":"clip","text":"안녕하세요 clipboard ✓","sensitive":false,"ts":1791000000000}'),
    ("macToPhone", mac_to_phone, 0, '{"t":"hello","name":"Mac mini"}'),
    ("macToPhone", mac_to_phone, 258, '{"t":"pong"}'),
]:
    sealed = AESGCM(key).encrypt(nonce(counter), plaintext.encode(), None)
    records.append(
        {"direction": direction, "counter": counter, "plaintext": plaintext, "sealed": b64(sealed)}
    )

vectors = {
    "psk": b64(psk),
    "phonePrivate": b64(phone_private_raw),
    "phonePublic": b64(raw_public(phone_private)),
    "macPrivate": b64(mac_private_raw),
    "macPublic": b64(raw_public(mac_private)),
    "phoneHello": b64(phone_hello),
    "macHello": b64(mac_hello),
    "phoneToMacKey": b64(phone_to_mac),
    "macToPhoneKey": b64(mac_to_phone),
    "pairingKey": b64(pairing_key),
    "pairingCode": "%04d" % (int.from_bytes(hashlib.sha256(b"clipway-code" + pairing_key).digest()[:4], "big") % 10000),
    "records": records,
}

out = pathlib.Path(__file__).with_name("handshake.json")
out.write_text(json.dumps(vectors, ensure_ascii=False, indent=2) + "\n")
print(f"wrote {out}")
