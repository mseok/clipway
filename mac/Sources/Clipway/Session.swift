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
        /// The key that authenticated this handshake.
        let psk: Data
        /// Set when the handshake used the QR code's key: the key to store for this phone.
        let newPairingKey: Data?
        /// When the phone's current clipboard content was copied (0 if unknown).
        let clipTs: Int64
    }

    nonisolated let connection: NWConnection
    private var sendCipher: FrameCipher?
    private var recvCipher: FrameCipher?

    init(connection: NWConnection) {
        self.connection = connection
    }

    /// A pairing key worth trying: a stored pairing, or (with `phoneId` nil) the key of
    /// the QR code that is on screen right now.
    struct Candidate {
        let psk: Data
        let phoneId: String?
    }

    /// The phone proves which pairing it holds by encrypting its hello; the plaintext
    /// part of the handshake names neither device.
    func handshake(macName: String, clipTs: Int64, candidates: [Candidate]) async throws -> Peer {
        guard !candidates.isEmpty else { throw SessionError.unknownPeer }
        let phoneHelloBytes = try await readFrame(limit: Wire.maxHandshakeFrame)
        let hello = try JSONDecoder().decode(PhoneHello.self, from: phoneHelloBytes)
        guard hello.v == Wire.version, let peerPublic = Data(base64Encoded: hello.eph),
            peerPublic.count == 32
        else { throw SessionError.malformed }

        let ephemeral = Curve25519.KeyAgreement.PrivateKey()
        let macHelloBytes = try Wire.encode(
            MacHello(v: Wire.version, eph: ephemeral.publicKey.rawRepresentation.base64EncodedString()))
        try await connection.sendAll(Wire.frame(macHelloBytes))

        let first = try await readFrame(limit: Wire.maxHandshakeFrame)
        for candidate in candidates {
            let keys = try BridgeCrypto.deriveKeys(
                psk: candidate.psk, ownPrivate: ephemeral, peerPublicRaw: peerPublic,
                phoneHello: phoneHelloBytes, macHello: macHelloBytes)
            var cipher = FrameCipher(key: keys.phoneToMac)
            guard let plaintext = try? cipher.open(first),
                let message = try? JSONDecoder().decode(Message.self, from: plaintext),
                message.t == "hello"
            else { continue }
            // A stored pairing keeps its id; a new pairing takes the id the phone reports.
            let reported = Sanitize.label(message.id)
            guard let phoneId = candidate.phoneId ?? (reported.isEmpty ? nil : reported)
            else { throw SessionError.malformed }
            recvCipher = cipher
            sendCipher = FrameCipher(key: keys.macToPhone)
            try send(Message(t: "hello", name: macName, ts: clipTs))
            let name = Sanitize.label(message.name)
            return Peer(
                phoneId: phoneId, name: name.isEmpty ? "Android" : name, psk: candidate.psk,
                newPairingKey: candidate.phoneId == nil ? keys.pairingKey : nil,
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

    /// Seals and enqueues in one step on the actor, so records reach the connection in
    /// counter order even when several tasks send at once.
    func send(_ message: Message) throws {
        guard sendCipher != nil else { throw SessionError.malformed }
        let sealed = try sendCipher!.seal(Wire.encode(message))
        connection.send(
            content: Wire.frame(sealed),
            completion: .contentProcessed { [connection] error in
                if error != nil { connection.cancel() }
            })
    }

    nonisolated func close() {
        connection.cancel()
    }

    private func readFrame(limit: Int = Wire.maxFrame) async throws -> Data {
        let length = Wire.frameLength(try await connection.readExactly(4))
        guard length > 0, length <= limit else { throw SessionError.malformed }
        return try await connection.readExactly(length)
    }
}
