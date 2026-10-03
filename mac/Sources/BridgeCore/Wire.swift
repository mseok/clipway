import Foundation

/// Protocol v1 wire format. The reference description lives in testvectors/generate.py.
public enum Wire {
    public static let version = 1
    public static let defaultPort: UInt16 = 47823
    public static let serviceType = "_clipway._tcp"
    public static let maxFrame = 2 * 1024 * 1024
    public static let maxClipBytes = 1024 * 1024

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

public struct PhoneHello: Codable {
    public var v: Int
    public var phoneId: String
    public var macId: String
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

/// One encrypted record. `t` is "hello", "clip", "otp", "ping" or "pong".
public struct Message: Codable, Equatable {
    public var t: String
    public var name: String?
    public var text: String?
    public var sensitive: Bool?
    public var ts: Int64?
    public var code: String?
    public var sender: String?

    public init(
        t: String, name: String? = nil, text: String? = nil, sensitive: Bool? = nil,
        ts: Int64? = nil, code: String? = nil, sender: String? = nil
    ) {
        self.t = t
        self.name = name
        self.text = text
        self.sensitive = sensitive
        self.ts = ts
        self.code = code
        self.sender = sender
    }
}

public enum PairingLink {
    /// clipway://pair?v=1&id=..&name=..&psk=<base64url>&port=..&hosts=a,b
    public static func make(macId: String, name: String, psk: Data, port: UInt16, hosts: [String]) -> String {
        var components = URLComponents()
        components.scheme = "clipway"
        components.host = "pair"
        components.queryItems = [
            URLQueryItem(name: "v", value: String(Wire.version)),
            URLQueryItem(name: "id", value: macId),
            URLQueryItem(name: "name", value: name),
            URLQueryItem(name: "psk", value: base64URL(psk)),
            URLQueryItem(name: "port", value: String(port)),
            URLQueryItem(name: "hosts", value: hosts.joined(separator: ",")),
        ]
        return components.string ?? ""
    }

    static func base64URL(_ data: Data) -> String {
        data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}
