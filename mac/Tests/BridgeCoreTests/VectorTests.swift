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
    let pairingKey: String
    let pairingCode: String
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
        #expect(keys.pairingKey == b64(v.pairingKey))
        #expect(PairingCode.code(for: keys.pairingKey) == v.pairingCode)
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
    let first = try JSONDecoder().decode(Message.self, from: Data(v.records[0].plaintext.utf8))
    #expect(first.t == "hello")
    #expect(first.id == "11111111-2222-3333-4444-555555555555")
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

    // Whatever the computer is called, the link holds nothing a shell would interpret.
    let hostile = PairingLink.make(
        macId: "id", name: "Bob's \"Mac\" $(reboot); `x` & more", psk: Data(count: 32),
        port: 1, hosts: ["10.0.0.1"])
    let allowed = CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~%=&:/?")
    #expect(hostile.unicodeScalars.allSatisfy { allowed.contains($0) })
    #expect(URLComponents(string: hostile)?.queryItems?.first { $0.name == "name" }?.value
        == "Bob's \"Mac\" $(reboot); `x` & more")
}

@Test func onlyLocalPeersAreAllowed() {
    func ip(_ a: UInt8, _ b: UInt8, _ c: UInt8, _ d: UInt8) -> [UInt8] { [a, b, c, d] }
    let none: [PeerFilter.Subnet] = []
    for local in [ip(192, 168, 0, 75), ip(10, 1, 2, 3), ip(172, 16, 9, 9), ip(172, 31, 255, 1),
        ip(100, 64, 0, 1), ip(100, 122, 44, 77), ip(169, 254, 3, 4), ip(127, 0, 0, 1)] {
        #expect(PeerFilter.allows(local, localSubnets: none))
    }
    for remote in [ip(8, 8, 8, 8), ip(143, 248, 37, 94), ip(172, 32, 0, 1), ip(100, 128, 0, 1),
        ip(192, 169, 0, 1), ip(11, 0, 0, 1)] {
        #expect(!PeerFilter.allows(remote, localSubnets: none))
    }
    // A public address is fine when this Mac sits on the same subnet.
    let campus = [PeerFilter.Subnet(address: 0x8FF8_2510, mask: 0xFFFF_FF00)]  // 143.248.37.16/24
    #expect(PeerFilter.allows(ip(143, 248, 37, 94), localSubnets: campus))
    #expect(!PeerFilter.allows(ip(143, 248, 38, 94), localSubnets: campus))
    #expect(!PeerFilter.allows(ip(8, 8, 8, 8), localSubnets: [PeerFilter.Subnet(address: 0, mask: 0)]))

    let mapped: [UInt8] = [0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xFF, 0xFF]
    #expect(PeerFilter.allows(mapped + ip(192, 168, 0, 75), localSubnets: none))
    #expect(!PeerFilter.allows(mapped + ip(8, 8, 8, 8), localSubnets: none))
    #expect(PeerFilter.allows([UInt8](repeating: 0, count: 15) + [1], localSubnets: none))
    #expect(PeerFilter.allows([0xFE, 0x80] + [UInt8](repeating: 0, count: 13) + [1], localSubnets: none))
    #expect(PeerFilter.allows([0xFD, 0x7A, 0x11, 0x5C] + [UInt8](repeating: 0, count: 12), localSubnets: none))
    #expect(!PeerFilter.allows([0x20, 0x01, 0x0D, 0xB8] + [UInt8](repeating: 0, count: 12), localSubnets: none))
    #expect(!PeerFilter.allows([1, 2, 3], localSubnets: none))

    // Per-peer limits count a whole IPv6 /64 as one peer.
    let a: [UInt8] = [0xFE, 0x80, 0, 0, 0, 0, 0, 0, 1, 2, 3, 4, 5, 6, 7, 8]
    let b: [UInt8] = [0xFE, 0x80, 0, 0, 0, 0, 0, 0, 9, 9, 9, 9, 9, 9, 9, 9]
    #expect(PeerFilter.bucket(a) == PeerFilter.bucket(b))
    #expect(PeerFilter.bucket(ip(192, 168, 0, 75)) == ip(192, 168, 0, 75))
    #expect(PeerFilter.bucket(mapped + ip(192, 168, 0, 75)) == ip(192, 168, 0, 75))
}

@Test func peerTextIsSanitized() {
    #expect(Sanitize.verificationCode("482913") == "482913")
    #expect(Sanitize.verificationCode("1234") == "1234")
    for bad in ["123", "123456789", "12a456", "１２３４５６", "", "12 34", "rm -rf"] {
        #expect(Sanitize.verificationCode(bad) == nil)
    }
    #expect(Sanitize.verificationCode(nil) == nil)
    #expect(Sanitize.label("  Fold7\n\u{1B}[31m ") == "Fold7[31m")
    #expect(Sanitize.label(String(repeating: "가", count: 200)).count == Wire.maxNameLength)
    #expect(Sanitize.label(nil) == "")
    // Invisible characters cannot make two names look alike; combining marks cannot hide megabytes.
    #expect(Sanitize.label("Mac mini\u{200B}\u{202E}\u{2028}") == "Mac mini")
    #expect(Sanitize.label("a" + String(repeating: "\u{0301}", count: 100_000)).unicodeScalars.count <= Wire.maxNameLength * 4)
}

@Test func peerTimestampsCannotOverflow() {
    let now: Int64 = 1_791_000_000_000
    // Values a hostile peer could put in "now" and "ts": none may trap, all are bounded.
    for peerNow in [Int64.min, -1, 0, 1, now, Int64.max, Clock.maxTimestamp + 1] {
        let offset = Clock.offset(peerNow: peerNow, localNow: now)
        #expect(abs(offset) <= Clock.maxTimestamp)
        for ts in [Int64.min, -1, 0, 1, now, Int64.max] {
            if let translated = Clock.translate(ts, offset: offset, now: now) {
                #expect(translated > 0 && translated <= now)
            }
        }
    }
    #expect(Clock.offset(peerNow: nil, localNow: now) == 0)
    #expect(Clock.offset(peerNow: now + 4000, localNow: now) == -4000)
    // A phone four seconds ahead: its copy from "now" lands at the Mac's now, an older one stays older.
    #expect(Clock.translate(now + 4000, offset: -4000, now: now) == now)
    #expect(Clock.translate(now + 1000, offset: -4000, now: now) == now - 3000)
    #expect(Clock.translate(nil, offset: 0, now: now) == nil)
    #expect(Clock.translate(Int64.max, offset: Int64.min, now: now) == nil)
}
