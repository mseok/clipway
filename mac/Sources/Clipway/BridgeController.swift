import AppKit
import BridgeCore
import CryptoKit
import Foundation
import Network
import ServiceManagement

/// Owns the listener, the pasteboard watcher and every phone session.
/// All state lives on the main actor.
@MainActor
final class BridgeController: ObservableObject {
    static let shared = BridgeController()

    @Published private(set) var phones: [PairedPhone] = []
    @Published private(set) var connected: Set<String> = []
    @Published private(set) var pairingLink: String?
    @Published private(set) var status = "시작하는 중"
    @Published var clipboardEnabled = true { didSet { persist() } }
    @Published var otpEnabled = true { didSet { persist() } }

    private struct Clip {
        let text: String
        let sensitive: Bool
        let ts: Int64
    }

    private var state = PairingStore.load()
    private let listener = BridgeListener()
    private let watcher = PasteboardWatcher()
    private let otpPresenter = OTPPresenter()
    /// A phone races several addresses, so it may briefly hold more than one session.
    private var sessions: [String: [PhoneSession]] = [:]
    private var pendingPsk: Data?
    private var pendingExpiry: Task<Void, Never>?
    /// Connections that have not finished the handshake yet, per remote address. One
    /// address cannot take every slot, so a single host cannot lock real phones out.
    private var handshaking: [[UInt8]: Int] = [:]
    /// Arrival times of recent verification codes, for rate limiting.
    private var recentCodes: [Date] = []
    private static let maxHandshakingPerPeer = 4
    private static let maxHandshaking = 64
    private var pairingSignal: DispatchSourceSignal?
    private var terminateSignal: DispatchSourceSignal?
    /// Last text copied on this Mac since launch.
    private var localClip: Clip?
    /// When the current pasteboard content was copied, on whichever device.
    /// Incoming clips older than this lose (last writer wins).
    private var clipTs: Int64 = 0
    /// What the pasteboard is known to hold. A change that reports the same text (another
    /// clipboard tool rewriting it, a repeated copy) is not sent again.
    private var pasteboardText: String?

    let macName = Host.current().localizedName ?? "Mac"

    func start() {
        phones = state.phones
        clipboardEnabled = state.clipboardEnabled
        otpEnabled = state.otpEnabled
        otpPresenter.requestPermission()
        PairingStore.writePendingLink(nil)  // left over if the app quit while pairing
        watcher.onCopy = { [weak self] text, sensitive in self?.localCopy(text, sensitive: sensitive) }
        watcher.start()
        listener.start(
            port: Wire.defaultPort, name: macName,
            onConnection: { [weak self] connection in
                Task { @MainActor in self?.accept(connection) }
            },
            onFailure: { [weak self] reason in
                Task { @MainActor in self?.status = "수신 대기 실패: \(reason)" }
            })
        // `pkill -USR1 Clipway` starts pairing without the menu (for scripted setup,
        // e.g. over ssh); the link is then read from PairingStore.pendingLinkURL.
        signal(SIGUSR1, SIG_IGN)
        let source = DispatchSource.makeSignalSource(signal: SIGUSR1, queue: .main)
        source.setEventHandler { [weak self] in
            MainActor.assumeIsolated { self?.beginPairing() }
        }
        source.resume()
        pairingSignal = source

        // `pkill` and installs send SIGTERM; quit through AppKit so sessions are closed.
        signal(SIGTERM, SIG_IGN)
        let terminate = DispatchSource.makeSignalSource(signal: SIGTERM, queue: .main)
        terminate.setEventHandler { NSApp.terminate(nil) }
        terminate.resume()
        terminateSignal = terminate
        refreshStatus()
    }

    /// Closes every session so phones notice at once and reconnect to the next launch.
    func shutdown() {
        sessions.values.joined().forEach { $0.close() }
        // Give the connections a moment to send their close before the process exits.
        Thread.sleep(forTimeInterval: 0.15)
    }

    // MARK: Pairing

    func beginPairing() {
        let psk = SymmetricKey(size: .bits256).withUnsafeBytes { Data($0) }
        pendingPsk = psk
        pairingLink = PairingLink.make(
            macId: state.macId, name: macName, psk: psk, port: Wire.defaultPort,
            hosts: LocalAddresses.reachable())
        PairingStore.writePendingLink(pairingLink)
        pendingExpiry?.cancel()
        pendingExpiry = Task {
            try? await Task.sleep(for: .seconds(600))
            if !Task.isCancelled { endPairing() }
        }
    }

    func endPairing() {
        pendingPsk = nil
        pairingLink = nil
        PairingStore.writePendingLink(nil)
        pendingExpiry?.cancel()
        pendingExpiry = nil
    }

    func unpair(_ phone: PairedPhone) {
        state.phones.removeAll { $0.id == phone.id }
        sessions[phone.id]?.forEach { $0.close() }
        persist()
    }

    var launchAtLogin: Bool {
        get { SMAppService.mainApp.status == .enabled }
        set {
            do {
                if newValue {
                    try SMAppService.mainApp.register()
                } else {
                    try SMAppService.mainApp.unregister()
                }
            } catch {
                Log.app.error("login item: \(error.localizedDescription, privacy: .public)")
                status = "로그인 항목 설정 실패"
            }
            objectWillChange.send()
        }
    }

    // MARK: Sessions

    private func accept(_ connection: NWConnection) {
        // Unauthenticated peers get little: only local networks may connect, only a few
        // handshakes run at once, and each has six seconds.
        let candidates = pskCandidates()
        guard !candidates.isEmpty, let remote = Self.address(of: connection.endpoint),
            PeerFilter.allows(remote, localSubnets: LocalAddresses.subnets()),
            handshaking[PeerFilter.bucket(remote), default: 0] < Self.maxHandshakingPerPeer,
            handshaking.values.reduce(0, +) < Self.maxHandshaking
        else {
            connection.cancel()
            return
        }
        let address = PeerFilter.bucket(remote)
        handshaking[address, default: 0] += 1
        let session = PhoneSession(connection: connection)
        connection.start(queue: .global(qos: .userInitiated))
        Task {
            let timeout = Task {
                try? await Task.sleep(for: .seconds(6))
                if !Task.isCancelled { connection.cancel() }
            }
            var peerId: String?
            do {
                let peer: PhoneSession.Peer
                do {
                    defer {
                        handshaking[address] = handshaking[address].flatMap { $0 > 1 ? $0 - 1 : nil }
                    }
                    peer = try await session.handshake(
                        macName: macName, clipTs: clipTs, candidates: candidates)
                }
                timeout.cancel()
                guard established(session, peer) else { throw SessionError.authFailed }
                peerId = peer.phoneId
                while true {
                    handle(try await session.receive(), from: session)
                }
            } catch {
                timeout.cancel()
                Log.net.info("session ended: \(String(describing: error), privacy: .public)")
            }
            session.close()
            if let peerId {
                sessions[peerId]?.removeAll { $0 === session }
                if sessions[peerId]?.isEmpty ?? true {
                    sessions[peerId] = nil
                    connected.remove(peerId)
                    refreshStatus()
                }
            }
        }
    }

    private func pskCandidates() -> [PhoneSession.Candidate] {
        var candidates = state.phones.map { PhoneSession.Candidate(psk: $0.psk, phoneId: $0.id) }
        if let pendingPsk { candidates.append(PhoneSession.Candidate(psk: pendingPsk, phoneId: nil)) }
        return candidates
    }

    /// Raw bytes of the remote IPv4 or IPv6 address.
    private static func address(of endpoint: NWEndpoint) -> [UInt8]? {
        guard case .hostPort(let host, _) = endpoint else { return nil }
        switch host {
        case .ipv4(let address): return [UInt8](address.rawValue)
        case .ipv6(let address): return [UInt8](address.rawValue)
        default: return nil
        }
    }

    /// Returns false when the key that authenticated the handshake is no longer valid:
    /// the phone was unpaired, or the QR code was closed or used, while the handshake ran.
    private func established(_ session: PhoneSession, _ peer: PhoneSession.Peer) -> Bool {
        if let newKey = peer.newPairingKey {
            guard peer.psk == pendingPsk else { return false }
            // The QR code's key is used this once; from now on the phone must present
            // the key derived from this handshake.
            state.phones.removeAll { $0.id == peer.phoneId }
            state.phones.append(PairedPhone(id: peer.phoneId, name: peer.name, psk: newKey))
            endPairing()
            persist()
            otpPresenter.announce(title: "새 폰이 페어링되었습니다", detail: peer.name)
        } else {
            guard state.phones.contains(where: { $0.id == peer.phoneId && $0.psk == peer.psk })
            else { return false }
        }
        sessions[peer.phoneId, default: []].append(session)
        connected.insert(peer.phoneId)
        refreshStatus()
        Log.net.info("phone connected: \(peer.name, privacy: .public)")
        // Deliver what was copied here while the phone was away.
        if clipboardEnabled, let clip = localClip, clip.ts > min(peer.clipTs, Self.now()) {
            send(clip, to: session)
        }
        return true
    }

    private func handle(_ message: Message, from session: PhoneSession) {
        switch message.t {
        case "clip":
            guard clipboardEnabled, let text = message.text, !text.isEmpty,
                text.utf8.count <= Wire.maxClipBytes
            else { return }
            // A timestamp from the future would block later copies; cap it at now.
            let ts = min(message.ts ?? Self.now(), Self.now())
            guard ts > clipTs else {
                Log.app.info("clip from phone ignored (older than local)")
                return
            }
            clipTs = ts
            // Already there (a repeated send, or another sync tool delivered it first).
            guard text != pasteboardText else { return }
            watcher.write(text, sensitive: message.sensitive ?? false)
            pasteboardText = text
            Log.app.info("clip from phone: \(text.utf8.count) bytes")
        case "otp":
            guard otpEnabled, let code = Sanitize.verificationCode(message.code) else { return }
            // Anyone can text the phone, so codes are rate limited: at most one every two
            // seconds and five a minute may replace the clipboard.
            let now = Date()
            recentCodes.removeAll { now.timeIntervalSince($0) > 60 }
            guard recentCodes.count < 5, now.timeIntervalSince(recentCodes.last ?? .distantPast) >= 2
            else { return }
            recentCodes.append(now)
            watcher.write(code, sensitive: true, thisMacOnly: true)
            clipTs = Self.now()
            pasteboardText = code
            otpPresenter.present(code: code, sender: Sanitize.label(message.sender, maxLength: 40))
            Log.app.info("otp from phone")
        case "ping":
            Task { try? await session.send(Message(t: "pong")) }
        default:
            break
        }
    }

    private func localCopy(_ text: String, sensitive: Bool) {
        guard text != pasteboardText else { return }
        pasteboardText = text
        let clip = Clip(text: text, sensitive: sensitive, ts: Self.now())
        localClip = clip
        clipTs = clip.ts
        guard clipboardEnabled else { return }
        for session in sessions.values.joined() { send(clip, to: session) }
        Log.app.info("local copy: \(text.utf8.count) bytes -> \(self.sessions.count) phone(s)")
    }

    private func send(_ clip: Clip, to session: PhoneSession) {
        Task {
            do {
                try await session.send(
                    Message(t: "clip", text: clip.text, sensitive: clip.sensitive, ts: clip.ts))
            } catch {
                session.close()
            }
        }
    }

    // MARK: Helpers

    private func persist() {
        state.clipboardEnabled = clipboardEnabled
        state.otpEnabled = otpEnabled
        if phones != state.phones { phones = state.phones }
        PairingStore.save(state)
        refreshStatus()
    }

    private func refreshStatus() {
        if state.phones.isEmpty {
            status = "페어링 필요"
        } else if connected.isEmpty {
            status = "연결 대기 중"
        } else {
            status = "연결됨"
        }
    }

    private static func now() -> Int64 {
        Int64(Date().timeIntervalSince1970 * 1000)
    }
}
