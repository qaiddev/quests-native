import Foundation

/// Sends one response's requests to qaid in order: create, then every answer, then
/// submit. Improves on the web embed, which fires each PATCH on its own and never
/// looks at the status:
/// - an answer made before the create returns waits for it, then goes in order;
/// - typing is debounced per question (a change to another question sends the
///   previous one first), and an answer equal to the last one queued is not resent;
/// - the network and 5xx are retried with backoff; a refused (4xx) answer is
///   reported and skipped; a refused create waits for submit, which tries it again;
/// - once a call can't get through, nothing more is sent until submit, so a person
///   offline doesn't start a retry chain on every keystroke;
/// - at submit, a network failure goes straight to the offline queue, and sending never
///   keeps the person on "Sending…" longer than `submitTimeout` before it does. Android
///   has the same rules.
@MainActor
package final class ResponseSync {
    package enum SubmitOutcome: Equatable {
        case sent
        /// Handed to the offline queue; it goes out when the network returns.
        case savedOffline
        case failed(QaidQuestsError)
    }

    let client: QuestClient
    let questId: String
    let createBody: QuestJSON
    let store: OfflineQuestStore?
    let debounce: TimeInterval
    let sleep: QuestSleep
    let submitTimeout: TimeInterval

    package var onError: ((QaidQuestsErrorEvent) -> Void)?
    /// True while answers are on their way, for the "Saving…" note.
    package var onSavingChange: ((Bool) -> Void)?
    /// A response went into the offline queue: worth a flush, in case the network is back.
    package var onSavedOffline: (() -> Void)?

    package private(set) var responseId: String?
    private var queue: [OfflineOperation] = []
    private var lastQueued: [String: QuestAnswer] = [:]
    private var pending: (questionId: String, value: QuestAnswer)?
    private var generation = 0
    private var draining: Task<Void, Never>?
    /// Why sending stopped: the network, or a refused create or submit. Sending resumes
    /// at submit, so a person offline doesn't trigger a retry on every keystroke.
    private var stallError: QaidQuestsError?
    private var started = false
    private var outcome: SubmitOutcome?
    private var saving = false

    /// The longest a submit keeps the person on "Sending…" before the answers go to the queue.
    nonisolated package static let defaultSubmitTimeout: TimeInterval = 20

    package init(client: QuestClient, questId: String, createBody: QuestJSON, store: OfflineQuestStore?,
                 debounce: TimeInterval = 0.5, sleep: @escaping QuestSleep = realSleep,
                 submitTimeout: TimeInterval = ResponseSync.defaultSubmitTimeout) {
        self.client = client
        self.questId = questId
        self.createBody = createBody
        self.store = store
        self.debounce = debounce
        self.sleep = sleep
        self.submitTimeout = submitTimeout
    }

    /// Creates the response. Called once the quest is on screen, never before.
    package func start() {
        guard !started else { return }
        started = true
        kick()
    }

    /// An answer changed. Debounced answers wait for a pause (or the next flush).
    package func record(questionId: String, value: QuestAnswer, debounce: Bool) {
        guard outcome == nil else { return }
        guard debounce else {
            flush()
            enqueue(questionId, value)
            return
        }
        if let previous = pending, previous.questionId != questionId { enqueue(previous.questionId, previous.value) }
        pending = (questionId, value)
        generation += 1
        let mine = generation
        let (sleep, delay) = (self.sleep, self.debounce)
        Task {
            await sleep(delay)
            self.debounceFired(mine)
        }
    }

    private func debounceFired(_ fired: Int) {
        if fired == generation { flush() }
    }

    /// Sends the debounced answer now (Next, Back, close).
    package func flush() {
        guard let previous = pending else { return }
        pending = nil
        enqueue(previous.questionId, previous.value)
    }

    private func enqueue(_ questionId: String, _ value: QuestAnswer) {
        guard lastQueued[questionId] != value else { return }
        lastQueued[questionId] = value
        queue.append(.patch(questionId: questionId, value: value))
        if stallError == nil && started { kick() }
    }

    private func kick() {
        guard draining == nil else { return }
        draining = Task {
            await self.drain()
            self.draining = nil
            self.setSaving(false)
        }
    }

    package func waitUntilIdle() async {
        while let task = draining { await task.value }
    }

    /// Waits for the running drain for at most `timeout`, then stops it. False when it had
    /// to be stopped. A stopped drain never touches the queue again, so what is unsent stays exact.
    private func finishDraining(within timeout: TimeInterval) async -> Bool {
        guard let task = draining else { return true }
        return await withTaskGroup(of: Bool.self) { group -> Bool in
            group.addTask { await task.value; return true }
            // A real clock, whatever `sleep` the debounce uses.
            group.addTask { await realSleep(timeout); return false }
            let finished = await group.next() ?? true
            if !finished { task.cancel() }
            group.cancelAll()
            return finished
        }
    }

    private func drain() async {
        // A cancelled drain (submit's time limit) never touches the queue again.
        while stallError == nil, !Task.isCancelled {
            guard let responseId else {
                switch await client.create(body: createBody) {
                case .success(let id):
                    self.responseId = id
                    continue
                case .failure(let error):
                    stallError = error
                    if !error.isRetryable { report(.createResponse, nil, error) }
                    return
                }
            }
            guard let operation = queue.first else { return }
            switch operation {
            case let .patch(questionId, value):
                setSaving(true)
                if case .failure(let error) = await client.patch(responseId: responseId, questionId: questionId,
                                                                 value: value) {
                    if error.isRetryable {
                        stallError = error
                        return
                    }
                    report(.saveAnswer, questionId, error)
                }
            case let .submit(answers):
                if case .failure(let error) = await client.submit(responseId: responseId, answers: answers) {
                    stallError = error
                    if !error.isRetryable { report(.submit, nil, error) }
                    return
                }
            }
            guard !Task.isCancelled else { return }
            queue.removeFirst()
        }
    }

    /// Flush, PATCH `null` for each hidden question that held a value (the server keeps
    /// what PATCHes stored, whatever the submit body says), then submit. Safe to call
    /// again after a failure: Try again.
    package func submit(answers: [String: QuestAnswer], clearing hidden: [String]) async -> SubmitOutcome {
        if let outcome { return outcome }
        flush()
        for questionId in hidden { enqueue(questionId, .null) }
        // No drain is ever on a submit here: each submit waits for (or stops) its own.
        queue.removeAll { if case .submit = $0 { return true } else { return false } }
        queue.append(.submit(answers: answers))
        // The network already failed: another round of retries would only keep the person
        // watching "Sending…". Anything else (a 5xx, a refused create) is tried once more.
        if !stalledOffline {
            stallError = nil
            kick()
            if await !finishDraining(within: submitTimeout) {
                stallError = .network("Timed out after \(Int(submitTimeout)) s")
            }
        }
        guard let error = stallError else { return finish(.sent) }
        guard error.isRetryable, let store else { return .failed(error) }
        let start: OfflineResponse.Start = responseId.map { .created(responseId: $0) } ?? .pending(createBody: createBody)
        do {
            try store.add(OfflineResponse(questId: questId, base: client.base, userAgent: client.userAgent,
                                          start: start, operations: queue))
        } catch {
            let failure = QaidQuestsError.storage(error.localizedDescription)
            report(.offlineQueue, nil, failure)
            return .failed(failure)
        }
        queue.removeAll()
        onSavedOffline?()
        return finish(.savedOffline)
    }

    private var stalledOffline: Bool {
        if case .network? = stallError { return true }
        return false
    }

    private func finish(_ result: SubmitOutcome) -> SubmitOutcome {
        outcome = result
        return result
    }

    private func setSaving(_ value: Bool) {
        guard saving != value else { return }
        saving = value
        onSavingChange?(value)
    }

    private func report(_ operation: QaidQuestsErrorEvent.Operation, _ questionId: String?, _ error: QaidQuestsError) {
        onError?(QaidQuestsErrorEvent(questId: questId, operation: operation, questionId: questionId, error: error))
    }
}
