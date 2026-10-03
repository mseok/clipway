import CryptoKit
import Foundation
import Testing

@testable import BridgeCore

struct Vectors: Decodable {
    struct Record: Decodable {
        let direction: String
        let counter: UInt64
        let plaintext: String
        let sealed: String
    }

    let psk: String
    let phonePrivate: String
    let phonePublic: String
    let macPrivate: String
    let macPublic: String
    let phoneHello: String
    let macHello: String
    let phoneToMacKey: String
    let macToPhoneKey: String
    let records: [Record]

    static func load() throws -> Vectors {
        var url = URL(fileURLWithPath: #filePath)
        for _ in 0..<4 { url.deleteLastPathComponent() }
        url.append(path: "testvectors/handshake.json")
        return try JSONDecoder().decode(Vectors.self, from: Data(contentsOf: url))
    }
}

func b64(_ string: String) -> Data { Data(base64Encoded: string)! }

func keyBytes(_ key: SymmetricKey) -> Data { key.withUnsafeBytes { Data($0) } }

@Test func bothSidesDeriveTheReferenceKeys() throws {
    let v = try Vectors.load()
    let phone = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: b64(v.phonePrivate))
    let mac = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: b64(v.macPrivate))
    #expect(phone.publicKey.rawRepresentation == b64(v.phonePublic))
    #expect(mac.publicKey.rawRepresentation == b64(v.macPublic))

    let macSide = try BridgeCrypto.deriveKeys(
        psk: b64(v.psk), ownPrivate: mac, peerPublicRaw: b64(v.phonePublic),
        phoneHello: b64(v.phoneHello), macHello: b64(v.macHello))
    let phoneSide = try BridgeCrypto.deriveKeys(
        psk: b64(v.psk), ownPrivate: phone, peerPublicRaw: b64(v.macPublic),
        phoneHello: b64(v.phoneHello), macHello: b64(v.macHello))

    for keys in [macSide, phoneSide] {
        #expect(keyBytes(keys.phoneToMac) == b64(v.phoneToMacKey))
        #expect(keyBytes(keys.macToPhone) == b64(v.macToPhoneKey))
    }
}

@Test func recordsMatchTheReferenceCiphertext() throws {
    let v = try Vectors.load()
    for record in v.records {
        let key = SymmetricKey(
            data: b64(record.direction == "phoneToMac" ? v.phoneToMacKey : v.macToPhoneKey))
        var sealer = FrameCipher(key: key, counter: record.counter)
        #expect(try sealer.seal(Data(record.plaintext.utf8)) == b64(record.sealed))
        var opener = FrameCipher(key: key, counter: record.counter)
        #expect(try opener.open(b64(record.sealed)) == Data(record.plaintext.utf8))
    }
}

@Test func wrongKeyAndTamperedRecordsAreRejected() throws {
    let v = try Vectors.load()
    let record = v.records[0]
    var wrongKey = FrameCipher(key: SymmetricKey(data: b64(v.macToPhoneKey)), counter: record.counter)
    #expect(throws: (any Error).self) { try wrongKey.open(b64(record.sealed)) }

    var tampered = b64(record.sealed)
    tampered[0] ^= 1
    var cipher = FrameCipher(key: SymmetricKey(data: b64(v.phoneToMacKey)), counter: record.counter)
    #expect(throws: (any Error).self) { try cipher.open(tampered) }
    // A failed open must not advance the counter.
    #expect(try cipher.open(b64(record.sealed)) == Data(record.plaintext.utf8))
}

@Test func helloPayloadsDecode() throws {
    let v = try Vectors.load()
    let phoneHello = try JSONDecoder().decode(PhoneHello.self, from: b64(v.phoneHello))
    #expect(phoneHello.v == 1)
    #expect(phoneHello.eph == v.phonePublic)
    let message = try JSONDecoder().decode(Message.self, from: Data(v.records[1].plaintext.utf8))
    #expect(message.t == "clip")
    #expect(message.text == "안녕하세요 clipboard ✓")
    #expect(message.ts == 1_791_000_000_000)
}

@Test func pairingLinkUsesURLSafeKey() {
    let link = PairingLink.make(
        macId: "id-1", name: "홍길동’s Mac mini", psk: Data(repeating: 0xFB, count: 32),
        port: 47823, hosts: ["192.168.0.2", "100.64.0.10"])
    let items = URLComponents(string: link)?.queryItems ?? []
    let psk = items.first { $0.name == "psk" }?.value ?? ""
    #expect(!psk.contains("+") && !psk.contains("/") && !psk.contains("="))
    #expect(items.first { $0.name == "name" }?.value == "홍길동’s Mac mini")
    #expect(items.first { $0.name == "hosts" }?.value == "192.168.0.2,100.64.0.10")
}
