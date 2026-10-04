import CryptoKit
import Foundation
import Testing

@testable import BridgeCore

private func manifestJSON(version: String = "0.2.0", file: String = "Clipway-mac.zip", size: Int = 1234) -> Data {
    let hash = String(repeating: "ab", count: 32)
    return Data(
        """
        {"version":"\(version)","mac":{"file":"\(file)","sha256":"\(hash)","size":\(size)},\
        "android":{"file":"Clipway-android.apk","sha256":"\(hash)","size":5678}}
        """.utf8)
}

private func sign(_ data: Data, with key: Curve25519.Signing.PrivateKey) throws -> Data {
    Data((try key.signature(for: data).base64EncodedString() + "\n").utf8)
}

@Test func versionsCompareNumerically() {
    #expect(AppVersion("0.10.0")! > AppVersion("0.9.9")!)
    #expect(AppVersion("1.0")! == AppVersion("1.0.0")!)
    #expect(AppVersion("0.1.1")! > AppVersion("0.1")!)
    #expect(!(AppVersion("0.1.0")! > AppVersion("0.1.0")!))
    for bad in ["", "1..2", "v1.0", "1.0-beta", "1.2.3.4.5", "1.-2", "12345678.0", " 1.0"] {
        #expect(AppVersion(bad) == nil, "\(bad)")
    }
}

@Test func manifestNeedsTheReleaseKeySignature() throws {
    let key = Curve25519.Signing.PrivateKey()
    let publicKey = key.publicKey.rawRepresentation
    let manifest = manifestJSON()
    let signature = try sign(manifest, with: key)

    let release = ReleaseSigning.verify(manifest: manifest, signature: signature, publicKey: publicKey)
    #expect(release?.version == "0.2.0")
    #expect(release?.mac.size == 1234)

    // One changed byte, another key, or the key built into the app: all rejected.
    var tampered = manifest
    tampered[tampered.count - 3] ^= 1
    #expect(ReleaseSigning.verify(manifest: tampered, signature: signature, publicKey: publicKey) == nil)
    let other = Curve25519.Signing.PrivateKey()
    #expect(ReleaseSigning.verify(manifest: manifest, signature: try sign(manifest, with: other), publicKey: publicKey) == nil)
    #expect(ReleaseSigning.verify(manifest: manifest, signature: signature) == nil)
    #expect(ReleaseSigning.verify(manifest: manifest, signature: Data("not base64".utf8), publicKey: publicKey) == nil)
}

@Test func signedManifestMustStillBeWellFormed() throws {
    let key = Curve25519.Signing.PrivateKey()
    let publicKey = key.publicKey.rawRepresentation
    let bad = [
        manifestJSON(version: "latest"),
        manifestJSON(file: "../Clipway-mac.zip"),
        manifestJSON(file: ".hidden"),
        manifestJSON(size: 0),
        manifestJSON(size: ReleaseSigning.maxAssetBytes + 1),
        Data(#"{"version":"0.2.0"}"#.utf8),
    ]
    for manifest in bad {
        let signature = try sign(manifest, with: key)
        #expect(ReleaseSigning.verify(manifest: manifest, signature: signature, publicKey: publicKey) == nil)
    }
}

@Test func builtInKeyIsAnEd25519PublicKey() throws {
    #expect(ReleaseSigning.publicKey.count == 32)
    #expect(ReleaseSigning.publicKey != Data(count: 32))
    _ = try Curve25519.Signing.PublicKey(rawRepresentation: ReleaseSigning.publicKey)
}
