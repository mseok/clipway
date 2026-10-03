"""Forwards loopback ports to a device on the LAN.

macOS Local Network privacy blocks adb (and other third-party command-line
tools started from this session) from reaching 192.168.x.x directly, while
/usr/bin/python3 has been granted that access. adb can still be used by
pointing it at 127.0.0.1 and letting this relay carry the bytes:

    /usr/bin/python3 tools/lan_relay.py 44799:192.168.0.50:44799 &
    adb connect 127.0.0.1:44799

Allowing "adb" under System Settings > Privacy & Security > Local Network
makes the relay unnecessary.
"""

import socket
import sys
import threading


def pump(source: socket.socket, sink: socket.socket) -> None:
    try:
        while chunk := source.recv(65536):
            sink.sendall(chunk)
    except OSError:
        pass
    finally:
        for sock in (source, sink):
            try:
                sock.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            sock.close()


def serve(local_port: int, host: str, port: int) -> None:
    listener = socket.socket()
    listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    listener.bind(("127.0.0.1", local_port))
    listener.listen()
    print(f"127.0.0.1:{local_port} -> {host}:{port}", flush=True)
    while True:
        client, _ = listener.accept()
        try:
            target = socket.create_connection((host, port), timeout=5)
        except OSError as error:
            print(f"{host}:{port} unreachable: {error}", flush=True)
            client.close()
            continue
        target.settimeout(None)
        threading.Thread(target=pump, args=(client, target), daemon=True).start()
        threading.Thread(target=pump, args=(target, client), daemon=True).start()


def main() -> None:
    if len(sys.argv) < 2:
        raise SystemExit("usage: lan_relay.py LOCALPORT:HOST:PORT [...]")
    threads = []
    for spec in sys.argv[1:]:
        local_port, host, port = spec.split(":")
        thread = threading.Thread(target=serve, args=(int(local_port), host, int(port)))
        thread.start()
        threads.append(thread)
    for thread in threads:
        thread.join()


if __name__ == "__main__":
    main()
