"""Stand-in for the Android app, used to exercise the Mac app over a real socket.

Run: uv run --with cryptography python tools/fake_phone.py --psk <b64> <actions...>

Actions run in order on one connection:
  clip:<text>     send a clipboard text
  otp:<code>      send a verification code
  listen:<secs>   print messages received from the Mac for that long
  test            send a connection test and print the Mac's answer
"""

import argparse
import base64
import hashlib
import json
import socket
import struct
import time

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey, X25519PublicKey
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF

INFO = b"clipway-v1"


class Channel:
    def __init__(self, sock: socket.socket):
        self.sock = sock
        self.send_key = self.recv_key = None
        self.pairing_key = None  # the psk to use from now on if this handshake was a pairing
        self.send_counter = self.recv_counter = 0

    def read_exactly(self, count: int) -> bytes:
        data = b""
        while len(data) < count:
            chunk = self.sock.recv(count - len(data))
            if not chunk:
                raise ConnectionError("closed by Mac")
            data += chunk
        return data

    def read_frame(self) -> bytes:
        (length,) = struct.unpack(">I", self.read_exactly(4))
        return self.read_exactly(length)

    def write_frame(self, payload: bytes) -> None:
        self.sock.sendall(struct.pack(">I", len(payload)) + payload)

    @staticmethod
    def nonce(counter: int) -> bytes:
        return b"\x00" * 4 + counter.to_bytes(8, "big")

    def send(self, message: dict) -> None:
        plaintext = json.dumps(message, ensure_ascii=False, separators=(",", ":")).encode()
        self.write_frame(AESGCM(self.send_key).encrypt(self.nonce(self.send_counter), plaintext, None))
        self.send_counter += 1

    def receive(self) -> dict:
        sealed = self.read_frame()
        plaintext = AESGCM(self.recv_key).decrypt(self.nonce(self.recv_counter), sealed, None)
        self.recv_counter += 1
        return json.loads(plaintext)


def connect(host: str, port: int, psk: bytes, phone_id: str, clip_ts: int) -> Channel:
    channel = Channel(socket.create_connection((host, port), timeout=5))
    ephemeral = X25519PrivateKey.generate()
    public = ephemeral.public_key().public_bytes(
        serialization.Encoding.Raw, serialization.PublicFormat.Raw
    )
    phone_hello = json.dumps(
        {"v": 1, "eph": base64.b64encode(public).decode()}, separators=(",", ":")
    ).encode()
    channel.write_frame(phone_hello)
    mac_hello = channel.read_frame()
    mac_public = X25519PublicKey.from_public_bytes(base64.b64decode(json.loads(mac_hello)["eph"]))
    transcript = hashlib.sha256(phone_hello + mac_hello).digest()
    okm = HKDF(algorithm=hashes.SHA256(), length=96, salt=psk, info=INFO + transcript).derive(
        ephemeral.exchange(mac_public)
    )
    channel.send_key, channel.recv_key, channel.pairing_key = okm[:32], okm[32:64], okm[64:]
    channel.send({"t": "hello", "id": phone_id, "name": "Fake Phone", "ts": clip_ts})
    print("mac:", channel.receive())
    return channel


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=47823)
    parser.add_argument("--psk", required=True, help="base64 pairing key")
    parser.add_argument("--phone-id", default="fake-phone")
    parser.add_argument("--clip-ts", type=int, default=0, help="ts reported in hello")
    parser.add_argument("actions", nargs="*")
    args = parser.parse_args()

    channel = connect(args.host, args.port, base64.b64decode(args.psk), args.phone_id, args.clip_ts)
    for action in args.actions:
        kind, _, value = action.partition(":")
        if kind == "clip":
            channel.send({"t": "clip", "text": value, "sensitive": False, "ts": int(time.time() * 1000)})
        elif kind == "otp":
            channel.send({"t": "otp", "code": value, "sender": "15881234"})
        elif kind == "test":
            channel.send({"t": "test", "n": 12345})
            print("mac:", channel.receive())
        elif kind == "listen":
            deadline = time.time() + float(value)
            channel.sock.settimeout(0.2)
            while time.time() < deadline:
                try:
                    print("mac:", channel.receive())
                except TimeoutError:
                    continue
            channel.sock.settimeout(5)
        else:
            raise SystemExit(f"unknown action {action}")
        time.sleep(0.3)
    channel.sock.close()


if __name__ == "__main__":
    main()
