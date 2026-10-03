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
        watcher.onCopy = { [weak self] text, sensitive in self?.localCopy(text, sensitive: sensitive) }
        watcher.start()
        listener.start(
            port: Wire.defaultPort, macId: state.macId, name: macName,
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
        let session = PhoneSession(connection: connection)
        connection.start(queue: .global(qos: .userInitiated))
        Task {
            let timeout = Task {
                try? await Task.sleep(for: .seconds(10))
                if !Task.isCancelled { connection.cancel() }
            }
            var peerId: String?
            do {
                let peer = try await session.handshake(
                    macId: state.macId, macName: macName, clipTs: clipTs
                ) { [weak self] phoneId in
                    await self?.pskCandidates(for: phoneId) ?? []
                }
                timeout.cancel()
                peerId = peer.phoneId
                established(session, peer)
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

    private func pskCandidates(for phoneId: String) -> [Data] {
        var keys: [Data] = []
        if let pendingPsk { keys.append(pendingPsk) }
        if let phone = state.phones.first(where: { $0.id == phoneId }) { keys.append(phone.psk) }
        return keys
    }

    private func established(_ session: PhoneSession, _ peer: PhoneSession.Peer) {
        if peer.psk == pendingPsk {
            state.phones.removeAll { $0.id == peer.phoneId }
            state.phones.append(PairedPhone(id: peer.phoneId, name: peer.name, psk: peer.psk))
            endPairing()
            persist()
        }
        sessions[peer.phoneId, default: []].append(session)
        connected.insert(peer.phoneId)
        refreshStatus()
        Log.net.info("phone connected: \(peer.name, privacy: .public)")
        // Deliver what was copied here while the phone was away.
        if clipboardEnabled, let clip = localClip, clip.ts > peer.clipTs {
            send(clip, to: session)
        }
    }

    private func handle(_ message: Message, from session: PhoneSession) {
        switch message.t {
        case "clip":
            guard clipboardEnabled, let text = message.text, !text.isEmpty,
                text.utf8.count <= Wire.maxClipBytes
            else { return }
            let ts = message.ts ?? Self.now()
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
            guard otpEnabled, let code = message.code, !code.isEmpty else { return }
            watcher.write(code, sensitive: true, thisMacOnly: true)
            clipTs = Self.now()
            pasteboardText = code
            otpPresenter.present(code: code, sender: message.sender ?? "")
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
