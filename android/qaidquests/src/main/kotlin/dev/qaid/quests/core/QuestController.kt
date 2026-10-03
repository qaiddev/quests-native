package dev.qaid.quests.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

enum class QuestPhase { LOADING, LOAD_FAILED, ANSWERING, SUBMITTING, SUBMIT_FAILED, DONE }

/** The step on screen. */
data class QuestStep(
    val index: Int,
    val total: Int,
    val question: Question,
    val value: QuestAnswer,
    val error: String?,
    val primaryLabel: String,
    /** Null on the first step: no Back button. */
    val backLabel: String?,
    val unsupported: Boolean,
)

/** Everything the UI draws, as one value; the UI holds no logic of its own. */
data class QuestViewState(
    val phase: QuestPhase = QuestPhase.LOADING,
    val style: QuestStyle = QuestStyles.DEFAULT,
    val text: QaidQuestText = QaidQuestText(),
    /** The detail under the load-error title. */
    val loadError: String? = null,
    /** Null when the quest hides its title or has none. */
    val title: String? = null,
    val description: String? = null,
    val step: QuestStep? = null,
    val showProgress: Boolean = false,
    val progressFraction: Float = 0f,
    val progressPosition: ProgressPosition = ProgressPosition.TOP,
    val thankYouTitle: String = text.thankYouTitle,
    val thankYouMessage: String? = null,
    /** The answers were saved on the device to send later. */
    val savedOffline: Boolean = false,
)

/** Who sees what happens: the host's callbacks, the log, and the offline queue. */
interface QuestCallbacks {
    fun onComplete(answers: Map<String, QuestAnswer>)
    fun onError(event: QaidQuestErrorEvent)
    fun log(message: String)

    /** Saves a response that couldn't be sent. False when it couldn't be written. */
    fun persist(pending: PendingResponse): Boolean
}

/** What a response is created with, gathered by the platform layer when the view appears. */
data class QuestEnvironment(
    val pageUrl: String,
    val visitorId: String,
    val userAgent: String,
    val metadata: JSONObject,
)

/**
 * One quest on screen, start to finish: loads the definition (and theme, in parallel),
 * creates the response, drives the [QuestSession], and submits — or saves the answers to
 * send later when qaid can't be reached. Runs on [scope]'s thread; the UI calls in and
 * renders [state].
 */
class QuestController(
    private val questId: String,
    private val config: QaidQuestsConfig,
    private val environment: QuestEnvironment,
    private val transport: QuestTransport,
    private val scope: CoroutineScope,
    private val callbacks: QuestCallbacks,
    private val retry: RetryPolicy = RetryPolicy(),
) {
    private val text = config.text
    private val endpoints = QuestEndpoints(config.base)
    private val _state = MutableStateFlow(QuestViewState(text = text, style = QuestStyles.build(null, config.theme)))
    private var started = false
    private var running: Running? = null
    private var autoAdvance: Job? = null

    /** The quest once loaded: where the person is, and the response's traffic. */
    private class Running(val session: QuestSession, val sync: ResponseSync)

    val state: StateFlow<QuestViewState> = _state.asStateFlow()

    private val _saving = MutableStateFlow(false)

    /** "Saving…": a save or the create is in flight. */
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    /** Loads the quest. Nothing is fetched or sent before this; calling it again does nothing. */
    fun start() {
        if (started) return
        started = true
        load()
    }

    fun retryLoad() {
        if (_state.value.phase == QuestPhase.LOAD_FAILED) load()
    }

    fun answer(questionId: String, value: QuestAnswer) {
        val run = active() ?: return
        run.session.setAnswer(questionId, value)
        publish(run)
        val question = run.session.current
        if (config.autoAdvance && question.id == questionId && question is ChoiceQuestion && !question.multiple) {
            autoAdvance?.cancel()
            // Next, Back and close cancel this, and a new pick replaces it, so when it fires the
            // person is still on this step.
            autoAdvance = scope.launch {
                delay(AUTO_ADVANCE_MS)
                next()
            }
        }
    }

    fun next() {
        val run = active() ?: return
        autoAdvance?.cancel()
        when (run.session.next()) {
            QuestSession.Nav.SUBMIT -> submit(run)
            else -> publish(run)
        }
    }

    fun back() {
        val run = active() ?: return
        autoAdvance?.cancel()
        run.session.back()
        publish(run)
    }

    fun retrySubmit() {
        if (_state.value.phase == QuestPhase.SUBMIT_FAILED) running?.let(::submit)
    }

    /** The view is going away: send what's typed. Does not submit (closing never does). */
    fun close() {
        autoAdvance?.cancel()
        running?.let { it.sync.flush() }
    }

    private fun active(): Running? = running?.takeIf { _state.value.phase == QuestPhase.ANSWERING }

    private fun load() {
        _state.value = _state.value.copy(phase = QuestPhase.LOADING, loadError = null)
        scope.launch {
            val theme = async { loadTheme() }
            val quest = loadDefinition()
            val style = theme.await()
            if (quest == null) {
                _state.value = _state.value.copy(phase = QuestPhase.LOAD_FAILED, style = style)
                return@launch
            }
            begin(quest, style)
        }
    }

    private suspend fun loadDefinition(): Questionnaire? {
        val request = QuestRequests.get(endpoints.definition(questId), environment.userAgent)
        val detail = when (val r = transport.call(request, RetryPolicy(emptyList()), ::delay)) {
            is CallOutcome.Ok -> try {
                return QuestDecoder.questionnaire(r.body, questId)
            } catch (e: QuestDecodeException) {
                reportLoad(QaidQuestError.Decoding(e.detail))
                if (e.detail == EMPTY) text.loadErrorEmpty else text.loadErrorInvalid
            }
            is CallOutcome.Refused -> statusDetail(r.error)
            is CallOutcome.Unreachable -> statusDetail(r.error)
        }
        _state.value = _state.value.copy(loadError = detail)
        return null
    }

    private fun statusDetail(error: QaidQuestError): String {
        reportLoad(error)
        return if (error is QaidQuestError.Http) text.fill(text.loadErrorStatus, error.status) else text.loadErrorOffline
    }

    private fun reportLoad(error: QaidQuestError) =
        callbacks.onError(QaidQuestErrorEvent(questId, QuestOperation.LOAD_DEFINITION, error))

    /** A failed or unreadable theme is only logged: the quest draws with qaid's defaults. */
    private suspend fun loadTheme(): QuestStyle {
        val url = config.themeUrl() ?: return QuestStyles.build(null, config.theme)
        val doc = when (val r = transport.call(QuestRequests.get(url, environment.userAgent), RetryPolicy(emptyList()), ::delay)) {
            is CallOutcome.Ok -> runCatching { QuestThemeDoc.decode(r.body) }.getOrNull()
            else -> null
        }
        if (doc == null) callbacks.log("quest theme ${config.themeId} couldn't be loaded; using defaults")
        if (doc?.ignoredCss == true || doc?.unstyled == true) {
            callbacks.log("quest theme ${config.themeId}: custom CSS and 'unstyled' have no native equivalent and are ignored")
        }
        return QuestStyles.build(doc, config.theme)
    }

    private fun begin(quest: Questionnaire, style: QuestStyle) {
        quest.questions.filter(Unsupported::isUnsupported).forEach {
            callbacks.log("question ${it.id} has type '${(it as UnknownQuestion).type}', which this SDK version can't show")
        }
        val createBody = QuestRequests.createBody(
            config.apiKey, quest.id, environment.pageUrl, environment.visitorId, environment.userAgent, environment.metadata,
        )
        val sync = ResponseSync(
            transport, endpoints, createBody, environment.userAgent, scope, retry, config.saveDebounceMs, busy = _saving,
        ) { operation, error -> callbacks.onError(QaidQuestErrorEvent(questId, operation, error)) }
        sync.start()
        val session = QuestSession(quest, text, object : QuestSession.Effects {
            override fun save(questionId: String, value: QuestAnswer, immediate: Boolean) = sync.answer(questionId, value, immediate)
            override fun flush() = sync.flush()
        })
        val run = Running(session, sync)
        running = run
        _state.value = _state.value.copy(
            phase = QuestPhase.ANSWERING,
            style = style,
            title = quest.title?.takeIf { !quest.hideTitle && it.isNotBlank() },
            description = quest.description?.takeIf { !quest.hideTitle && it.isNotBlank() },
            progressPosition = config.progressPosition ?: quest.progressPosition ?: ProgressPosition.TOP,
            thankYouTitle = quest.thankYouTitle ?: text.thankYouTitle,
            thankYouMessage = quest.thankYouMessage,
        )
        publish(run)
    }

    private fun submit(run: Running) {
        val session = run.session
        val sync = run.sync
        _state.value = _state.value.copy(phase = QuestPhase.SUBMITTING)
        val answers = session.submittedAnswers()
        val hidden = session.hiddenAnswered()
        scope.launch {
            val phase = when (val outcome = sync.submit(answers, hidden)) {
                SubmitOutcome.Sent -> QuestPhase.DONE
                is SubmitOutcome.Offline -> if (callbacks.persist(outcome.pending)) {
                    _state.value = _state.value.copy(savedOffline = true)
                    QuestPhase.DONE
                } else {
                    callbacks.onError(QaidQuestErrorEvent(questId, QuestOperation.SUBMIT, QaidQuestError.Storage("couldn't save answers to send later")))
                    QuestPhase.SUBMIT_FAILED
                }
                is SubmitOutcome.Failed -> QuestPhase.SUBMIT_FAILED
            }
            _state.value = _state.value.copy(phase = phase)
            if (phase == QuestPhase.DONE) callbacks.onComplete(answers)
        }
    }

    private fun publish(run: Running) {
        val session = run.session
        val question = session.current
        _state.value = _state.value.copy(
            step = QuestStep(
                index = session.index,
                total = session.visible.size,
                question = question,
                value = session.value(question),
                error = session.error,
                primaryLabel = session.primaryLabel,
                backLabel = session.backLabel.takeIf { session.canGoBack },
                unsupported = Unsupported.isUnsupported(question),
            ),
            showProgress = session.showsProgress,
            progressFraction = session.progressFraction,
        )
    }

    private companion object {
        const val AUTO_ADVANCE_MS = 180L
        const val EMPTY = "Questionnaire is empty"
    }
}
