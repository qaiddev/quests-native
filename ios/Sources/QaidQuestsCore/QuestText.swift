import Foundation

/// Every word the SDK draws itself, in English by default. Pass translations here.
///
/// The quest's own words (title, labels, `nextLabel`, `thankYouTitle`…) come from the
/// quest and win over these; these fill in where the quest has none, and cover what
/// only the app draws: loading, errors, validation, the offline note, VoiceOver labels.
/// `{count}`, `{n}`, `{total}` and `{status}` are filled in where they appear.
public struct QaidQuestText: Equatable, Sendable {
    // Navigation and end screens; the web embed's defaults.
    public var next: String
    public var back: String
    public var submit: String
    public var thankYouTitle: String
    public var close: String

    // Validation; the web embed's exact messages.
    public var validationRequired: String
    public var validationMinLength: String
    public var validationAmount: String
    public var validationDate: String
    public var validationSelectOne: String
    public var validationSelectMany: String

    // Loading and failures.
    public var loading: String
    public var loadErrorTitle: String
    public var loadErrorStatus: String
    public var loadErrorEmpty: String
    public var loadErrorInvalid: String
    public var loadErrorOffline: String
    public var retry: String
    public var submitErrorTitle: String
    public var submitErrorMessage: String
    public var tryAgain: String
    public var sending: String
    public var saving: String
    public var savedOffline: String
    public var notConfigured: String

    // Controls only the app has, and what VoiceOver reads.
    public var unsupportedQuestion: String
    public var datePlaceholder: String
    public var clearDate: String
    public var required: String
    public var closeForm: String
    public var progressLabel: String
    public var progressValue: String
    public var stepAnnouncement: String

    public init(
        next: String = "Next",
        back: String = "Back",
        submit: String = "Submit",
        thankYouTitle: String = "Thank you!",
        close: String = "Close",
        validationRequired: String = "This field is required.",
        validationMinLength: String = "Please enter at least {count} characters.",
        validationAmount: String = "Please enter a valid amount.",
        validationDate: String = "Please pick a date.",
        validationSelectOne: String = "Please select an option.",
        validationSelectMany: String = "Please select at least one option.",
        loading: String = "Loading…",
        loadErrorTitle: String = "Couldn't load form",
        loadErrorStatus: String = "Failed to load questionnaire ({status})",
        loadErrorEmpty: String = "Questionnaire is empty",
        loadErrorInvalid: String = "The questionnaire couldn't be read.",
        loadErrorOffline: String = "You seem to be offline.",
        retry: String = "Try again",
        submitErrorTitle: String = "Couldn't send your answers",
        submitErrorMessage: String = "Your answers are still here. Check your connection and try again.",
        tryAgain: String = "Try again",
        sending: String = "Sending…",
        saving: String = "Saving…",
        savedOffline: String = "Saved. Your answers will send when you're back online.",
        notConfigured: String = "Quests aren't set up in this build.",
        unsupportedQuestion: String = "This question can't be shown in this version of the app.",
        datePlaceholder: String = "Choose a date",
        clearDate: String = "Clear date",
        required: String = "required",
        closeForm: String = "Close form",
        progressLabel: String = "Progress",
        progressValue: String = "Question {n} of {total}",
        stepAnnouncement: String = "Step {n} of {total}"
    ) {
        self.next = next
        self.back = back
        self.submit = submit
        self.thankYouTitle = thankYouTitle
        self.close = close
        self.validationRequired = validationRequired
        self.validationMinLength = validationMinLength
        self.validationAmount = validationAmount
        self.validationDate = validationDate
        self.validationSelectOne = validationSelectOne
        self.validationSelectMany = validationSelectMany
        self.loading = loading
        self.loadErrorTitle = loadErrorTitle
        self.loadErrorStatus = loadErrorStatus
        self.loadErrorEmpty = loadErrorEmpty
        self.loadErrorInvalid = loadErrorInvalid
        self.loadErrorOffline = loadErrorOffline
        self.retry = retry
        self.submitErrorTitle = submitErrorTitle
        self.submitErrorMessage = submitErrorMessage
        self.tryAgain = tryAgain
        self.sending = sending
        self.saving = saving
        self.savedOffline = savedOffline
        self.notConfigured = notConfigured
        self.unsupportedQuestion = unsupportedQuestion
        self.datePlaceholder = datePlaceholder
        self.clearDate = clearDate
        self.required = required
        self.closeForm = closeForm
        self.progressLabel = progressLabel
        self.progressValue = progressValue
        self.stepAnnouncement = stepAnnouncement
    }

    package func minLengthMessage(_ count: Int) -> String {
        validationMinLength.replacingOccurrences(of: "{count}", with: String(count))
    }

    package func progressValue(step: Int, total: Int) -> String {
        Self.fill(progressValue, step: step, total: total)
    }

    package func stepAnnouncement(step: Int, total: Int) -> String {
        Self.fill(stepAnnouncement, step: step, total: total)
    }

    package func loadError(_ failure: QuestLoadFailure) -> String {
        switch failure {
        case .status(let status): return loadErrorStatus.replacingOccurrences(of: "{status}", with: String(status))
        case .empty: return loadErrorEmpty
        case .invalid: return loadErrorInvalid
        case .network: return loadErrorOffline
        }
    }

    static func fill(_ template: String, step: Int, total: Int) -> String {
        template.replacingOccurrences(of: "{n}", with: String(step))
            .replacingOccurrences(of: "{total}", with: String(total))
    }
}
