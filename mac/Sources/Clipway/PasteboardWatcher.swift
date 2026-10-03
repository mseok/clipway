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
    private static let jpeg = NSPasteboard.PasteboardType("public.jpeg")

    private let pasteboard = NSPasteboard.general
    private var lastChangeCount = NSPasteboard.general.changeCount
    private var timer: Timer?
    var onCopy: ((_ text: String, _ sensitive: Bool) -> Void)?
    var onImageCopy: ((_ mime: String, _ data: Data) -> Void)?

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
        // Text wins when a copy offers both (a spreadsheet cell also carries a picture of itself).
        if let text = pasteboard.string(forType: .string), !text.isEmpty {
            if text.utf8.count <= Wire.maxClipBytes { onCopy?(text, types.contains(Self.concealed)) }
            return
        }
        if let (mime, data) = image(), data.count <= Wire.maxImageBytes {
            onImageCopy?(mime, data)
        }
    }

    /// The copied picture as PNG or JPEG; other bitmap formats are converted to PNG.
    private func image() -> (String, Data)? {
        if let png = pasteboard.data(forType: .png) { return ("image/png", png) }
        if let jpeg = pasteboard.data(forType: Self.jpeg) { return ("image/jpeg", jpeg) }
        if let tiff = pasteboard.data(forType: .tiff),
            let png = NSBitmapImageRep(data: tiff)?.representation(using: .png, properties: [:])
        {
            return ("image/png", png)
        }
        return nil
    }

    /// Returns false when the data is not an image this Mac can decode.
    func writeImage(_ data: Data, mime: String) -> Bool {
        guard let tiff = NSImage(data: data)?.tiffRepresentation else { return false }
        pasteboard.clearContents()
        if mime == "image/png" { pasteboard.setData(data, forType: .png) }
        pasteboard.setData(tiff, forType: .tiff)
        lastChangeCount = pasteboard.changeCount
        return true
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
