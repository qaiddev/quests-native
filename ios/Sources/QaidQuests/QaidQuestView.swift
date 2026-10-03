#if os(iOS)
import SwiftUI

/// A quest drawn inline. It loads when it appears; nothing is sent before.
///
/// `onComplete` gets the submitted answers (hidden questions left out) once qaid has
/// them, or once they are safely in the offline queue. With `onClose`, a close button
/// shows in the header and a Close button on the thank-you screen.
public struct QaidQuestView: View {
    @StateObject private var model: QuestViewModel
    private let onClose: (() -> Void)?
    private let sheet: Bool

    public init(questId: String, screen: String? = nil, metadata: [String: String] = [:],
                onComplete: (([String: QuestAnswer]) -> Void)? = nil, onClose: (() -> Void)? = nil) {
        self.init(questId: questId, screen: screen, metadata: metadata, onComplete: onComplete, onClose: onClose,
                  sheet: false)
    }

    init(questId: String, screen: String?, metadata: [String: String],
         onComplete: (([String: QuestAnswer]) -> Void)?, onClose: (() -> Void)?, sheet: Bool) {
        _model = StateObject(wrappedValue: QuestViewModel(questId: questId, screen: screen, metadata: metadata,
                                                          onComplete: onComplete))
        self.onClose = onClose
        self.sheet = sheet
    }

    public var body: some View {
        QuestRoot(model: model, onClose: onClose, sheet: sheet)
            .task { await model.controller?.start() }
            .onDisappear { model.controller?.close() }
    }
}

@MainActor
final class QuestViewModel: ObservableObject {
    let controller: QuestController?

    init(questId: String, screen: String?, metadata: [String: String],
         onComplete: (([String: QuestAnswer]) -> Void)?) {
        guard let config = QaidQuests.configuration else {
            QuestLog.log("QaidQuests.configure(_:) must be called before a quest is shown")
            controller = nil
            return
        }
        let controller = QuestController(questId: questId, screen: screen, metadata: metadata,
                                         environment: QaidQuests.environment(for: config))
        self.controller = controller
        controller.onChange = { [weak self] in self?.objectWillChange.send() }
        controller.onAnnounce = { Announcer.announce($0, assertive: $1) }
        controller.onComplete = onComplete
        controller.onError = { QaidQuests.onError?($0) }
        controller.onLog = { QuestLog.log($0) }
    }
}

/// Where VoiceOver focus goes: the step's label or end screen's title, or the control
/// whose answer failed.
enum QuestFocus: Hashable {
    case title, control
}

struct QuestRoot: View {
    @ObservedObject var model: QuestViewModel
    let onClose: (() -> Void)?
    let sheet: Bool
    @Environment(\.colorScheme) private var scheme
    @ScaledMetric(relativeTo: .body) private var scaled: CGFloat = 100
    @AccessibilityFocusState private var focus: QuestFocus?

    var body: some View {
        if let controller = model.controller {
            let style = controller.style
            let look = QuestLook(style: style, systemDark: scheme == .dark, scale: scaled / 100)
            screen(controller, look)
                .padding(look.metrics.cardPadding)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
                .background(card(look))
                .modifier(ForcedScheme(mode: style.mode))
                .accessibilityIdentifier(QaidQuestTags.card)
                .onChange(of: controller.titleFocus) { _ in move(.title) }
                .onChange(of: controller.controlFocus) { _ in move(.control) }
        } else {
            Text(QaidQuestText().notConfigured)
                .padding()
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }

    @ViewBuilder
    private func card(_ look: QuestLook) -> some View {
        if sheet {
            look.color(look.palette.cardBackground).ignoresSafeArea()
        } else {
            let shape = RoundedRectangle(cornerRadius: look.metrics.cardRadius, style: .continuous)
            shape.fill(look.color(look.palette.cardBackground))
                .overlay(shape.strokeBorder(look.color(look.palette.cardBorder), lineWidth: 1))
        }
    }

    /// Setting the same value again would not move VoiceOver, so clear it first.
    private func move(_ target: QuestFocus) {
        focus = nil
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.15) { focus = target }
    }

    @ViewBuilder
    private func screen(_ controller: QuestController, _ look: QuestLook) -> some View {
        let text = controller.text
        switch controller.screen {
        case .idle, .loading:
            VStack(spacing: look.metrics.gap) {
                ProgressView().tint(look.color(look.palette.accent))
                Text(text.loading).font(look.font(0.875)).foregroundColor(look.color(look.palette.muted))
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .accessibilityElement(children: .combine)
        case .loadFailed(let detail):
            EndScreen(look: look, title: text.loadErrorTitle, message: detail, icon: nil, focus: $focus) {
                Button(text.retry) { Task { await controller.retryLoad() } }
                    .buttonStyle(QuestButtonStyle(look: look, primary: true))
                    .accessibilityIdentifier(QaidQuestTags.retry)
            }
        case .quest:
            if let session = controller.session {
                // A first submit keeps the step on screen; a retry keeps "Couldn't send" up.
                let onStep = session.phase == .answering || (session.phase == .submitting && !session.retrying)
                switch session.phase {
                case .answering, .submitting, .submitFailed:
                    if onStep {
                        QuestStepScreen(controller: controller, session: session, look: look, onClose: onClose,
                                        focus: $focus)
                    } else {
                        submitFailed(controller, session, look)
                    }
                case .done(let savedOffline):
                    EndScreen(look: look, title: session.thankYouTitle,
                              message: [session.thankYouMessage, savedOffline ? text.savedOffline : nil]
                                .compactMap { $0 }.joined(separator: "\n\n"),
                              icon: "checkmark", focus: $focus) {
                        if let onClose {
                            Button(text.close, action: onClose).buttonStyle(QuestButtonStyle(look: look, primary: true))
                                .accessibilityIdentifier(QaidQuestTags.close)
                        }
                    }
                }
            }
        }
    }
}

extension QuestRoot {
    private func submitFailed(_ controller: QuestController, _ session: QuestSession, _ look: QuestLook) -> some View {
        let text = controller.text
        return EndScreen(look: look, title: text.submitErrorTitle, message: text.submitErrorMessage, icon: nil,
                         focus: $focus) {
            HStack {
                if let onClose {
                    Button(text.close, action: onClose).buttonStyle(QuestButtonStyle(look: look, primary: false))
                }
                Spacer()
                Button(session.retrying ? text.sending : text.tryAgain) { controller.retrySubmit() }
                    .buttonStyle(QuestButtonStyle(look: look, primary: true))
                    .accessibilityIdentifier(QaidQuestTags.retry)
                    // Not `.disabled`: VoiceOver focus stays on it while sending.
                    .accessibilityAddTraits(session.retrying ? .updatesFrequently : [])
            }
        }
    }
}

/// `auto` follows the system; a theme that forces light or dark also draws the system
/// controls (pickers, sliders) that way.
struct ForcedScheme: ViewModifier {
    let mode: QaidQuestTheme.Mode

    func body(content: Content) -> some View {
        switch mode {
        case .auto: content
        case .light: content.environment(\.colorScheme, .light)
        case .dark: content.environment(\.colorScheme, .dark)
        }
    }
}

/// Load failure, submit failure and thank-you: a title that takes focus, a message,
/// and buttons.
struct EndScreen<Actions: View>: View {
    let look: QuestLook
    let title: String
    let message: String
    let icon: String?
    var focus: AccessibilityFocusState<QuestFocus?>.Binding
    @ViewBuilder let actions: () -> Actions

    var body: some View {
        VStack(spacing: look.metrics.gap) {
            if let icon {
                Image(systemName: icon)
                    .font(.system(size: 22 * look.scale, weight: .bold))
                    .foregroundColor(look.color(look.palette.accent))
                    .frame(width: 48 * look.scale, height: 48 * look.scale)
                    .background(Circle().fill(look.color(look.palette.accent.opacity(0.18))))
                    .accessibilityHidden(true)
            }
            Text(title)
                .font(look.font(1.25, weight: .bold))
                .foregroundColor(look.color(look.palette.cardForeground))
                .multilineTextAlignment(.center)
                .accessibilityAddTraits(.isHeader)
                .accessibilityFocused(focus, equals: .title)
            if !message.isEmpty {
                Text(message)
                    .font(look.font(1))
                    .foregroundColor(look.color(look.palette.muted))
                    .multilineTextAlignment(.center)
            }
            actions().padding(.top, 8)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

/// One step: header, progress, the question, the footer.
struct QuestStepScreen: View {
    let controller: QuestController
    let session: QuestSession
    let look: QuestLook
    let onClose: (() -> Void)?
    var focus: AccessibilityFocusState<QuestFocus?>.Binding
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var text: QaidQuestText { controller.text }
    private var bottom: Bool { session.progressPosition == .bottom }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            header
            if let description = session.description {
                Text(description)
                    .font(look.font(0.875))
                    .foregroundColor(look.color(look.palette.muted))
                    .padding(.bottom, look.metrics.sectionGap)
            }
            if session.showsProgress && !bottom {
                progressBar.padding(.bottom, look.metrics.sectionGap)
            }
            ScrollView {
                QuestStepBody(controller: controller, session: session, question: session.currentQuestion,
                              look: look, focus: focus)
                    .id(session.currentId)
                    .transition(reduceMotion ? .identity : .opacity.combined(with: .offset(y: 8)))
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .animation(reduceMotion ? nil : .easeOut(duration: 0.22), value: session.currentId)
            footer.padding(.top, look.metrics.sectionGap)
        }
    }

    @ViewBuilder
    private var header: some View {
        let counter = session.showsProgress && !bottom
        if session.title != nil || counter || onClose != nil || controller.saving {
            HStack(alignment: .center, spacing: 8) {
                if let title = session.title {
                    Text(title)
                        .font(look.font(1.125, weight: .bold))
                        .foregroundColor(look.color(look.palette.cardForeground))
                        .accessibilityAddTraits(.isHeader)
                }
                Spacer(minLength: 0)
                if !bottom { saving }
                if counter { counterText }
                if let onClose {
                    Button(action: onClose) {
                        Image(systemName: "xmark")
                            .font(.system(size: 13 * look.scale, weight: .semibold))
                            .foregroundColor(look.color(look.palette.muted))
                            .frame(width: 28, height: 28)
                            .background(Circle().fill(look.color(look.palette.progressTrack.opacity(0.5))))
                            .frame(minWidth: 44, minHeight: 44)
                            .contentShape(Rectangle())
                    }
                    .accessibilityLabel(text.closeForm)
                    .accessibilityIdentifier(QaidQuestTags.close)
                }
            }
            .padding(.bottom, 16)
        }
    }

    @ViewBuilder
    private var saving: some View {
        if controller.saving {
            HStack(spacing: 4) {
                Circle().fill(look.color(look.palette.accent)).frame(width: 6, height: 6)
                Text(text.saving).font(look.font(0.75)).foregroundColor(look.color(look.palette.muted))
            }
        }
    }

    private var counterText: some View {
        Text(session.counterText)
            .font(look.font(0.875))
            .foregroundColor(look.color(look.palette.muted))
            .accessibilityHidden(true)
    }

    private var progressBar: some View {
        GeometryReader { geometry in
            ZStack(alignment: .leading) {
                Capsule().fill(look.color(look.palette.progressTrack))
                Capsule().fill(look.color(look.palette.accent))
                    .frame(width: geometry.size.width * session.progressFraction)
                    .animation(reduceMotion ? nil : .easeInOut(duration: 0.3), value: session.progressFraction)
            }
        }
        .frame(height: 4)
        .accessibilityElement()
        .accessibilityLabel(text.progressLabel)
        .accessibilityValue(session.progressValueText)
        .accessibilityIdentifier(QaidQuestTags.progress)
    }

    private var footer: some View {
        HStack(spacing: 8) {
            if session.canGoBack {
                Button(session.backLabel) { controller.back() }
                    .buttonStyle(QuestButtonStyle(look: look, primary: false))
                    .accessibilityIdentifier(QaidQuestTags.back)
            }
            if bottom {
                if session.showsProgress { progressBar.frame(minWidth: 60) } else { Spacer() }
                saving
                if session.showsProgress { counterText }
            } else {
                Spacer()
            }
            Button(session.phase == .submitting ? text.sending : session.primaryLabel) { controller.next() }
                .buttonStyle(QuestButtonStyle(look: look, primary: true))
                .accessibilityAddTraits(session.phase == .submitting ? .updatesFrequently : [])
                .accessibilityIdentifier(QaidQuestTags.primary)
        }
    }
}

/// The label, description, control and error of one question.
struct QuestStepBody: View {
    let controller: QuestController
    let session: QuestSession
    let question: QuestQuestion
    let look: QuestLook
    var focus: AccessibilityFocusState<QuestFocus?>.Binding

    var body: some View {
        let text = controller.text
        VStack(alignment: .leading, spacing: look.metrics.gap) {
            VStack(alignment: .leading, spacing: 4) {
                (Text(question.label).foregroundColor(look.color(look.palette.cardForeground))
                    + Text(question.required ? " *" : "").foregroundColor(look.color(look.palette.error)))
                    .font(look.font(1.125, weight: .semibold))
                    .accessibilityLabel(question.required ? "\(question.label), \(text.required)" : question.label)
                    .accessibilityAddTraits(.isHeader)
                    .accessibilityFocused(focus, equals: .title)
                if let description = question.description, !description.isEmpty {
                    Text(description).font(look.font(0.875)).foregroundColor(look.color(look.palette.muted))
                }
            }
            QuestInput(controller: controller, session: session, question: question, look: look)
                .accessibilityHint(session.error ?? "")
                .accessibilityFocused(focus, equals: .control)
            if let error = session.error {
                Text(error)
                    .font(look.font(0.8125))
                    .foregroundColor(look.color(look.palette.error))
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityIdentifier(QaidQuestTags.error)
            }
        }
        .padding(.vertical, 2)
    }
}
#endif
