import Foundation

struct PairedPhone: Codable, Identifiable, Equatable {
    var id: String
    var name: String
    var psk: Data
}

struct StoredState: Codable {
    var macId = UUID().uuidString.lowercased()
    var phones: [PairedPhone] = []
    var clipboardEnabled = true
    var otpEnabled = true
    /// Optional so that state files written before this setting existed still load.
    var skipSensitive: Bool?
}

/// Persists pairings in ~/Library/Application Support/Clipway/state.json (0600).
/// A file is used instead of the Keychain because the app is ad-hoc signed and
/// every rebuild would otherwise trigger a Keychain access prompt.
enum PairingStore {
    private static var fileURL: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return base.appending(path: "Clipway/state.json")
    }

    /// Holds the pairing link while a QR code is on screen; removed when pairing ends.
    static var pendingLinkURL: URL {
        fileURL.deletingLastPathComponent().appending(path: "pending-pairing-link.txt")
    }

    static func writePendingLink(_ link: String?) {
        try? FileManager.default.removeItem(at: pendingLinkURL)
        guard let link else { return }
        FileManager.default.createFile(
            atPath: pendingLinkURL.path, contents: Data(link.utf8),
            attributes: [.posixPermissions: 0o600])
    }

    static func load() -> StoredState {
        guard let data = try? Data(contentsOf: fileURL),
            let state = try? JSONDecoder().decode(StoredState.self, from: data)
        else {
            let fresh = StoredState()
            save(fresh)
            return fresh
        }
        return state
    }

    static func save(_ state: StoredState) {
        do {
            let directory = fileURL.deletingLastPathComponent()
            try FileManager.default.createDirectory(
                at: directory, withIntermediateDirectories: true,
                attributes: [.posixPermissions: 0o700])
            try JSONEncoder().encode(state).write(to: fileURL, options: .atomic)
            try FileManager.default.setAttributes(
                [.posixPermissions: 0o600], ofItemAtPath: fileURL.path)
        } catch {
            Log.app.error("state save failed: \(error.localizedDescription, privacy: .public)")
        }
    }
}
