import Foundation

/// Decides which network peers may talk to the listener at all. The pairing key
/// already authenticates phones; this keeps hosts on the public internet from
/// reaching even the handshake when the Mac has a public address.
public enum PeerFilter {
    /// IPv4 address and netmask of a local interface, as host-order integers.
    public struct Subnet: Equatable {
        public let address: UInt32
        public let mask: UInt32

        public init(address: UInt32, mask: UInt32) {
            self.address = address
            self.mask = mask
        }
    }

    /// Private (RFC 1918), carrier-grade NAT (Tailscale), link-local and loopback ranges.
    private static let localRanges: [(UInt32, UInt32)] = [
        (0x0A00_0000, 0xFF00_0000),  // 10.0.0.0/8
        (0xAC10_0000, 0xFFF0_0000),  // 172.16.0.0/12
        (0xC0A8_0000, 0xFFFF_0000),  // 192.168.0.0/16
        (0x6440_0000, 0xFFC0_0000),  // 100.64.0.0/10
        (0xA9FE_0000, 0xFFFF_0000),  // 169.254.0.0/16
        (0x7F00_0000, 0xFF00_0000),  // 127.0.0.0/8
    ]

    /// A peer is allowed when it is in a local range or on a subnet this Mac is attached
    /// to (some campus networks hand out public addresses on the LAN).
    public static func allowsIPv4(_ peer: UInt32, localSubnets: [Subnet]) -> Bool {
        if localRanges.contains(where: { peer & $0.1 == $0.0 }) { return true }
        return localSubnets.contains { $0.mask != 0 && peer & $0.mask == $0.address & $0.mask }
    }

    /// `bytes` is a 4-byte IPv4 or 16-byte IPv6 address in network order.
    public static func allows(_ bytes: [UInt8], localSubnets: [Subnet]) -> Bool {
        if bytes.count == 4 {
            return allowsIPv4(bytes.reduce(0) { $0 << 8 | UInt32($1) }, localSubnets: localSubnets)
        }
        guard bytes.count == 16 else { return false }
        let mappedPrefix: [UInt8] = [0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xFF, 0xFF]
        if Array(bytes[0..<12]) == mappedPrefix {
            return allows(Array(bytes[12...]), localSubnets: localSubnets)
        }
        if bytes == [UInt8](repeating: 0, count: 15) + [1] { return true }  // ::1
        if bytes[0] == 0xFE && bytes[1] & 0xC0 == 0x80 { return true }  // fe80::/10 link-local
        return bytes[0] & 0xFE == 0xFC  // fc00::/7 unique local (includes Tailscale)
    }

    /// Key for per-peer limits: the IPv4 address, or the /64 of an IPv6 address (one host
    /// can use any number of addresses inside its /64).
    public static func bucket(_ bytes: [UInt8]) -> [UInt8] {
        bytes.count == 16 ? Array(bytes[0..<8]) : bytes
    }
}
