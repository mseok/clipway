"""Generates handshake.json from an independent implementation of protocol v1.

Run: uv run --with cryptography python testvectors/generate.py

Protocol v1
  frame      = 4-byte big-endian length || payload
  phoneHello = JSON {"v":1,"phoneId":..,"macId":..,"eph":b64(X25519 pub)}   (plaintext)
  macHello   = JSON {"v":1,"eph":b64(X25519 pub)}                            (plaintext)
  okm        = HKDF-SHA256(ikm = X25519 shared secret, salt = psk,
                           info = "clipway-v1" || SHA256(phoneHello || macHello), L = 64)
  phoneToMac = okm[0:32], macToPhone = okm[32:64]
  record     = AES-256-GCM(key, nonce = 00000000 || counter_be64, aad = "") -> ciphertext || tag
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
    {
        "v": 1,
        "phoneId": "11111111-2222-3333-4444-555555555555",
        "macId": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
        "eph": b64(raw_public(phone_private)),
    },
    separators=(",", ":"),
).encode()
mac_hello = json.dumps(
    {"v": 1, "eph": b64(raw_public(mac_private))}, separators=(",", ":")
).encode()

shared = phone_private.exchange(mac_private.public_key())
transcript = hashlib.sha256(phone_hello + mac_hello).digest()
okm = HKDF(algorithm=hashes.SHA256(), length=64, salt=psk, info=INFO + transcript).derive(shared)
phone_to_mac, mac_to_phone = okm[:32], okm[32:]

records = []
for direction, key, counter, plaintext in [
    ("phoneToMac", phone_to_mac, 0, '{"t":"hello","name":"Android Phone"}'),
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
    "records": records,
}

out = pathlib.Path(__file__).with_name("handshake.json")
out.write_text(json.dumps(vectors, ensure_ascii=False, indent=2) + "\n")
print(f"wrote {out}")
