import AppKit
import CoreImage.CIFilterBuiltins
import SwiftUI

@main
struct ClipwayApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    @ObservedObject private var controller = BridgeController.shared

    var body: some Scene {
        MenuBarExtra {
            PanelView().environmentObject(controller)
        } label: {
            Image(systemName: controller.connected.isEmpty ? "iphone" : "iphone.radiowaves.left.and.right")
        }
        .menuBarExtraStyle(.window)
    }
}

final class AppDelegate: NSObject, NSApplicationDelegate {
    func applicationDidFinishLaunching(_ notification: Notification) {
        BridgeController.shared.start()
    }

    func applicationWillTerminate(_ notification: Notification) {
        BridgeController.shared.shutdown()
    }
}

struct PanelView: View {
    @EnvironmentObject private var controller: BridgeController

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("Clipway").font(.headline)
                Spacer()
                Text(controller.status).font(.caption).foregroundStyle(.secondary)
            }
            if controller.phones.isEmpty {
                Text("페어링된 폰이 없습니다.").font(.callout).foregroundStyle(.secondary)
            }
            ForEach(controller.phones) { phone in
                HStack(spacing: 8) {
                    Circle()
                        .fill(controller.connected.contains(phone.id) ? Color.green : Color.secondary.opacity(0.4))
                        .frame(width: 8, height: 8)
                    Text(phone.name)
                    Spacer()
                    Button("해제") { controller.unpair(phone) }
                        .buttonStyle(.borderless)
                        .font(.caption)
                }
            }
            Divider()
            Toggle("클립보드 동기화", isOn: $controller.clipboardEnabled)
            Toggle("인증번호 받기", isOn: $controller.otpEnabled)
            Toggle(
                "로그인 시 실행",
                isOn: Binding(
                    get: { controller.launchAtLogin },
                    set: { controller.launchAtLogin = $0 }))
            Divider()
            pairing
            Divider()
            HStack {
                Spacer()
                Button("종료") { NSApp.terminate(nil) }
            }
        }
        .toggleStyle(.switch)
        .controlSize(.small)
        .padding(14)
        .frame(width: 300)
    }

    @ViewBuilder private var pairing: some View {
        if let link = controller.pairingLink, let image = QRCode.image(for: link) {
            VStack(spacing: 8) {
                Image(nsImage: image)
                    .interpolation(.none)
                    .resizable()
                    .frame(width: 200, height: 200)
                    .padding(10)
                    .background(Color.white)
                    .clipShape(RoundedRectangle(cornerRadius: 8))
                Text("폰의 Clipway 앱에서 이 QR을 스캔하세요.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Button("닫기") { controller.endPairing() }
            }
            .frame(maxWidth: .infinity)
        } else {
            Button("새 폰 페어링") { controller.beginPairing() }
        }
    }
}

enum QRCode {
    static func image(for string: String) -> NSImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(string.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage?.transformed(by: CGAffineTransform(scaleX: 8, y: 8))
        else { return nil }
        let representation = NSCIImageRep(ciImage: output)
        let image = NSImage(size: representation.size)
        image.addRepresentation(representation)
        return image
    }
}
