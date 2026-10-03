import CryptoKit
import Foundation

/// Arithmetic on timestamps that came from the other device. Swift traps on integer
/// overflow, so nothing from the network is added or subtracted before it is bounded.
public enum Clock {
    /// 2100-01-01 in milliseconds; anything later is not a real timestamp.
    public static let maxTimestamp: Int64 = 4_102_444_800_000

    /// What to add to the peer's timestamps to get local time (0 when the peer's clock is unusable).
    public static func offset(peerNow: Int64?, localNow: Int64) -> Int64 {
        guard let peerNow, peerNow > 0, peerNow <= maxTimestamp, localNow > 0, localNow <= maxTimestamp
        else { return 0 }
        return localNow - peerNow
    }

    /// A peer timestamp on the local clock, never later than now. Returns nil for a
    /// missing or impossible value.
    public static func translate(_ ts: Int64?, offset: Int64, now: Int64) -> Int64? {
        guard let ts, ts > 0, ts <= maxTimestamp, abs(offset) <= maxTimestamp else { return nil }
        return min(max(ts + offset, 1), now)
    }
}

public enum PairingCode {
    /// Four digits derived from the long-term pairing key. Both devices show it, so the
    /// person can see that the phone paired with this Mac and not with something else.
    public static func code(for key: Data) -> String {
        let digest = SHA256.hash(data: Data("clipway-code".utf8) + key)
        let value = digest.prefix(4).reduce(UInt32(0)) { $0 << 8 | UInt32($1) }
        return String(format: "%04d", value % 10000)
    }
}
