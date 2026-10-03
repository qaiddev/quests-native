package dev.qaid.quests.core

/**
 * Every string the SDK itself shows or speaks, for apps that are not in English. The English
 * defaults are the web embed's where it has one. A quest's own labels (its Next / Back /
 * Submit and thank-you text, set in the qaid editor) still win over these: they are the
 * quest's content. `{count}`, `{status}`, `{n}` and `{total}` are filled in where noted.
 * The same keys, with the same defaults, as the iOS SDK's `QaidQuestText`.
 */
data class QaidQuestText(
    // Navigation and end screens.
    val next: String = "Next",
    val back: String = "Back",
    val submit: String = "Submit",
    val thankYouTitle: String = "Thank you!",
    val close: String = "Close",

    // Validation: the web embed's exact messages.
    val validationRequired: String = "This field is required.",
    /** `{count}` = the question's minimum length. */
    val validationMinLength: String = "Please enter at least {count} characters.",
    val validationAmount: String = "Please enter a valid amount.",
    val validationDate: String = "Please pick a date.",
    val validationSelectOne: String = "Please select an option.",
    val validationSelectMany: String = "Please select at least one option.",

    // Loading and failures.
    val loading: String = "Loading…",
    val loadErrorTitle: String = "Couldn't load form",
    /** The detail under [loadErrorTitle] when the server answered with an error. `{status}` = HTTP status. */
    val loadErrorStatus: String = "Failed to load questionnaire ({status})",
    val loadErrorEmpty: String = "Questionnaire is empty",
    val loadErrorInvalid: String = "The questionnaire couldn't be read.",
    /** The detail under [loadErrorTitle] when the server couldn't be reached. */
    val loadErrorOffline: String = "Check your connection and try again.",
    val retry: String = "Retry",
    val submitErrorTitle: String = "Couldn't send your answers",
    val submitErrorMessage: String = "Your answers are still here. Check your connection and try again.",
    val tryAgain: String = "Try again",
    val sending: String = "Sending…",
    val saving: String = "Saving…",
    /** Shown on the thank-you screen when the answers were saved to send later. */
    val savedOffline: String = "Your answers are saved on this device and will be sent when you're back online.",
    /** Drawn in place of a quest when `QaidQuests.configure` was never called. */
    val notConfigured: String = "Quests aren't set up in this build.",

    // Controls only the app has, and what TalkBack reads.
    /** The card drawn in place of a question this version of the app can't show. */
    val unsupportedQuestion: String = "This question can't be shown in this version of the app.",
    val datePlaceholder: String = "Pick a date",
    val clearDate: String = "Clear date",
    /** The date dialog's buttons. (iOS's inline picker has none.) */
    val dateConfirm: String = "OK",
    val dateCancel: String = "Cancel",
    /** Spoken after a required question's label. */
    val required: String = "required",
    /** The close button's accessibility label. */
    val closeForm: String = "Close form",
    /** The progress bar's accessibility label. */
    val progressLabel: String = "Progress",
    /** The progress bar's spoken value. */
    val progressValue: String = "Question {n} of {total}",
    /** Announced when a new step appears. */
    val stepAnnouncement: String = "Step {n} of {total}",
) {
    /** [template] with `{n}` and `{total}` filled in. */
    internal fun fill(template: String, n: Int, total: Int): String =
        template.replace("{n}", n.toString()).replace("{total}", total.toString())

    internal fun minLengthMessage(count: Int): String = validationMinLength.replace("{count}", count.toString())

    internal fun loadErrorMessage(status: Int): String = loadErrorStatus.replace("{status}", status.toString())
}
