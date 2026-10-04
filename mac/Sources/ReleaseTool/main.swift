import BridgeCore
import CryptoKit
import Foundation

// Signs the release manifest. Used by scripts/package-release.sh.
//
//   ReleaseTool keygen <key file>        creates the release key and prints its public half
//   ReleaseTool public <key file>        prints the public half of an existing key
//   ReleaseTool sign <key file> <file>   writes <file>.sig
//   ReleaseTool verify <file>            checks <file>.sig against the key built into the app

func fail(_ message: String) -> Never {
    FileHandle.standardError.write(Data((message + "\n").utf8))
    exit(1)
}

func loadKey(_ path: String) -> Curve25519.Signing.PrivateKey {
    guard let text = try? String(contentsOfFile: path, encoding: .utf8),
        let raw = Data(base64Encoded: text.trimmingCharacters(in: .whitespacesAndNewlines)),
        let key = try? Curve25519.Signing.PrivateKey(rawRepresentation: raw)
    else { fail("cannot read the release key at \(path)") }
    return key
}

let arguments = Array(CommandLine.arguments.dropFirst())
switch (arguments.first, arguments.count) {
case ("keygen", 2):
    let path = arguments[1]
    guard !FileManager.default.fileExists(atPath: path) else { fail("\(path) already exists") }
    let key = Curve25519.Signing.PrivateKey()
    let created = FileManager.default.createFile(
        atPath: path, contents: Data((key.rawRepresentation.base64EncodedString() + "\n").utf8),
        attributes: [.posixPermissions: 0o600])
    guard created else { fail("cannot write \(path)") }
    print(key.publicKey.rawRepresentation.base64EncodedString())
case ("public", 2):
    print(loadKey(arguments[1]).publicKey.rawRepresentation.base64EncodedString())
case ("sign", 3):
    let key = loadKey(arguments[1])
    guard let data = FileManager.default.contents(atPath: arguments[2]),
        let signature = try? key.signature(for: data)
    else { fail("cannot sign \(arguments[2])") }
    do {
        try Data((signature.base64EncodedString() + "\n").utf8)
            .write(to: URL(fileURLWithPath: arguments[2] + ".sig"))
    } catch {
        fail("cannot write \(arguments[2]).sig")
    }
case ("verify", 2):
    guard let data = FileManager.default.contents(atPath: arguments[1]),
        let signature = FileManager.default.contents(atPath: arguments[1] + ".sig"),
        let manifest = ReleaseSigning.verify(manifest: data, signature: signature)
    else { fail("not signed with the key built into the app") }
    print("signature ok: release \(manifest.version)")
default:
    fail("usage: ReleaseTool keygen|public <key file> | sign <key file> <file> | verify <file>")
}
