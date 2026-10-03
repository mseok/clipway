import Foundation

/// Protocol v1 wire format. The reference description lives in testvectors/generate.py.
public enum Wire {
    public static let version = 1
    public static let defaultPort: UInt16 = 47823
    public static let serviceType = "_clipway._tcp"
    public static let maxFrame = 2 * 1024 * 1024
    /// Limit for the frames exchanged before a peer has proved it holds a pairing key.
    public static let maxHandshakeFrame = 1024
    public static let maxNameLength = 64
    public static let maxClipBytes = 1024 * 1024
    /// An image travels as an "image" record followed by raw records of this size.
    public static let imageChunk = 256 * 1024
    public static let maxImageBytes = 20 * 1024 * 1024
    public static let imageTypes: Set<String> = [
        "image/png", "image/jpeg", "image/gif", "image/webp", "image/heic", "image/heif", "image/bmp",
    ]

    public static func frame(_ payload: Data) -> Data {
        var out = Data(capacity: payload.count + 4)
        withUnsafeBytes(of: UInt32(payload.count).bigEndian) { out.append(contentsOf: $0) }
        out.append(payload)
        return out
    }

    public static func frameLength(_ header: Data) -> Int {
        header.reduce(0) { $0 << 8 | Int($1) }
    }

    public static func encode<T: Encodable>(_ value: T) throws -> Data {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.withoutEscapingSlashes]
        return try encoder.encode(value)
    }
}

/// The plaintext hellos carry no device identifiers: which pairing a phone belongs to
/// is found by trying the stored keys on its first encrypted record.
public struct PhoneHello: Codable {
    public var v: Int
    public var eph: String
}

public struct MacHello: Codable {
    public var v: Int
    public var eph: String

    public init(v: Int, eph: String) {
        self.v = v
        self.eph = eph
    }
}

/// One encrypted record. `t` is "hello", "clip", "image", "otp", "ping", "pong", "test" or "tested".
public struct Message: Codable, Equatable {
    public var t: String
    /// Phone id, sent in the phone's encrypted hello.
    public var id: String?
    public var name: String?
    public var text: String?
    public var sensitive: Bool?
    public var ts: Int64?
    public var code: String?
    public var sender: String?
    /// Number chosen by the phone for a connection test and echoed in the reply.
    public var n: Int64?
    /// In "hello": the sender's clock, so the receiver can translate its timestamps.
    public var now: Int64?
    /// For "image": the media type and the number of bytes in the raw records that follow.
    public var mime: String?
    public var size: Int?

    public init(
        t: String, id: String? = nil, name: String? = nil, text: String? = nil,
        sensitive: Bool? = nil, ts: Int64? = nil, code: String? = nil, sender: String? = nil,
        n: Int64? = nil, mime: String? = nil, size: Int? = nil, now: Int64? = nil
    ) {
        self.t = t
        self.id = id
        self.name = name
        self.text = text
        self.sensitive = sensitive
        self.ts = ts
        self.code = code
        self.sender = sender
        self.n = n
        self.mime = mime
        self.size = size
        self.now = now
    }
}

public enum Sanitize {
    /// Single-line display text from a peer: no control characters, bounded length.
    public static func label(_ value: String?, maxLength: Int = Wire.maxNameLength) -> String {
        let cleaned = (value ?? "").unicodeScalars.filter { !CharacterSet.controlCharacters.contains($0) }
        return String(String(String.UnicodeScalarView(cleaned)).prefix(maxLength))
            .trimmingCharacters(in: .whitespaces)
    }

    /// A verification code is 4 to 8 ASCII digits; anything else is dropped.
    public static func verificationCode(_ value: String?) -> String? {
        guard let value, (4...8).contains(value.count),
            value.allSatisfy({ $0.isASCII && $0.isNumber })
        else { return nil }
        return value
    }
}

public enum PairingLink {
    /// clipway://pair?v=1&id=..&name=..&psk=<base64url>&port=..&hosts=a,b
    public static func make(macId: String, name: String, psk: Data, port: UInt16, hosts: [String]) -> String {
        // Everything outside RFC 3986 "unreserved" is percent-encoded, so the link has no
        // quotes or shell metacharacters whatever the computer is called.
        let unreserved = CharacterSet(
            charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~")
        let items: [(String, String)] = [
            ("v", String(Wire.version)), ("id", macId), ("name", name), ("psk", base64URL(psk)),
            ("port", String(port)), ("hosts", hosts.joined(separator: ",")),
        ]
        let query = items.map { key, value in
            key + "=" + (value.addingPercentEncoding(withAllowedCharacters: unreserved) ?? "")
        }
        return "clipway://pair?" + query.joined(separator: "&")
    }

    static func base64URL(_ data: Data) -> String {
        data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}
