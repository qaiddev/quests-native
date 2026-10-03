#if os(iOS)
import SwiftUI
import UIKit
@_exported import QaidQuestsCore

/// qaid.dev quests drawn with native SwiftUI controls: one question per step, answers
/// saved as they are given, sent in order, and kept on the device while offline.
///
/// ```swift
/// // once, at launch
/// QaidQuests.configure(QaidQuestsConfiguration(apiKey: "<embed key>", appName: "MyApp"))
///
/// // inline, in SwiftUI
/// QaidQuestView(questId: "<quest id>", screen: "Settings") { answers in … }
///
/// // or as a sheet, from UIKit
/// QaidQuests.present(questId: "<quest id>", screen: "Settings")
/// ```
///
/// Nothing is fetched or sent until a quest view appears. No analytics.
@MainActor
public enum QaidQuests {
    public private(set) static var configuration: QaidQuestsConfiguration?

    /// Every failure talking to qaid (a refused answer, an unreachable submit…), on the
    /// main thread. The person is never blocked by one; this is for the app's own logs.
    public static var onError: ((QaidQuestsErrorReport) -> Void)?

    /// Also sends any responses saved while offline, and keeps watching for the network.
    public static func configure(_ configuration: QaidQuestsConfiguration) {
        self.configuration = configuration
        QuestQueueRuntime.shared.start()
    }

    public static var isConfigured: Bool { configuration != nil }

    /// Show a quest in a sheet (the large detent).
    ///
    /// - Parameters:
    ///   - presenter: the controller to present from; the top-most one when nil.
    ///   - screen: where the person is ("Settings"), appended to the page URL and sent
    ///     as `metadata.screen`.
    ///   - metadata: extra top-level response metadata, e.g. `["feedbackId": id]`.
    public static func present(questId: String, from presenter: UIViewController? = nil, screen: String? = nil,
                               metadata: [String: String] = [:],
                               onComplete: (([String: QuestAnswer]) -> Void)? = nil) {
        guard configuration != nil else {
            assertionFailure("QaidQuests.configure(_:) must be called before present(questId:)")
            return
        }
        guard let presenter = presenter ?? UIApplication.shared.qaidTopViewController else { return }
        let controller = QaidQuestViewController(questId: questId, screen: screen, metadata: metadata,
                                                 onComplete: onComplete)
        controller.isSheet = true
        controller.modalPresentationStyle = .pageSheet
        if let sheet = controller.sheetPresentationController {
            sheet.detents = [.large()]
            sheet.prefersGrabberVisible = true
        }
        presenter.present(controller, animated: true)
    }

    /// Sends what the offline queue holds now. It also goes by itself on `configure`,
    /// on return to the foreground and when the network comes back.
    public static func flushOfflineQueue() {
        QuestQueueRuntime.shared.flush()
    }

    // MARK: Who and what, for every response

    /// Who is using the app. Sent with every response as `metadata.user`; nil fields are left out.
    nonisolated public static func setUser(id: String? = nil, email: String? = nil, name: String? = nil) {
        QuestMetadataStore.shared.setUser(QuestUser(id: id, email: email, name: name))
    }

    nonisolated public static func clearUser() {
        QuestMetadataStore.shared.setUser(nil)
    }

    /// One key/value sent with every response under `metadata.custom`; nil removes it.
    /// Up to 30 keys; keys are cut to 64 characters and values to 500.
    nonisolated public static func setMetadata(key: String, value: String?) {
        QuestMetadataStore.shared.setMetadata(key, value)
    }

    nonisolated public static func clearMetadata() {
        QuestMetadataStore.shared.clearMetadata()
    }

    static func environment(for config: QaidQuestsConfiguration) -> QuestEnvironment {
        QuestEnvironment(config: config, device: .current(appName: config.appName),
                         visitorId: nonBlank(config.visitorId) ?? VisitorId.current,
                         appMetadata: QuestMetadataStore.shared.snapshot(), transport: URLSessionQuestTransport(),
                         store: QuestQueueRuntime.shared.store)
    }
}

extension UIApplication {
    var qaidTopViewController: UIViewController? {
        let scenes = connectedScenes.compactMap { $0 as? UIWindowScene }
        let scene = scenes.first { $0.activationState == .foregroundActive } ?? scenes.first
        let window = scene?.windows.first { $0.isKeyWindow } ?? scene?.windows.first
        var top = window?.rootViewController
        while let presented = top?.presentedViewController, !presented.isBeingDismissed {
            top = presented
        }
        return top
    }
}
#endif
