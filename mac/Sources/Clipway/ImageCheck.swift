import AppKit
import ImageIO

/// Checks a received picture before it is offered to other apps: it must really be of
/// the declared type and of a sane size. Only the header is read; nothing is decoded.
enum ImageCheck {
    static let maxPixels = 40_000_000
    static let maxSide = 30_000

    private static let types: [String: String] = [
        "image/png": "public.png", "image/jpeg": "public.jpeg", "image/gif": "com.compuserve.gif",
        "image/webp": "org.webmproject.webp", "image/heic": "public.heic", "image/heif": "public.heif",
        "image/bmp": "com.microsoft.bmp",
    ]

    static func pasteboardType(of data: Data, mime: String) -> NSPasteboard.PasteboardType? {
        let options = [kCGImageSourceShouldCache: false] as CFDictionary
        guard let expected = types[mime],
            let source = CGImageSourceCreateWithData(data as CFData, options),
            let actual = CGImageSourceGetType(source) as String?, actual == expected,
            let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, options) as? [CFString: Any],
            let width = properties[kCGImagePropertyPixelWidth] as? Int,
            let height = properties[kCGImagePropertyPixelHeight] as? Int,
            width > 0, height > 0, width <= maxSide, height <= maxSide, width * height <= maxPixels
        else { return nil }
        return NSPasteboard.PasteboardType(expected)
    }
}

/// Supplies TIFF, which some older apps expect, only when an app actually asks for it,
/// so a received picture is not decoded until the person pastes it.
final class LazyTIFF: NSObject, NSPasteboardItemDataProvider {
    private let source: Data

    init(source: Data) {
        self.source = source
    }

    func pasteboard(
        _ pasteboard: NSPasteboard?, item: NSPasteboardItem,
        provideDataForType type: NSPasteboard.PasteboardType
    ) {
        guard type == .tiff, let tiff = NSBitmapImageRep(data: source)?.tiffRepresentation else { return }
        item.setData(tiff, forType: .tiff)
    }
}
