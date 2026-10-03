import AppKit
import SwiftUI
import UserNotifications

/// Shows a verification code. The code itself is copied by the controller.
///
/// macOS refuses Notification Center access to ad-hoc signed apps ("Notifications are
/// not allowed for this application"), so the default presentation is a small banner
/// window of our own. Notification Center is used only when macOS grants it.
@MainActor
final class OTPPresenter: NSObject, UNUserNotificationCenterDelegate {
    private var notificationsGranted = false
    var soundEnabled = false
    private var panel: NSPanel?
    private var dismissal: Task<Void, Never>?

    func requestPermission() {
        let center = UNUserNotificationCenter.current()
        center.delegate = self
        center.requestAuthorization(options: [.alert, .sound]) { granted, _ in
            Task { @MainActor in self.notificationsGranted = granted }
        }
    }

    func present(code: String, sender: String) {
        let title = "인증번호 \(code)"
        let detail = sender.isEmpty ? "클립보드에 복사했습니다" : "\(sender) · 클립보드에 복사했습니다"
        if notificationsGranted {
            let content = UNMutableNotificationContent()
            content.title = title
            content.body = detail
            content.sound = soundEnabled ? .default : nil
            UNUserNotificationCenter.current().add(
                UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil))
        } else {
            showBanner(title: title, detail: detail)
        }
    }

    /// Banner for events the user should notice even without Notification Center access.
    func announce(title: String, detail: String) {
        showBanner(title: title, detail: detail, symbol: "iphone.badge.checkmark")
    }

    private func showBanner(title: String, detail: String, symbol: String = "key.fill") {
        panel?.close()
        let view = NSHostingView(rootView: BannerView(title: title, detail: detail, symbol: symbol))
        let size = view.fittingSize
        let panel = NSPanel(
            contentRect: NSRect(origin: .zero, size: size),
            styleMask: [.borderless, .nonactivatingPanel], backing: .buffered, defer: false)
        panel.level = .statusBar
        panel.isOpaque = false
        panel.backgroundColor = .clear
        panel.hasShadow = true
        panel.ignoresMouseEvents = true
        panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary]
        panel.contentView = view
        if let frame = NSScreen.main?.visibleFrame {
            panel.setFrameOrigin(NSPoint(x: frame.maxX - size.width - 16, y: frame.maxY - size.height - 12))
        }
        panel.orderFrontRegardless()
        if soundEnabled { NSSound(named: "Glass")?.play() }
        self.panel = panel
        Log.app.info(
            "banner shown: visible=\(panel.isVisible) frame=\(NSStringFromRect(panel.frame), privacy: .public)")

        dismissal?.cancel()
        dismissal = Task {
            try? await Task.sleep(for: .seconds(8))
            if !Task.isCancelled { panel.close() }
        }
    }

    // A menu bar app counts as foreground, so banners must be requested explicitly.
    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter, willPresent notification: UNNotification
    ) async -> UNNotificationPresentationOptions {
        [.banner, .sound, .list]
    }
}

private struct BannerView: View {
    let title: String
    let detail: String
    let symbol: String

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: symbol).font(.title2).foregroundStyle(.secondary)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.title3.weight(.semibold).monospacedDigit())
                Text(detail).font(.callout).foregroundStyle(.secondary)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .frame(minWidth: 280, alignment: .leading)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 14))
        .padding(8)
    }
}
