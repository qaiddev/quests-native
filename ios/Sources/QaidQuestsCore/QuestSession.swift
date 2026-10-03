import Foundation

/// How a question is drawn. One function decides, so the rule for types this version
/// doesn't know can change in one place.
package enum QuestionHandling: Equatable {
    case native
    /// A card saying the question can't be shown in this version of the app. Never
    /// required, never answered, logged once.
    case unsupported
}

package func handling(for kind: QuestionKind) -> QuestionHandling {
    if case .unknown = kind { return .unsupported }
    return .native
}

/// What the session asks of the world after a step: the controller carries it out.
package enum SessionEffect: Equatable {
    case save(questionId: String, value: QuestAnswer, debounce: Bool)
    case flushSaves
    case submit(answers: [String: QuestAnswer], clearing: [String])
    /// A polite VoiceOver announcement.
    case announce(String)
    /// An assertive one: errors.
    case alert(String)
    /// Move VoiceOver focus to the step's label, or the end screen's title.
    case focusTitle
    /// Move focus to the control whose answer failed.
    case focusControl
    case autoAdvance(questionId: String)
    case logUnsupported(questionId: String, type: String)
    case complete([String: QuestAnswer])
}

/// One quest, one question per step, as the web embed's flow (protocol §6). Pure: it
/// holds the answers and the step, and returns effects for the controller to run.
package struct QuestSession: Equatable {
    package enum Phase: Equatable {
        case answering
        case submitting
        case submitFailed
        /// `savedOffline`: the answers wait in the offline queue.
        case done(savedOffline: Bool)
    }

    package let quest: Questionnaire
    package let text: QaidQuestText
    package let autoAdvance: Bool
    /// Where the progress bar goes: the app's choice, else the quest's.
    package let progressPosition: QaidQuestProgressPosition
    package private(set) var answers: [String: QuestAnswer] = [:]
    package private(set) var currentId: String
    package private(set) var error: String?
    package private(set) var phase = Phase.answering
    /// Submitting from the "Couldn't send" screen, which stays up showing "Sending…".
    package private(set) var retrying = false
    private var loggedUnsupported: Set<String> = []

    package init(quest: Questionnaire, text: QaidQuestText, autoAdvance: Bool,
                 progressPosition: QaidQuestProgressPosition? = nil) {
        self.quest = quest
        self.text = text
        self.autoAdvance = autoAdvance
        self.progressPosition = progressPosition ?? quest.progressPosition
        currentId = ""
        currentId = visibleQuestions[0].id
    }

    // MARK: What the screen shows

    /// The questions visible under the answers so far. If every question is hidden (a
    /// quest whose first question has a false rule), the first one shows anyway.
    package var visibleQuestions: [QuestQuestion] {
        let visible = QuestVisibility.visibleQuestions(quest.questions, answers: answers)
        return visible.isEmpty ? [quest.questions[0]] : visible
    }

    /// The current question's place in the visible list. It is always there: its
    /// visibility depends only on earlier answers, which change only on earlier steps.
    package var stepIndex: Int {
        visibleQuestions.firstIndex { $0.id == currentId } ?? 0
    }

    package var currentQuestion: QuestQuestion { visibleQuestions[stepIndex] }

    package var stepCount: Int { visibleQuestions.count }
    package var stepNumber: Int { stepIndex + 1 }
    /// No later question is visible: the primary button submits.
    package var isLastStep: Bool { stepIndex >= stepCount - 1 }
    package var canGoBack: Bool { stepIndex > 0 }
    package var primaryLabel: String {
        isLastStep ? quest.submitLabel ?? text.submit : quest.nextLabel ?? text.next
    }
    package var backLabel: String { quest.backLabel ?? text.back }

    /// Hidden by `hideProgress`, or when the quest has one question in all — counted
    /// before visibility, as the web embed does.
    package var showsProgress: Bool { !quest.hideProgress && quest.questions.count > 1 }
    /// Starts at 1/N, as the web embed's fill does.
    package var progressFraction: Double { Double(stepNumber) / Double(stepCount) }
    package var counterText: String { "\(stepNumber) / \(stepCount)" }
    package var progressValueText: String { text.progressValue(step: stepNumber, total: stepCount) }

    package var title: String? { quest.hideTitle ? nil : nonBlank(quest.title) }
    package var description: String? { quest.hideTitle ? nil : nonBlank(quest.description) }
    package var thankYouTitle: String { quest.thankYouTitle ?? text.thankYouTitle }
    package var thankYouMessage: String? { quest.thankYouMessage }

    package func answer(_ questionId: String) -> QuestAnswer? { answers[questionId] }

    /// What the submitted set would be now.
    package var visibleAnswers: [String: QuestAnswer] {
        QuestVisibility.visibleAnswers(quest.questions, answers: answers)
    }

    // MARK: Actions

    /// The first step is on screen.
    package mutating func begin() -> [SessionEffect] {
        enterStep()
    }

    package mutating func setText(_ questionId: String, _ value: String) -> [SessionEffect] {
        guard let question = editable(questionId), case .text(let options) = question.kind else { return [] }
        return set(question, .string(JSText.clip(value, maxLength: options.maxLength)))
    }

    /// `nil` is an empty or unreadable field.
    package mutating func setAmount(_ questionId: String, _ value: Double?) -> [SessionEffect] {
        guard let question = editable(questionId), case .currency = question.kind else { return [] }
        return set(question, value.map(QuestAnswer.number) ?? .null)
    }

    package mutating func setRange(_ questionId: String, _ value: Double) -> [SessionEffect] {
        guard let question = editable(questionId), case .range(let options) = question.kind else { return [] }
        return set(question, .number(RangeMath.snap(value, options: options)))
    }

    /// `YYYY-MM-DD`, or nil to clear.
    package mutating func setDate(_ questionId: String, _ value: String?) -> [SessionEffect] {
        guard let question = editable(questionId), case .date = question.kind else { return [] }
        return set(question, value.map(QuestAnswer.string) ?? .null)
    }

    /// A tap on an option. Single choice keeps a tapped selection selected (it can't
    /// be cleared); multiple choice toggles, keeping the order things were picked in.
    package mutating func select(_ questionId: String, option: String) -> [SessionEffect] {
        guard let question = editable(questionId), case .multipleChoice(let options) = question.kind,
              options.options.contains(where: { $0.value == option }) else { return [] }
        guard options.multiple else {
            var effects = set(question, .string(option))
            if autoAdvance { effects.append(.autoAdvance(questionId: questionId)) }
            return effects
        }
        var picked = selected(questionId)
        if let index = picked.firstIndex(of: option) { picked.remove(at: index) } else { picked.append(option) }
        return set(question, .strings(picked))
    }

    package func selected(_ questionId: String) -> [String] {
        switch answers[questionId] {
        case .string(let value)?: return [value]
        case .strings(let values)?: return values
        default: return []
        }
    }

    /// 180 ms after a single-choice pick with `autoAdvance` on: Next, if still there.
    package mutating func autoAdvanceFired(_ questionId: String) -> [SessionEffect] {
        guard phase == .answering, currentId == questionId else { return [] }
        return next()
    }

    /// Validate, save, then the next visible question or submit.
    package mutating func next() -> [SessionEffect] {
        guard phase == .answering else { return [] }
        let question = currentQuestion
        if let message = QuestValidation.message(for: question, value: answers[question.id], text: text) {
            error = message
            return [.alert(message), .focusControl]
        }
        var effects: [SessionEffect] = []
        if handling(for: question.kind) == .native {
            // The web embed saves every visited question on Next, untouched ones too.
            let value = answers[question.id] ?? Self.emptyValue(question.kind)
            answers[question.id] = value
            effects.append(.flushSaves)
            effects.append(.save(questionId: question.id, value: value, debounce: false))
        }
        if isLastStep {
            phase = .submitting
            effects.append(submitEffect())
            return effects
        }
        currentId = visibleQuestions[stepIndex + 1].id
        error = nil
        return effects + enterStep()
    }

    /// Back never validates; it sends what is waiting first.
    package mutating func back() -> [SessionEffect] {
        guard phase == .answering, canGoBack else { return [] }
        currentId = visibleQuestions[stepIndex - 1].id
        error = nil
        return [.flushSaves] + enterStep()
    }

    package mutating func retrySubmit() -> [SessionEffect] {
        guard phase == .submitFailed else { return [] }
        phase = .submitting
        retrying = true
        return [submitEffect()]
    }

    package mutating func submitFinished(_ outcome: ResponseSync.SubmitOutcome) -> [SessionEffect] {
        guard phase == .submitting else { return [] }
        retrying = false
        switch outcome {
        case .sent, .savedOffline:
            phase = .done(savedOffline: outcome == .savedOffline)
            let spoken = [thankYouTitle, thankYouMessage, outcome == .savedOffline ? text.savedOffline : nil]
                .compactMap { $0 }.joined(separator: ". ")
            return [.focusTitle, .announce(spoken), .complete(visibleAnswers)]
        case .failed:
            phase = .submitFailed
            return [.focusTitle, .alert("\(text.submitErrorTitle). \(text.submitErrorMessage)")]
        }
    }

    // MARK: Helpers

    private func editable(_ questionId: String) -> QuestQuestion? {
        guard phase == .answering, let question = quest.question(questionId) else { return nil }
        return question
    }

    private mutating func set(_ question: QuestQuestion, _ value: QuestAnswer) -> [SessionEffect] {
        answers[question.id] = value
        if question.id == currentId { error = nil }
        return [.save(questionId: question.id, value: value, debounce: question.debouncesSaves)]
    }

    private mutating func enterStep() -> [SessionEffect] {
        var effects: [SessionEffect] = []
        let question = currentQuestion
        switch question.kind {
        case .range(let options) where answers[question.id] == nil:
            // The web embed pushes a range's starting value whenever its step is
            // drawn, so an untouched range counts as answered.
            let value = QuestAnswer.number(RangeMath.initialValue(options, answer: nil))
            answers[question.id] = value
            effects.append(.save(questionId: question.id, value: value, debounce: true))
        case .unknown(let type) where !loggedUnsupported.contains(question.id):
            loggedUnsupported.insert(question.id)
            effects.append(.logUnsupported(questionId: question.id, type: type))
        default:
            break
        }
        if stepCount > 1 { effects.append(.announce(text.stepAnnouncement(step: stepNumber, total: stepCount))) }
        effects.append(.focusTitle)
        return effects
    }

    private func submitEffect() -> SessionEffect {
        let submitted = visibleAnswers
        let clearing = quest.questions.map(\.id).filter { id in
            guard let value = answers[id], value != .null else { return false }
            return submitted[id] == nil
        }
        return .submit(answers: submitted, clearing: clearing)
    }

    /// What Next saves for a question left untouched: what the web embed's control holds.
    static func emptyValue(_ kind: QuestionKind) -> QuestAnswer {
        switch kind {
        case .text: return .string("")
        case .multipleChoice(let options) where options.multiple: return .strings([])
        default: return .null
        }
    }
}
