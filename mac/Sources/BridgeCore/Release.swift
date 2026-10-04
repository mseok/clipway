import CryptoKit
import Foundation

/// A dotted release number such as "0.1.0". Anything else is rejected.
public struct AppVersion: Comparable, Sendable {
    /// Without trailing zeros, so that "1.0" and "1.0.0" are equal.
    private let parts: [Int]

    public init?(_ string: String) {
        let fields = string.split(separator: ".", omittingEmptySubsequences: false)
        guard (1...4).contains(fields.count) else { return nil }
        var parts: [Int] = []
        for field in fields {
            guard (1...6).contains(field.count), field.allSatisfy({ $0.isASCII && $0.isNumber }),
                let number = Int(field)
            else { return nil }
            parts.append(number)
        }
        while parts.last == 0 { parts.removeLast() }
        self.parts = parts
    }

    public static func < (lhs: AppVersion, rhs: AppVersion) -> Bool {
        lhs.parts.lexicographicallyPrecedes(rhs.parts)
    }
}

/// `release.json` of a published release: which version it is and what its files hash to.
public struct ReleaseManifest: Codable, Equatable, Sendable {
    public struct Asset: Codable, Equatable, Sendable {
        public let file: String
        public let sha256: String
        public let size: Int
    }

    public let version: String
    public let mac: Asset
    public let android: Asset
}

/// Releases are signed with an Ed25519 key that stays out of git (`release.key`). The Mac
/// app is only ad-hoc signed, so this signature is what tells a real update from a forged
/// one. On Android the system does that job: it only accepts an APK signed like the
/// installed one.
public enum ReleaseSigning {
    public static let publicKey = Data(base64Encoded: "b64IU+IpVblc94X/oSw1SXnoUUEKOnRetfWz4AN9rPs=")!

    public static let maxManifestBytes = 16 * 1024
    public static let maxAssetBytes = 200 * 1024 * 1024

    /// Returns the manifest only if `signature` (base64, the content of `release.json.sig`)
    /// was made by the release key over exactly these bytes.
    public static func verify(
        manifest: Data, signature: Data, publicKey: Data = publicKey
    ) -> ReleaseManifest? {
        let encoded = String(decoding: signature, as: UTF8.self)
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard manifest.count <= maxManifestBytes,
            let raw = Data(base64Encoded: encoded),
            let key = try? Curve25519.Signing.PublicKey(rawRepresentation: publicKey),
            key.isValidSignature(raw, for: manifest),
            let decoded = try? JSONDecoder().decode(ReleaseManifest.self, from: manifest),
            AppVersion(decoded.version) != nil, wellFormed(decoded.mac), wellFormed(decoded.android)
        else { return nil }
        return decoded
    }

    /// The file name becomes part of a download address and the size bounds the download.
    private static func wellFormed(_ asset: ReleaseManifest.Asset) -> Bool {
        let name = asset.file.unicodeScalars
        let plain = CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789._-")
        let hex = CharacterSet(charactersIn: "0123456789abcdef")
        return (1...64).contains(name.count) && name.first != "." && name.allSatisfy(plain.contains)
            && asset.sha256.unicodeScalars.count == 64 && asset.sha256.unicodeScalars.allSatisfy(hex.contains)
            && (1...maxAssetBytes).contains(asset.size)
    }
}
