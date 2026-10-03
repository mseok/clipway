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

/// Bytes handed to the connection and not yet written out.
final class Backlog: @unchecked Sendable {
    private let lock = NSLock()
    private var bytes = 0

    func add(_ count: Int) -> Int {
        lock.lock()
        defer { lock.unlock() }
        bytes += count
        return bytes
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
        /// The key to store for this phone: the stored one, or the new one after a pairing.
        var storedKey: Data { newPairingKey ?? psk }
        /// When the phone's current clipboard content was copied (0 if unknown), on this Mac's clock.
        let clipTs: Int64
        /// Add this to a timestamp from the phone to get the time on this Mac's clock. The two
        /// clocks can be seconds apart, and "newest copy wins" needs them compared fairly.
        let clockOffset: Int64
    }

    nonisolated let connection: NWConnection
    private let backlog = Backlog()
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
    func handshake(macName: String, candidates: [Candidate]) async throws -> Peer {
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
            // The Mac's hello is sent by `confirm`, once the controller has accepted the
            // key. A phone therefore never sees a hello for a pairing the Mac refused.
            let now = Int64(Date().timeIntervalSince1970 * 1000)
            let name = Sanitize.label(message.name)
            let offset = Clock.offset(peerNow: message.now, localNow: now)
            return Peer(
                phoneId: phoneId, name: name.isEmpty ? "Android" : name, psk: candidate.psk,
                newPairingKey: candidate.phoneId == nil ? keys.pairingKey : nil,
                clipTs: Clock.translate(message.ts, offset: offset, now: now) ?? 0, clockOffset: offset)
        }
        throw SessionError.authFailed
    }

    /// Completes the handshake after the controller has accepted the key.
    func confirm(macName: String, clipTs: Int64) throws {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        try send(Message(t: "hello", name: macName, ts: clipTs, now: now))
    }

    enum Record {
        case message(Message)
        /// Part of the picture announced by the last "image" message.
        case chunk(Data)
    }

    func receive() async throws -> Record {
        let sealed = try await readFrame()
        guard recvCipher != nil else { throw SessionError.malformed }
        let plaintext = try recvCipher!.open(sealed)
        if plaintext.first == 0 { return .chunk(plaintext.dropFirst()) }
        return .message(try JSONDecoder().decode(Message.self, from: plaintext))
    }

    /// Seals and enqueues in one step on the actor, so records reach the connection in
    /// counter order even when several tasks send at once.
    func send(_ message: Message) throws {
        try sendRecord(Wire.encode(message))
    }

    func sendImage(mime: String, data: Data, sensitive: Bool, ts: Int64) throws {
        try send(Message(t: "image", sensitive: sensitive, ts: ts, mime: mime, size: data.count))
        var offset = data.startIndex
        while offset < data.endIndex {
            let end = min(offset + Wire.imageChunk, data.endIndex)
            try sendRecord(Data([0]) + data[offset..<end])
            offset = end
        }
    }

    private func sendRecord(_ plaintext: Data) throws {
        guard sendCipher != nil else { throw SessionError.malformed }
        // A phone that stops reading must not make this Mac queue without limit.
        guard backlog.add(plaintext.count) <= Wire.maxBacklog else {
            connection.cancel()
            throw SessionError.closed
        }
        let sealed = try sendCipher!.seal(plaintext)
        connection.send(
            content: Wire.frame(sealed),
            completion: .contentProcessed { [connection, backlog] error in
                _ = backlog.add(-plaintext.count)
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
