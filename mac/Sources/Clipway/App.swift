import AppKit
import BridgeCore
import CoreImage.CIFilterBuiltins
import SwiftUI

@main
struct ClipwayApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    @ObservedObject private var controller = BridgeController.shared

    var body: some Scene {
        MenuBarExtra {
            PanelView().environmentObject(controller).environmentObject(controller.updater)
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
    @EnvironmentObject private var updater: Updater

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
                    VStack(alignment: .leading, spacing: 1) {
                        Text(phone.name)
                        Text("확인 코드 \(PairingCode.code(for: phone.psk))")
                            .font(.caption2).foregroundStyle(.secondary)
                    }
                    Spacer()
                    Button("해제") { controller.unpair(phone) }
                        .buttonStyle(.borderless)
                        .font(.caption)
                }
            }
            Divider()
            Toggle("클립보드 동기화", isOn: $controller.clipboardEnabled)
            Toggle("인증번호 받기", isOn: $controller.otpEnabled)
            Toggle("민감한 항목은 보내지 않기", isOn: $controller.skipSensitive)
                .help("비밀번호 관리자처럼 복사한 내용을 '민감함'으로 표시하는 앱의 복사는 폰으로 보내지 않습니다.")
            Toggle("알림 소리", isOn: $controller.soundEnabled)
            Toggle(
                "로그인 시 실행",
                isOn: Binding(
                    get: { controller.launchAtLogin },
                    set: { controller.launchAtLogin = $0 }))
            Divider()
            pairing
            Divider()
            update
            HStack {
                Text("버전 \(updater.currentVersion)").font(.caption).foregroundStyle(.secondary)
                Button(updater.checking ? "확인하는 중…" : "업데이트 확인") {
                    Task { await updater.check(userInitiated: true) }
                }
                .buttonStyle(.borderless)
                .font(.caption)
                .disabled(updater.checking || updater.phase == .installing)
                Spacer()
                Button("종료") { NSApp.terminate(nil) }
            }
        }
        .toggleStyle(.switch)
        .controlSize(.small)
        .padding(14)
        .frame(width: 300)
    }

    @ViewBuilder private var update: some View {
        switch updater.phase {
        case .idle:
            if updater.upToDate {
                Text("최신 버전입니다.").font(.caption).foregroundStyle(.secondary)
            }
        case .available(let release):
            HStack {
                Text("새 버전 \(release.version)")
                Spacer()
                Button("업데이트") { updater.install() }
            }
        case .installing:
            HStack(spacing: 8) {
                ProgressView().controlSize(.small)
                Text("업데이트를 설치하는 중…")
            }
        case .failed(let message):
            Text(message).font(.caption).foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
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
