import AppKit
import BridgeCore
import CryptoKit
import Foundation

enum UpdateError: Error {
    case unavailable
    case notSigned
    case damaged
    case notInstalled
}

/// Finds a newer release, checks that it was signed with the release key and installs it
/// over the running app. Looking is automatic; installing takes one click.
@MainActor
final class Updater: ObservableObject {
    enum Phase: Equatable {
        case idle
        case available(ReleaseManifest)
        case installing
        case failed(String)
    }

    @Published private(set) var phase: Phase = .idle
    @Published private(set) var checking = false
    /// True after a check that the user asked for found nothing newer.
    @Published private(set) var upToDate = false
    /// Called once for each newer version that is found.
    var onAvailable: ((String) -> Void)?

    let currentVersion =
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0"
    /// `defaults write dev.mseok.clipway ReleasesURL <url>` points the app at another
    /// server for testing. A release from there still needs the release key's signature.
    private let releases =
        UserDefaults.standard.string(forKey: "ReleasesURL").flatMap(URL.init(string:))
        ?? URL(string: "https://github.com/mseok/clipway/releases")!
    private let session: URLSession = {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 30
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        return URLSession(configuration: configuration)
    }()
    private var schedule: Task<Void, Never>?

    /// False when the app was started from the disk image or straight from the download
    /// folder: it then sits on a read-only volume and cannot be replaced.
    static let installed: Bool = {
        let app = Bundle.main.bundleURL
        return app.pathExtension == "app" && !app.path.contains("/AppTranslocation/")
            && FileManager.default.isWritableFile(atPath: app.deletingLastPathComponent().path)
    }()

    /// Checks shortly after launch and then once a day.
    func start() {
        schedule = Task {
            try? await Task.sleep(for: .seconds(20))
            while !Task.isCancelled {
                await check()
                try? await Task.sleep(for: .seconds(24 * 3600))
            }
        }
    }

    func check(userInitiated: Bool = false) async {
        guard !checking, phase != .installing else { return }
        checking = true
        upToDate = false
        defer { checking = false }
        do {
            let latest = releases.appending(path: "latest/download")
            let manifest = try await fetch(
                latest.appending(path: "release.json"), limit: ReleaseSigning.maxManifestBytes)
            let signature = try await fetch(latest.appending(path: "release.json.sig"), limit: 256)
            guard let release = ReleaseSigning.verify(manifest: manifest, signature: signature)
            else { throw UpdateError.notSigned }
            // Only ever forwards: an old release, replayed with its own valid signature, is ignored.
            guard let offered = AppVersion(release.version), let current = AppVersion(currentVersion),
                offered > current
            else {
                phase = .idle
                upToDate = userInitiated
                return
            }
            let known = phase
            phase = .available(release)
            if known != phase { onAvailable?(release.version) }
        } catch {
            Log.app.info("update check failed: \(String(describing: error), privacy: .public)")
            // An update that is already on offer stays on offer.
            guard userInitiated else { return }
            if case .available = phase { return }
            // Only a failed connection is the network's fault; anything else came from the server.
            phase = .failed(
                error is URLError
                    ? "업데이트를 확인하지 못했습니다. 인터넷 연결을 확인해 주세요."
                    : "업데이트 정보를 받지 못했습니다. 잠시 후 다시 시도해 주세요.")
        }
    }

    /// `pkill -USR2 -x Clipway`: installs the newest release without the menu (scripted setups).
    func installLatest() async {
        await check()
        install()
    }

    func install() {
        guard case .available(let release) = phase else { return }
        phase = .installing
        Task {
            do {
                try await replaceApp(with: release)
                relaunch()
            } catch {
                Log.app.error("update failed: \(String(describing: error), privacy: .public)")
                phase = .failed(Self.explain(error))
            }
        }
    }

    private func replaceApp(with release: ReleaseManifest) async throws {
        let app = Bundle.main.bundleURL
        guard Self.installed else { throw UpdateError.notInstalled }

        let archive = try await fetch(
            releases.appending(path: "download/v\(release.version)/\(release.mac.file)"),
            limit: release.mac.size)
        let digest = SHA256.hash(data: archive).map { String(format: "%02x", $0) }.joined()
        guard archive.count == release.mac.size, digest == release.mac.sha256
        else { throw UpdateError.damaged }

        // On the same volume as the app, so that the swap below is a rename.
        let staging = try FileManager.default.url(
            for: .itemReplacementDirectory, in: .userDomainMask, appropriateFor: app, create: true)
        defer { try? FileManager.default.removeItem(at: staging) }
        let zip = staging.appending(path: "update.zip")
        try archive.write(to: zip)
        try await Self.unzip(zip, into: staging)
        let fresh = staging.appending(path: app.lastPathComponent)
        let info = NSDictionary(contentsOf: fresh.appending(path: "Contents/Info.plist"))
        guard info?["CFBundleIdentifier"] as? String == Bundle.main.bundleIdentifier,
            info?["CFBundleShortVersionString"] as? String == release.version
        else { throw UpdateError.damaged }
        _ = try FileManager.default.replaceItemAt(app, withItemAt: fresh)
    }

    private static func unzip(_ archive: URL, into directory: URL) async throws {
        try await Task.detached {
            let ditto = Process()
            ditto.executableURL = URL(fileURLWithPath: "/usr/bin/ditto")
            ditto.arguments = ["-x", "-k", archive.path, directory.path]
            try ditto.run()
            ditto.waitUntilExit()
            guard ditto.terminationStatus == 0 else { throw UpdateError.damaged }
        }.value
    }

    /// Quits, and has a shell open the new copy once this process is gone.
    private func relaunch() {
        let shell = Process()
        shell.executableURL = URL(fileURLWithPath: "/bin/sh")
        shell.arguments = [
            "-c", #"while kill -0 "$1" 2>/dev/null; do sleep 0.2; done; exec /usr/bin/open "$2""#,
            "sh", String(ProcessInfo.processInfo.processIdentifier), Bundle.main.bundleURL.path,
        ]
        do {
            try shell.run()
            NSApp.terminate(nil)
        } catch {
            phase = .failed("새 버전을 설치했습니다. Clipway를 종료한 뒤 다시 열어 주세요.")
        }
    }

    /// Reads at most `limit` bytes; a longer answer is an error, not a bigger download.
    private func fetch(_ url: URL, limit: Int) async throws -> Data {
        let (bytes, response) = try await session.bytes(from: url)
        guard (response as? HTTPURLResponse)?.statusCode == 200,
            response.expectedContentLength <= Int64(limit)
        else { throw UpdateError.unavailable }
        var data = Data()
        for try await byte in bytes {
            guard data.count < limit else { throw UpdateError.unavailable }
            data.append(byte)
        }
        return data
    }

    private static func explain(_ error: Error) -> String {
        switch error {
        case UpdateError.notInstalled:
            return "Clipway를 응용 프로그램 폴더로 옮긴 뒤 다시 실행하면 업데이트할 수 있습니다."
        case UpdateError.damaged, UpdateError.notSigned:
            return "내려받은 파일이 릴리스와 다릅니다. 잠시 후 다시 시도해 주세요."
        case is URLError:
            return "업데이트를 내려받지 못했습니다. 인터넷 연결을 확인해 주세요."
        case UpdateError.unavailable:
            return "업데이트를 내려받지 못했습니다. 잠시 후 다시 시도해 주세요."
        default:
            return "업데이트를 설치하지 못했습니다. 응용 프로그램 폴더에 쓸 수 있는지 확인해 주세요."
        }
    }
}
