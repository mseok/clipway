import BridgeCore
import CryptoKit
import Foundation
import Network
import os

enum Log {
    static let app = Logger(subsystem: "dev.mseok.clipway", category: "app")
    static let net = Logger(subsystem: "dev.mseok.clipway", category: "net")
}

enum SessionError: Error {
    case closed
    case malformed
    case unknownPeer
    case authFailed
}

extension NWConnection {
    func readExactly(_ count: Int) async throws -> Data {
        try await withCheckedThrowingContinuation { continuation in
            receive(minimumIncompleteLength: count, maximumLength: count) { data, _, _, error in
                if let error {
                    continuation.resume(throwing: error)
                } else if let data, data.count == count {
                    continuation.resume(returning: data)
                } else {
                    continuation.resume(throwing: SessionError.closed)
                }
            }
        }
    }

    func sendAll(_ data: Data) async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            send(
                content: data,
                completion: .contentProcessed { error in
                    if let error {
                        continuation.resume(throwing: error)
                    } else {
                        continuation.resume()
                    }
                })
        }
    }
}

/// One phone connection: plaintext hello exchange, then encrypted records.
actor PhoneSession {
    struct Peer {
        let phoneId: String
        let name: String
        let psk: Data
        /// When the phone's current clipboard content was copied (0 if unknown).
        let clipTs: Int64
    }

    nonisolated let connection: NWConnection
    private var sendCipher: FrameCipher?
    private var recvCipher: FrameCipher?

    init(connection: NWConnection) {
        self.connection = connection
    }

    /// `pskCandidates` returns the keys worth trying for a phone id: the stored
    /// pairing and, while a QR code is on screen, the pending pairing key.
    func handshake(
        macId: String, macName: String, clipTs: Int64,
        pskCandidates: @Sendable (String) async -> [Data]
    ) async throws -> Peer {
        let phoneHelloBytes = try await readFrame()
        let hello = try JSONDecoder().decode(PhoneHello.self, from: phoneHelloBytes)
        guard hello.v == Wire.version, hello.macId == macId,
            let peerPublic = Data(base64Encoded: hello.eph)
        else { throw SessionError.malformed }

        let candidates = await pskCandidates(hello.phoneId)
        guard !candidates.isEmpty else { throw SessionError.unknownPeer }

        let ephemeral = Curve25519.KeyAgreement.PrivateKey()
        let macHelloBytes = try Wire.encode(
            MacHello(v: Wire.version, eph: ephemeral.publicKey.rawRepresentation.base64EncodedString()))
        try await connection.sendAll(Wire.frame(macHelloBytes))

        let first = try await readFrame()
        for psk in candidates {
            let keys = try BridgeCrypto.deriveKeys(
                psk: psk, ownPrivate: ephemeral, peerPublicRaw: peerPublic,
                phoneHello: phoneHelloBytes, macHello: macHelloBytes)
            var cipher = FrameCipher(key: keys.phoneToMac)
            guard let plaintext = try? cipher.open(first),
                let message = try? JSONDecoder().decode(Message.self, from: plaintext),
                message.t == "hello"
            else { continue }
            recvCipher = cipher
            sendCipher = FrameCipher(key: keys.macToPhone)
            try await send(Message(t: "hello", name: macName, ts: clipTs))
            return Peer(
                phoneId: hello.phoneId, name: message.name ?? "Android", psk: psk,
                clipTs: message.ts ?? 0)
        }
        throw SessionError.authFailed
    }

    func receive() async throws -> Message {
        let sealed = try await readFrame()
        guard recvCipher != nil else { throw SessionError.malformed }
        let plaintext = try recvCipher!.open(sealed)
        return try JSONDecoder().decode(Message.self, from: plaintext)
    }

    func send(_ message: Message) async throws {
        guard sendCipher != nil else { throw SessionError.malformed }
        let sealed = try sendCipher!.seal(Wire.encode(message))
        try await connection.sendAll(Wire.frame(sealed))
    }

    nonisolated func close() {
        connection.cancel()
    }

    private func readFrame() async throws -> Data {
        let length = Wire.frameLength(try await connection.readExactly(4))
        guard length > 0, length <= Wire.maxFrame else { throw SessionError.malformed }
        return try await connection.readExactly(length)
    }
}
