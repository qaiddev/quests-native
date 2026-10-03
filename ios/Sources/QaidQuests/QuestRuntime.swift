#if os(iOS)
import Network
import UIKit
import os

/// A random id kept on this device, so qaid can group one person's responses without
/// knowing who they are. The same key as the QaidFeedback SDK, so feedback and quest
/// rows from one install match.
enum VisitorId {
    private static let key = "dev.qaid.feedback.visitorId"

    static var current: String {
        if let existing = UserDefaults.standard.string(forKey: key) { return existing }
        let fresh = UUID().uuidString.lowercased()
        UserDefaults.standard.set(fresh, forKey: key)
        return fresh
    }
}

extension QuestDeviceInfo {
    @MainActor
    static func current(appName: String) -> QuestDeviceInfo {
        let info = Bundle.main.infoDictionary ?? [:]
        let bundleName = info["CFBundleDisplayName"] as? String ?? info["CFBundleName"] as? String ?? "App"
        return QuestDeviceInfo(
            osVersion: UIDevice.current.systemVersion,
            model: modelIdentifier(),
            appName: appName.isEmpty ? bundleName : appName,
            appVersion: info["CFBundleShortVersionString"] as? String ?? "0",
            build: info["CFBundleVersion"] as? String ?? "0",
            locale: Locale.current.identifier,
            bundleIdentifier: Bundle.main.bundleIdentifier ?? ""
        )
    }

    /// "iPhone16,1"; the simulated model in the Simulator.
    private static func modelIdentifier() -> String {
        if let simulated = ProcessInfo.processInfo.environment["SIMULATOR_MODEL_IDENTIFIER"] {
            return "\(simulated) (Simulator)"
        }
        var system = utsname()
        uname(&system)
        return withUnsafeBytes(of: &system.machine) { raw in
            String(decoding: raw.prefix { $0 != 0 }, as: UTF8.self)
        }
    }
}

enum QuestLog {
    private static let logger = Logger(subsystem: "dev.qaid.quests", category: "QaidQuests")

    static func log(_ message: String) {
        logger.notice("\(message, privacy: .public)")
    }
}

enum Announcer {
    /// Polite announcements queue behind what VoiceOver is saying; assertive ones interrupt.
    static func announce(_ text: String, assertive: Bool) {
        guard UIAccessibility.isVoiceOverRunning else { return }
        let words = NSAttributedString(string: text, attributes: [.accessibilitySpeechQueueAnnouncement: !assertive])
        UIAccessibility.post(notification: .announcement, argument: words)
    }
}

/// Responses that couldn't reach qaid wait in `Application Support/QaidQuests/queue/`
/// (excluded from backup) and go out on `configure()`, on return to the foreground,
/// and when the network comes back.
@MainActor
final class QuestQueueRuntime {
    static let shared = QuestQueueRuntime()

    let store: OfflineQuestStore
    private var monitor: NWPathMonitor?
    private var foreground: NSObjectProtocol?
    private var flushing = false
    private var wasOnline = false

    init() {
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        store = OfflineQuestStore(root: support.appendingPathComponent("QaidQuests", isDirectory: true)
            .appendingPathComponent("queue", isDirectory: true))
    }

    /// Idempotent: `configure()` may be called more than once.
    func start() {
        if foreground == nil {
            foreground = NotificationCenter.default.addObserver(
                forName: UIApplication.willEnterForegroundNotification, object: nil, queue: .main
            ) { _ in
                Task { @MainActor in QuestQueueRuntime.shared.flush() }
            }
        }
        if monitor == nil {
            let monitor = NWPathMonitor()
            monitor.pathUpdateHandler = { path in
                let online = path.status == .satisfied
                Task { @MainActor in QuestQueueRuntime.shared.networkChanged(online: online) }
            }
            monitor.start(queue: DispatchQueue(label: "dev.qaid.quests.network"))
            self.monitor = monitor
        }
        flush()
    }

    private func networkChanged(online: Bool) {
        defer { wasOnline = online }
        if online && !wasOnline { flush() }
    }

    /// One flush at a time. The scan and file reads run off the main thread.
    func flush() {
        guard !flushing else { return }
        flushing = true
        let store = store
        Task {
            _ = await Task.detached(priority: .utility) {
                await OfflineFlusher.flush(store: store, transport: URLSessionQuestTransport()) { report in
                    Task { @MainActor in
                        QuestLog.log("offline queue: \(report.operation.rawValue) failed: \(report.error)")
                        QaidQuests.onError?(report)
                    }
                }
            }.value
            self.flushing = false
        }
    }
}
#endif
