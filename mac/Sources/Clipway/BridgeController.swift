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
    /// When on, copies that the source app marked as concealed (password managers do) stay on this Mac.
    @Published var skipSensitive = false { didSet { persist() } }
    /// Sound with the banner for codes, pairing and tests. Off unless switched on.
    @Published var soundEnabled = false {
        didSet {
            otpPresenter.soundEnabled = soundEnabled
            persist()
        }
    }

    private struct Clip {
        var text = ""
        /// Set for a copied picture; `text` is then empty.
        var image: (mime: String, data: Data)?
        var sensitive = false
        let ts: Int64
    }

    private var state = PairingStore.load()
    private let listener = BridgeListener()
    private let watcher = PasteboardWatcher()
    private let otpPresenter = OTPPresenter()
    let updater = Updater()
    /// A phone races several addresses, so it may briefly hold more than one session.
    private var sessions: [String: [PhoneSession]] = [:]
    private var pendingPsk: Data?
    private var isLoading = false
    private var pendingExpiry: Task<Void, Never>?
    /// Connections that have not finished the handshake yet, per remote address. One
    /// address cannot take every slot, so a single host cannot lock real phones out.
    private var handshaking: [[UInt8]: Int] = [:]
    /// Arrival times of recent verification codes, for rate limiting.
    private var recentCodes: [Date] = []
    private static let maxHandshakingPerPeer = 4
    private static let maxHandshaking = 64
    private static let maxSessionsPerPhone = 4
    /// The phone whose pairing was stored but which has not yet been seen using its new key.
    private var unconfirmedPairing: String?
    private var lastTestBanner = Date.distantPast
    private var pairingSignal: DispatchSourceSignal?
    private var updateSignal: DispatchSourceSignal?
    private var terminateSignal: DispatchSourceSignal?
    /// Last text copied on this Mac since launch.
    private var localClip: Clip?
    /// When the current pasteboard content was copied, on whichever device.
    /// Incoming clips older than this lose (last writer wins).
    private var clipTs: Int64 = 0
    /// What the pasteboard is known to hold. A change that reports the same text (another
    /// clipboard tool rewriting it, a repeated copy) is not sent again.
    private var pasteboardText: String?
    /// SHA-256 of the picture the pasteboard is known to hold, for the same purpose.
    private var pasteboardImage: Data?

    let macName = Host.current().localizedName ?? "Mac"

    func start() {
        // Assigning a setting saves all of them, so nothing may be saved until every
        // stored value has been read back.
        isLoading = true
        phones = state.phones
        clipboardEnabled = state.clipboardEnabled
        otpEnabled = state.otpEnabled
        skipSensitive = state.skipSensitive ?? false
        soundEnabled = state.soundEnabled ?? false
        isLoading = false
        otpPresenter.requestPermission()
        PairingStore.writePendingLink(nil)  // left over if the app quit while pairing
        watcher.onCopy = { [weak self] text, sensitive in self?.localCopy(text, sensitive: sensitive) }
        watcher.onImageCopy = { [weak self] mime, data, sensitive in
            self?.localImageCopy(mime: mime, data: data, sensitive: sensitive)
        }
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

        // `pkill -USR2 Clipway` installs the newest release, likewise without the menu.
        signal(SIGUSR2, SIG_IGN)
        let update = DispatchSource.makeSignalSource(signal: SIGUSR2, queue: .main)
        update.setEventHandler { [weak self] in
            MainActor.assumeIsolated {
                guard let updater = self?.updater else { return }
                Task { await updater.installLatest() }
            }
        }
        update.resume()
        updateSignal = update
        updater.onAvailable = { [weak self] version in
            self?.otpPresenter.announce(
                title: "Clipway \(version) 업데이트", detail: "메뉴바 아이콘을 눌러 설치하세요",
                symbol: "arrow.down.circle")
        }
        updater.start()
        // A menu bar app opens no window, so a first launch would look like nothing happened.
        if state.phones.isEmpty {
            otpPresenter.announce(
                title: "Clipway가 메뉴바에서 실행 중입니다", detail: "메뉴바의 폰 아이콘을 눌러 폰을 연결하세요",
                symbol: "menubar.arrow.up.rectangle")
        }

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
        unconfirmedPairing = nil
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

    /// A picture that is still arriving on one session.
    private struct IncomingImage {
        let mime: String
        let size: Int
        let sensitive: Bool
        let ts: Int64?
        var data = Data()
    }

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
            var pictureDeadline: Task<Void, Never>?
            do {
                let peer: PhoneSession.Peer
                do {
                    defer {
                        handshaking[address] = handshaking[address].flatMap { $0 > 1 ? $0 - 1 : nil }
                    }
                    peer = try await session.handshake(macName: macName, candidates: candidates)
                }
                timeout.cancel()
                guard established(session, peer) else { throw SessionError.authFailed }
                peerId = peer.phoneId
                try await session.confirm(macName: macName, clipTs: clipTs)
                deliverPending(to: session, peer: peer)

                var incoming: IncomingImage?
                while true {
                    let record = try await session.receive()
                    // The first record after a pairing shows that the phone has the new key.
                    if peer.newPairingKey != nil, unconfirmedPairing == peer.phoneId { endPairing() }
                    switch record {
                    case .message(let message) where message.t == "image":
                        guard let size = message.size, size > 0, size <= Wire.maxImageBytes,
                            let mime = message.mime, Wire.imageTypes.contains(mime)
                        else { throw SessionError.malformed }
                        incoming = IncomingImage(
                            mime: mime, size: size, sensitive: message.sensitive ?? false, ts: message.ts)
                        // A picture that never completes must not sit in memory.
                        pictureDeadline?.cancel()
                        pictureDeadline = Task {
                            try? await Task.sleep(for: .seconds(120))
                            if !Task.isCancelled { connection.cancel() }
                        }
                    case .message(let message):
                        handle(message, from: session, peer: peer)
                    case .chunk(let bytes):
                        guard var image = incoming, !bytes.isEmpty, image.data.count + bytes.count <= image.size
                        else { throw SessionError.malformed }
                        image.data.append(bytes)
                        if image.data.count < image.size {
                            incoming = image
                        } else {
                            incoming = nil
                            pictureDeadline?.cancel()
                            apply(image, peer: peer)
                        }
                    }
                }
            } catch {
                timeout.cancel()
                Log.net.info("session ended: \(String(describing: error), privacy: .public)")
            }
            pictureDeadline?.cancel()
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
            // From now on the phone must present the key derived from this handshake. The
            // QR key stays valid until the phone is seen using the new one: if this Mac's
            // answer is lost the phone can simply try again, instead of the two ending up
            // with different keys.
            sessions[peer.phoneId]?.forEach { $0.close() }
            state.phones.removeAll { $0.id == peer.phoneId }
            state.phones.append(PairedPhone(id: peer.phoneId, name: peer.name, psk: newKey))
            unconfirmedPairing = peer.phoneId
            persist()
            otpPresenter.announce(
                title: "새 폰이 페어링되었습니다",
                detail: "\(peer.name) · 확인 코드 \(PairingCode.code(for: newKey))")
        } else {
            guard state.phones.contains(where: { $0.id == peer.phoneId && $0.psk == peer.psk })
            else { return false }
            if unconfirmedPairing == peer.phoneId { endPairing() }
        }
        // A phone needs one session, briefly two. More than a few means it is not reading them.
        var open = sessions[peer.phoneId, default: []]
        while open.count >= Self.maxSessionsPerPhone { open.removeFirst().close() }
        sessions[peer.phoneId] = open + [session]
        connected.insert(peer.phoneId)
        refreshStatus()
        Log.net.info("phone connected: \(peer.name, privacy: .public)")
        return true
    }

    /// Delivers what was copied here while the phone was away.
    private func deliverPending(to session: PhoneSession, peer: PhoneSession.Peer) {
        guard clipboardEnabled, let clip = localClip, clip.ts > peer.clipTs,
            !(clip.sensitive && skipSensitive)
        else { return }
        send(clip, to: session)
    }

    private func handle(_ message: Message, from session: PhoneSession, peer: PhoneSession.Peer) {
        switch message.t {
        case "clip":
            guard let text = message.text, !text.isEmpty, text.utf8.count <= Wire.maxClipBytes
            else { return }
            let ts = Clock.translate(message.ts, offset: peer.clockOffset, now: Self.now()) ?? Self.now()
            guard ts > clipTs else {
                Log.app.info("clip from phone ignored (older than local)")
                return
            }
            // Recorded even with sync off, so the phone does not offer it again at every connect.
            clipTs = ts
            // Already there (a repeated send, or another sync tool delivered it first).
            guard clipboardEnabled, text != pasteboardText else { return }
            watcher.write(text, sensitive: message.sensitive ?? false)
            pasteboardText = text
            pasteboardImage = nil
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
            pasteboardImage = nil
            otpPresenter.present(code: code, sender: Sanitize.label(message.sender, maxLength: 40))
            Log.app.info("otp from phone")
        case "ping":
            Task { try? await session.send(Message(t: "pong")) }
        case "test":
            // "연결 테스트" on the phone: confirm on both screens that phone -> Mac works.
            // Answer first: drawing the banner would otherwise be counted as network delay.
            let showBanner = Date().timeIntervalSince(lastTestBanner) >= 1
            if showBanner { lastTestBanner = Date() }
            Task {
                try? await session.send(Message(t: "tested", n: message.n))
                if showBanner {
                    otpPresenter.announce(title: "폰 연결 테스트", detail: "\(peer.name)에서 보낸 신호를 받았습니다")
                }
            }
        default:
            break
        }
    }

    private func apply(_ image: IncomingImage, peer: PhoneSession.Peer) {
        let ts = Clock.translate(image.ts, offset: peer.clockOffset, now: Self.now()) ?? Self.now()
        guard ts > clipTs else { return }
        clipTs = ts
        guard clipboardEnabled else { return }
        let digest = Data(SHA256.hash(data: image.data))
        guard digest != pasteboardImage,
            watcher.writeImage(image.data, mime: image.mime, sensitive: image.sensitive)
        else { return }
        pasteboardImage = digest
        pasteboardText = nil
        Log.app.info("image from phone: \(image.data.count) bytes")
    }

    private func localImageCopy(mime: String, data: Data, sensitive: Bool) {
        let digest = Data(SHA256.hash(data: data))
        guard digest != pasteboardImage else { return }
        pasteboardImage = digest
        pasteboardText = nil
        broadcast(Clip(image: (mime, data), sensitive: sensitive, ts: Self.now()))
        Log.app.info("local image copy: \(data.count) bytes")
    }

    private func localCopy(_ text: String, sensitive: Bool) {
        guard text != pasteboardText else { return }
        pasteboardText = text
        pasteboardImage = nil
        // Text full of characters that JSON has to escape can outgrow a record; such a
        // copy stays here rather than making the phone drop the connection.
        let fits = ((try? Wire.encode(Message(t: "clip", text: text, sensitive: sensitive, ts: 0)))?.count ?? .max)
            <= Wire.maxFrame - 64
        var clip = Clip(sensitive: sensitive, ts: Self.now())
        clip.text = fits ? text : ""
        broadcast(clip, sendable: fits)
        Log.app.info("local copy: \(text.utf8.count) bytes")
    }

    /// Records a copy made on this Mac and sends it to the connected phones, unless it is
    /// held back (sensitive and the option is on, or too large to send).
    private func broadcast(_ clip: Clip, sendable: Bool = true) {
        clipTs = clip.ts
        guard sendable, !(clip.sensitive && skipSensitive) else {
            // The older copy must not be delivered later in its place.
            localClip = nil
            return
        }
        localClip = clip
        guard clipboardEnabled else { return }
        for session in sessions.values.joined() { send(clip, to: session) }
    }

    private func send(_ clip: Clip, to session: PhoneSession) {
        Task {
            do {
                if let image = clip.image {
                    try await session.sendImage(
                        mime: image.mime, data: image.data, sensitive: clip.sensitive, ts: clip.ts)
                } else {
                    try await session.send(
                        Message(t: "clip", text: clip.text, sensitive: clip.sensitive, ts: clip.ts))
                }
            } catch {
                session.close()
            }
        }
    }

    // MARK: Helpers

    private func persist() {
        guard !isLoading else { return }
        state.clipboardEnabled = clipboardEnabled
        state.otpEnabled = otpEnabled
        state.skipSensitive = skipSensitive
        state.soundEnabled = soundEnabled
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
