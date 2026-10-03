import Foundation

/// Everything a quest on screen needs from outside: configuration, the device, the
/// network and the disk. The app builds the real one; tests script every part.
package struct QuestEnvironment {
    package var config: QaidQuestsConfiguration
    package var device: QuestDeviceInfo
    package var visitorId: String
    package var appMetadata: QuestAppMetadata
    package var transport: QuestTransport
    package var store: OfflineQuestStore?
    package var retry: QuestRetryPolicy
    /// The save debounce: `config.saveDebounceMs` unless a test sets one.
    package var debounce: TimeInterval
    package var autoAdvanceDelay: TimeInterval
    package var sleep: QuestSleep
    package var submitTimeout: TimeInterval
    /// After a response goes into the offline queue: the app flushes it, in case the network is back.
    package var onSavedOffline: (@MainActor () -> Void)?

    package init(config: QaidQuestsConfiguration, device: QuestDeviceInfo, visitorId: String,
                 appMetadata: QuestAppMetadata = QuestAppMetadata(), transport: QuestTransport,
                 store: OfflineQuestStore?, retry: QuestRetryPolicy = QuestRetryPolicy(), debounce: TimeInterval? = nil,
                 autoAdvanceDelay: TimeInterval = 0.18, sleep: @escaping QuestSleep = realSleep,
                 submitTimeout: TimeInterval = ResponseSync.defaultSubmitTimeout,
                 onSavedOffline: (@MainActor () -> Void)? = nil) {
        self.config = config
        self.device = device
        self.visitorId = visitorId
        self.appMetadata = appMetadata
        self.transport = transport
        self.store = store
        self.retry = retry
        self.debounce = debounce ?? TimeInterval(max(config.saveDebounceMs, 0)) / 1000
        self.autoAdvanceDelay = autoAdvanceDelay
        self.sleep = sleep
        self.submitTimeout = submitTimeout
        self.onSavedOffline = onSavedOffline
    }
}

/// One quest on screen, from loading to the thank-you: loads the definition and theme,
/// creates the response, runs the session's effects, and tells the view when to
/// redraw. The SwiftUI view only draws what this holds and forwards taps.
@MainActor
package final class QuestController {
    package enum Screen: Equatable {
        case idle
        case loading
        case loadFailed(String)
        /// The session's phase says which: a step, sending, the thank-you, or Try again.
        case quest
    }

    package let questId: String
    let screenName: String?
    let extraMetadata: [String: String]
    let environment: QuestEnvironment
    let client: QuestClient

    package private(set) var screen = Screen.idle
    package private(set) var session: QuestSession?
    package private(set) var style: QuestStyle
    package private(set) var saving = false
    /// Bumped when VoiceOver focus should move to the step's label or the end screen's title.
    package private(set) var titleFocus = 0
    /// Bumped when focus should go back to the control whose answer failed.
    package private(set) var controlFocus = 0

    package var onChange: (() -> Void)?
    /// `(text, assertive)`.
    package var onAnnounce: ((String, Bool) -> Void)?
    package var onComplete: (([String: QuestAnswer]) -> Void)?
    package var onError: ((QaidQuestsErrorEvent) -> Void)?
    package var onLog: ((String) -> Void)?

    private var sync: ResponseSync?
    private var tasks: [Task<Void, Never>] = []

    package init(questId: String, screen: String?, metadata: [String: String], environment: QuestEnvironment) {
        self.questId = questId
        self.screenName = screen
        self.extraMetadata = metadata
        self.environment = environment
        client = QuestClient(base: environment.config.base, userAgent: environment.device.userAgent,
                             transport: environment.transport, retry: environment.retry, sleep: environment.sleep)
        style = QuestStyleResolver.resolve(document: nil, host: environment.config.theme)
    }

    package var text: QaidQuestText { environment.config.text }

    // MARK: Loading

    /// Called when the view appears; nothing is fetched or sent before.
    package func start() async {
        guard screen == .idle else { return }
        await load()
    }

    package func retryLoad() async {
        guard case .loadFailed = screen else { return }
        await load()
    }

    private func load() async {
        screen = .loading
        onAnnounce?(text.loading, false)
        onChange?()
        // In parallel, as the web embed; the response is created only after both.
        async let definition = client.loadDefinition(questId: questId)
        async let theme = loadTheme()
        let (result, document) = await (definition, theme)
        switch result {
        case .failure(let failure):
            let detail = text.loadError(failure)
            screen = .loadFailed(detail)
            report(QaidQuestsErrorEvent(questId: questId, operation: .loadQuest, error: Self.error(for: failure)))
            onAnnounce?("\(text.loadErrorTitle). \(detail)", true)
            onChange?()
        case .success(let quest):
            style = QuestStyleResolver.resolve(document: document, host: environment.config.theme)
            begin(quest)
        }
    }

    private func loadTheme() async -> QuestThemeDocument? {
        let config = environment.config
        guard let themeId = nonBlank(config.themeId) else { return nil }
        switch await client.loadTheme(themeId: themeId, version: config.themeVersion, apiKey: config.apiKey) {
        case .success(let document):
            if document.ignoresCSS {
                onLog?("Quest theme \(themeId): custom CSS and \"unstyled\" have no native equivalent and are ignored.")
            }
            return document
        case .failure(let error):
            // As the web embed: the quest still draws, with the default look.
            report(QaidQuestsErrorEvent(questId: questId, operation: .loadTheme, error: error))
            return nil
        }
    }

    private func begin(_ quest: Questionnaire) {
        let env = environment
        let pageUrl = QuestPageUrl.make(config: env.config, bundleIdentifier: env.device.bundleIdentifier,
                                        screen: screenName)
        let body = QuestRequests.createBody(
            apiKey: env.config.apiKey, questId: quest.id, pageUrl: pageUrl, visitorId: env.visitorId,
            userAgent: env.device.userAgent,
            metadata: env.device.metadata(screen: screenName, app: env.appMetadata, extra: extraMetadata)
        )
        let sync = ResponseSync(client: client, questId: quest.id, createBody: body, store: env.store,
                                debounce: env.debounce, sleep: env.sleep, submitTimeout: env.submitTimeout)
        sync.onError = { [weak self] in self?.report($0) }
        sync.onSavedOffline = env.onSavedOffline
        sync.onSavingChange = { [weak self] saving in
            self?.saving = saving
            self?.onChange?()
        }
        self.sync = sync
        sync.start()
        var session = QuestSession(quest: quest, text: text, autoAdvance: env.config.autoAdvance,
                                   progressPosition: env.config.progressPosition)
        let effects = session.begin()
        self.session = session
        screen = .quest
        run(effects, sync: sync)
    }

    static func error(for failure: QuestLoadFailure) -> QaidQuestsError {
        switch failure {
        case .status(let status): return status >= 500 ? .server(status) : .rejected(status: status, message: "")
        case .empty: return .invalidResponse("The quest has no questions")
        case .invalid: return .invalidResponse("The quest definition couldn't be read")
        case .network: return .network("The quest couldn't be loaded")
        }
    }

    // MARK: What the person does

    package func setText(_ questionId: String, _ value: String) { mutate { $0.setText(questionId, value) } }
    package func setAmount(_ questionId: String, _ value: Double?) { mutate { $0.setAmount(questionId, value) } }
    package func setRange(_ questionId: String, _ value: Double) { mutate { $0.setRange(questionId, value) } }
    package func setDate(_ questionId: String, _ value: String?) { mutate { $0.setDate(questionId, value) } }
    package func select(_ questionId: String, option: String) { mutate { $0.select(questionId, option: option) } }
    package func next() { mutate { $0.next() } }
    package func back() { mutate { $0.back() } }
    package func retrySubmit() { mutate { $0.retrySubmit() } }

    /// The view is going away: send what is waiting. Closing never submits.
    package func close() {
        sync?.flush()
    }

    /// Waits for every request and timer this controller started. For tests.
    package func waitUntilIdle() async {
        while let task = tasks.first {
            await task.value
            tasks.removeFirst()
        }
        await sync?.waitUntilIdle()
    }

    private func mutate(_ change: (inout QuestSession) -> [SessionEffect]) {
        guard var session, let sync else { return }
        let effects = change(&session)
        self.session = session
        run(effects, sync: sync)
    }

    private func run(_ effects: [SessionEffect], sync: ResponseSync) {
        for effect in effects {
            switch effect {
            case let .save(questionId, value, debounce):
                sync.record(questionId: questionId, value: value, debounce: debounce)
            case .flushSaves:
                sync.flush()
            case let .submit(answers, clearing):
                tasks.append(Task {
                    let outcome = await sync.submit(answers: answers, clearing: clearing)
                    self.mutate { $0.submitFinished(outcome) }
                })
            case .announce(let words):
                onAnnounce?(words, false)
            case .alert(let words):
                onAnnounce?(words, true)
            case .focusTitle:
                titleFocus += 1
            case .focusControl:
                controlFocus += 1
            case .autoAdvance(let questionId):
                let (sleep, delay) = (environment.sleep, environment.autoAdvanceDelay)
                tasks.append(Task {
                    await sleep(delay)
                    self.mutate { $0.autoAdvanceFired(questionId) }
                })
            case let .logUnsupported(questionId, type):
                onLog?("Question \"\(questionId)\" has type \"\(type)\", which QaidQuests \(QaidQuestsSDK.version) can't show.")
            case .complete(let answers):
                onComplete?(answers)
            }
        }
        onChange?()
    }

    private func report(_ report: QaidQuestsErrorEvent) {
        onLog?("\(report.operation.rawValue) failed: \(report.error)")
        onError?(report)
    }
}
