import CryptoKit
import Foundation

public enum BridgeError: Error {
    case malformed
}

public struct SessionKeys {
    public let phoneToMac: SymmetricKey
    public let macToPhone: SymmetricKey
    /// Long-term key both sides store after a pairing handshake, replacing the key from
    /// the QR code. A photographed QR code is therefore useless once pairing is done.
    public let pairingKey: Data
}

public enum BridgeCrypto {
    static let info = Data("clipway-v1".utf8)

    /// Both hello payloads are the exact bytes that went over the wire.
    public static func deriveKeys(
        psk: Data,
        ownPrivate: Curve25519.KeyAgreement.PrivateKey,
        peerPublicRaw: Data,
        phoneHello: Data,
        macHello: Data
    ) throws -> SessionKeys {
        let peer = try Curve25519.KeyAgreement.PublicKey(rawRepresentation: peerPublicRaw)
        let shared = try ownPrivate.sharedSecretFromKeyAgreement(with: peer)
        var hash = SHA256()
        hash.update(data: phoneHello)
        hash.update(data: macHello)
        let transcript = Data(hash.finalize())
        let okm = shared.hkdfDerivedSymmetricKey(
            using: SHA256.self, salt: psk, sharedInfo: info + transcript, outputByteCount: 96)
        let bytes = okm.withUnsafeBytes { Data($0) }
        return SessionKeys(
            phoneToMac: SymmetricKey(data: bytes[0..<32]),
            macToPhone: SymmetricKey(data: bytes[32..<64]),
            pairingKey: Data(bytes[64..<96]))
    }
}

/// AES-256-GCM records with a per-direction counter nonce. The counter only
/// advances on success, so a failed `open` leaves the cipher usable.
public struct FrameCipher {
    private let key: SymmetricKey
    private var counter: UInt64

    public init(key: SymmetricKey, counter: UInt64 = 0) {
        self.key = key
        self.counter = counter
    }

    public mutating func seal(_ plaintext: Data) throws -> Data {
        let box = try AES.GCM.seal(plaintext, using: key, nonce: nonce())
        counter += 1
        return box.ciphertext + box.tag
    }

    public mutating func open(_ sealed: Data) throws -> Data {
        guard sealed.count >= 16 else { throw BridgeError.malformed }
        let box = try AES.GCM.SealedBox(
            nonce: nonce(), ciphertext: sealed.dropLast(16), tag: sealed.suffix(16))
        let plaintext = try AES.GCM.open(box, using: key)
        counter += 1
        return plaintext
    }

    private func nonce() throws -> AES.GCM.Nonce {
        var bytes = Data(count: 4)
        withUnsafeBytes(of: counter.bigEndian) { bytes.append(contentsOf: $0) }
        return try AES.GCM.Nonce(data: bytes)
    }
}
