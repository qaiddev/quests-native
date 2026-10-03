package dev.qaid.quests.core

/**
 * Every string the SDK itself shows or speaks, for apps that are not in English. The English
 * defaults are the web embed's. A quest's own labels (its Next / Back / Submit and thank-you
 * text, set in the qaid editor) still win over these: they are the quest's content.
 * `{n}` and `{total}` are filled in where noted.
 */
data class QaidQuestText(
    val next: String = "Next",
    val back: String = "Back",
    val submit: String = "Submit",
    val thankYouTitle: String = "Thank you!",
    /** Shown on the thank-you screen when the answers were saved to send later. */
    val savedOffline: String = "Your answers are saved on this device and will be sent when you're back online.",
    val loading: String = "Loading…",
    val loadErrorTitle: String = "Couldn't load form",
    /** The detail under [loadErrorTitle] when the server answered with an error. `{n}` = HTTP status. */
    val loadErrorStatus: String = "Failed to load questionnaire ({n})",
    /** The detail under [loadErrorTitle] when the server couldn't be reached. */
    val loadErrorOffline: String = "Check your connection and try again.",
    val loadErrorEmpty: String = "Questionnaire is empty",
    val loadErrorInvalid: String = "The questionnaire couldn't be read.",
    val retry: String = "Retry",
    val submitErrorTitle: String = "Couldn't send your answers",
    val submitErrorMessage: String = "Your answers are still here. Check your connection and try again.",
    val tryAgain: String = "Try again",
    val sending: String = "Sending…",
    val saving: String = "Saving…",
    val close: String = "Close",
    /** The close button's accessibility label. */
    val closeLabel: String = "Close form",
    /** Spoken after a required question's label. */
    val requiredLabel: String = "required",
    /** The progress bar's accessibility label. */
    val progressLabel: String = "Progress",
    /** The progress bar's spoken value. */
    val progressValue: String = "Question {n} of {total}",
    /** Announced when a new step appears. */
    val stepAnnouncement: String = "Step {n} of {total}",
    val errorRequired: String = "This field is required.",
    /** `{n}` = the question's minimum length. */
    val errorMinLength: String = "Please enter at least {n} characters.",
    val errorAmount: String = "Please enter a valid amount.",
    val errorDate: String = "Please pick a date.",
    val errorOption: String = "Please select an option.",
    val errorOptions: String = "Please select at least one option.",
    val datePlaceholder: String = "Pick a date",
    val dateConfirm: String = "OK",
    val dateCancel: String = "Cancel",
    val dateClear: String = "Clear",
    /** The card drawn in place of a question this version of the app can't show. */
    val unsupportedQuestion: String = "This question can't be shown in this version of the app.",
) {
    fun fill(template: String, n: Int, total: Int = 0): String =
        template.replace("{n}", n.toString()).replace("{total}", total.toString())
}
