import BridgeCore
import Foundation
import Network

/// Accepts phone connections on every interface (LAN and Tailscale) and
/// advertises the service over Bonjour so the phone can find the LAN address.
final class BridgeListener {
    private var listener: NWListener?

    func start(
        port: UInt16, macId: String, name: String,
        onConnection: @escaping (NWConnection) -> Void,
        onFailure: @escaping (String) -> Void
    ) {
        let tcp = NWProtocolTCP.Options()
        tcp.enableKeepalive = true
        tcp.keepaliveIdle = 20
        tcp.keepaliveInterval = 5
        tcp.keepaliveCount = 3
        tcp.noDelay = true
        let parameters = NWParameters(tls: nil, tcp: tcp)
        parameters.allowLocalEndpointReuse = true

        do {
            let listener = try NWListener(using: parameters, on: NWEndpoint.Port(rawValue: port)!)
            var txt = NWTXTRecord()
            txt["id"] = macId
            listener.service = NWListener.Service(name: name, type: Wire.serviceType, txtRecord: txt)
            listener.newConnectionHandler = onConnection
            listener.stateUpdateHandler = { state in
                switch state {
                case .ready:
                    Log.net.info("listening on \(port)")
                case .failed(let error):
                    Log.net.error("listener failed: \(error.localizedDescription, privacy: .public)")
                    onFailure(error.localizedDescription)
                default:
                    break
                }
            }
            listener.start(queue: .main)
            self.listener = listener
        } catch {
            Log.net.error("listener start failed: \(error.localizedDescription, privacy: .public)")
            onFailure(error.localizedDescription)
        }
    }
}

enum LocalAddresses {
    /// IPv4 addresses a phone could reach: LAN interfaces and the Tailscale range.
    static func reachable() -> [String] {
        var result: [String] = []
        var head: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&head) == 0 else { return [] }
        defer { freeifaddrs(head) }
        var cursor = head
        while let entry = cursor {
            defer { cursor = entry.pointee.ifa_next }
            guard let address = entry.pointee.ifa_addr, address.pointee.sa_family == UInt8(AF_INET)
            else { continue }
            let interface = String(cString: entry.pointee.ifa_name)
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            guard
                getnameinfo(
                    address, socklen_t(address.pointee.sa_len), &host, socklen_t(host.count),
                    nil, 0, NI_NUMERICHOST) == 0
            else { continue }
            let ip = String(cString: host)
            let octets = ip.split(separator: ".").compactMap { Int($0) }
            guard octets.count == 4, octets[0] != 127, !(octets[0] == 169 && octets[1] == 254)
            else { continue }
            let isTailscale = octets[0] == 100 && (64...127).contains(octets[1])
            if interface.hasPrefix("en") || isTailscale, !result.contains(ip) {
                result.append(ip)
            }
        }
        return result
    }
}
