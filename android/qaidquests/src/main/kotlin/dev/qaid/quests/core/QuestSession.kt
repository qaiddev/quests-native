package dev.qaid.quests.core

/**
 * Where the person is in a quest: the web embed's step flow with no drawing in it. One
 * question per step; [index] points into the *visible* questions, which are recomputed on
 * every answer. Validation runs on Next only; Back never validates. Not thread-safe.
 */
internal class QuestSession(
    val quest: Questionnaire,
    private val text: QaidQuestText,
    private val effects: Effects,
) {
    /** What the session asks of the response traffic. */
    interface Effects {
        fun save(questionId: String, value: QuestAnswer, immediate: Boolean)
        fun flush()
    }

    enum class Nav { INVALID, MOVED, SUBMIT }

    private val answers = LinkedHashMap<String, QuestAnswer>()

    var index: Int = 0
        private set

    /** The current step's validation message; cleared by the next change to its answer. */
    var error: String? = null
        private set

    var visible: List<Question> = Visibility.visibleQuestions(quest.questions, answers)
        private set

    val current: Question get() = visible[index]

    /** No question after this one is visible under the current answers: the button submits. */
    val isLast: Boolean get() = index >= visible.size - 1

    val canGoBack: Boolean get() = index > 0

    val primaryLabel: String get() = if (isLast) quest.submitLabel ?: text.submit else quest.nextLabel ?: text.next

    val backLabel: String get() = quest.backLabel ?: text.back

    /** Hidden when the quest says so or has one question in all (the raw count, as on the web). */
    val showsProgress: Boolean get() = !quest.hideProgress && quest.questions.size > 1

    /** `n / total` over the visible questions; the fill starts at 1/total, as on the web. */
    val progressFraction: Float get() = (index + 1).toFloat() / visible.size

    init {
        enterStep()
    }

    /** What the control for [question] shows: its answer, or the empty value for its type. */
    fun value(question: Question): QuestAnswer = answers[question.id] ?: empty(question)

    fun hasAnswer(questionId: String): Boolean = questionId in answers

    /** The person changed an answer. Debounced for typing and the slider; immediate otherwise. */
    fun setAnswer(questionId: String, value: QuestAnswer) {
        val question = quest.questions.firstOrNull { it.id == questionId } ?: return
        if (Unsupported.isUnsupported(question)) return
        answers[questionId] = value
        error = null
        visible = Visibility.visibleQuestions(quest.questions, answers)
        val typed = question is TextQuestion || question is CurrencyQuestion || question is RangeQuestion
        effects.save(questionId, value, immediate = !typed)
    }

    /**
     * Validate → save now (every visited question is saved, untouched ones with their empty
     * value, as on the web) → [Nav.SUBMIT] on the last visible question, else the next step.
     */
    fun next(): Nav {
        val question = current
        val value = value(question)
        QuestValidation.message(question, value, text)?.let {
            error = it
            return Nav.INVALID
        }
        if (!Unsupported.isUnsupported(question)) {
            answers[question.id] = value
            visible = Visibility.visibleQuestions(quest.questions, answers)
            effects.save(question.id, value, immediate = true)
        }
        if (isLast) return Nav.SUBMIT
        index++
        error = null
        enterStep()
        return Nav.MOVED
    }

    /** One step back, no validation; pending typing is saved first. False on the first step. */
    fun back(): Boolean {
        effects.flush()
        if (index == 0) return false
        index--
        error = null
        enterStep()
        return true
    }

    /** What is submitted and handed to onComplete: visible questions' answers only. */
    fun submittedAnswers(): Map<String, QuestAnswer> = Visibility.visibleAnswers(quest.questions, answers)

    /**
     * Questions answered and then hidden: the server keeps what was saved, so each is cleared
     * with a save of null before submit (an answer already null needs none).
     */
    fun hiddenAnswered(): List<String> {
        val submitted = submittedAnswers()
        return answers.filter { (id, value) -> id !in submitted && value != QuestAnswer.None }.keys.toList()
    }

    /**
     * A range counts as answered once shown: the web pushes the slider's starting value the
     * moment the step draws, so `answered` / `equals` rules on it agree with the web.
     */
    private fun enterStep() {
        val question = current
        if (question is RangeQuestion && question.id !in answers) {
            setAnswer(question.id, QuestAnswer.Number(RangeRules.initial(question)))
        }
    }

    private fun empty(question: Question): QuestAnswer = when (question) {
        is TextQuestion -> QuestAnswer.Text("")
        is ChoiceQuestion -> if (question.multiple) QuestAnswer.Choices(emptyList()) else QuestAnswer.None
        is RangeQuestion -> QuestAnswer.Number(RangeRules.initial(question))
        else -> QuestAnswer.None
    }
}
