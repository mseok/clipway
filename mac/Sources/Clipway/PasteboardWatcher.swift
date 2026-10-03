import AppKit
import BridgeCore

/// Polls the general pasteboard (macOS has no change notification) and reports
/// text the user copied on this Mac. Writes made through `write` are not reported.
@MainActor
final class PasteboardWatcher {
    // nspasteboard.org conventions honoured by password managers and clipboard tools.
    private static let concealed = NSPasteboard.PasteboardType("org.nspasteboard.ConcealedType")
    private static let transient = NSPasteboard.PasteboardType("org.nspasteboard.TransientType")
    // Universal Clipboard content from another Apple device. Reading it would
    // trigger a remote fetch, and that device forwards its own copies anyway.
    private static let remote = NSPasteboard.PasteboardType("com.apple.is-remote-clipboard")

    private let pasteboard = NSPasteboard.general
    private var lastChangeCount = NSPasteboard.general.changeCount
    private var timer: Timer?
    var onCopy: ((_ text: String, _ sensitive: Bool) -> Void)?

    func start() {
        lastChangeCount = pasteboard.changeCount
        timer = Timer.scheduledTimer(withTimeInterval: 0.4, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.poll() }
        }
    }

    private func poll() {
        guard pasteboard.changeCount != lastChangeCount else { return }
        lastChangeCount = pasteboard.changeCount
        let types = pasteboard.types ?? []
        if types.contains(Self.transient) || types.contains(Self.remote) { return }
        guard let text = pasteboard.string(forType: .string), !text.isEmpty,
            text.utf8.count <= Wire.maxClipBytes
        else { return }
        onCopy?(text, types.contains(Self.concealed))
    }

    func write(_ text: String, sensitive: Bool, thisMacOnly: Bool = false) {
        if thisMacOnly {
            pasteboard.prepareForNewContents(with: .currentHostOnly)
        } else {
            pasteboard.clearContents()
        }
        pasteboard.setString(text, forType: .string)
        if sensitive {
            pasteboard.setString("", forType: Self.concealed)
        }
        lastChangeCount = pasteboard.changeCount
    }
}
