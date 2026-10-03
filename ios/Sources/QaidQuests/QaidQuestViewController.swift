#if os(iOS)
import SwiftUI
import UIKit

/// A quest for UIKit apps: embed it as a child controller, or let
/// `QaidQuests.present(questId:)` show it as a sheet.
///
/// Embedded, it draws inline and shows a close button only when `onClose` is set
/// before it appears. Presented, it closes itself (×, Close, or a swipe down).
public final class QaidQuestViewController: UIViewController, UIAdaptivePresentationControllerDelegate {
    public let questId: String
    private let screen: String?
    private let metadata: [String: String]
    private let onComplete: (([String: QuestAnswer]) -> Void)?
    /// Called once when the quest is closed. Closing never submits.
    public var onClose: (() -> Void)?
    var isSheet = false
    private var closed = false

    public init(questId: String, screen: String? = nil, metadata: [String: String] = [:],
                onComplete: (([String: QuestAnswer]) -> Void)? = nil, onClose: (() -> Void)? = nil) {
        self.questId = questId
        self.screen = screen
        self.metadata = metadata
        self.onComplete = onComplete
        self.onClose = onClose
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        nil
    }

    override public func viewDidLoad() {
        super.viewDidLoad()
        let closable = isSheet || onClose != nil
        let root = QaidQuestView(questId: questId, screen: screen, metadata: metadata, onComplete: onComplete,
                                 onClose: closable ? { [weak self] in self?.close() } : nil, sheet: isSheet)
        let host = UIHostingController(rootView: root)
        addChild(host)
        host.view.translatesAutoresizingMaskIntoConstraints = false
        host.view.backgroundColor = .clear
        view.addSubview(host.view)
        NSLayoutConstraint.activate([
            host.view.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            host.view.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            host.view.topAnchor.constraint(equalTo: view.topAnchor),
            host.view.bottomAnchor.constraint(equalTo: view.bottomAnchor),
        ])
        host.didMove(toParent: self)
        // A sheet is a dialog: VoiceOver stays inside it.
        view.accessibilityViewIsModal = isSheet
    }

    override public func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        presentationController?.delegate = self
    }

    private func close() {
        if isSheet && presentingViewController != nil {
            dismiss(animated: true) { [weak self] in self?.finish() }
        } else {
            finish()
        }
    }

    private func finish() {
        guard !closed else { return }
        closed = true
        onClose?()
    }

    public func presentationControllerDidDismiss(_ presentationController: UIPresentationController) {
        finish()
    }
}
#endif
