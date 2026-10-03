package dev.qaid.quests.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/** How a submit ended. */
sealed interface SubmitOutcome {
    data object Sent : SubmitOutcome

    /** qaid couldn't be reached: everything still unsent, to save and send later. */
    data class Offline(val pending: PendingResponse) : SubmitOutcome

    /** qaid refused the create or the submit (4xx). */
    data class Failed(val error: QaidQuestError) : SubmitOutcome
}

/**
 * One response's traffic: the create, then every answer save, then the submit, in that
 * order, one at a time. Improves on the web embed (which fires saves concurrently and never
 * checks them): a save made before the create returns waits for it; a network failure or 5xx
 * is retried with backoff; a 4xx is reported through [onError] and dropped. Typing is
 * debounced per question; date and choice answers go at once.
 *
 * [busy] is true while a call is in flight or waiting to retry: the "Saving…" indicator.
 * Not thread-safe: drive it from one thread (the SDK uses the main thread; network calls
 * suspend inside the transport).
 */
class ResponseSync(
    private val transport: QuestTransport,
    private val endpoints: QuestEndpoints,
    private val createBody: String,
    private val userAgent: String,
    private val scope: CoroutineScope,
    private val retry: RetryPolicy = RetryPolicy(),
    private val debounceMs: Long = 500,
    private val now: () -> Long = System::currentTimeMillis,
    private val busy: MutableStateFlow<Boolean> = MutableStateFlow(false),
    private val onError: (QuestOperation, QaidQuestError) -> Unit = { _, _ -> },
) {
    var responseId: String? = null
        private set

    private val queue = ArrayDeque<PendingPatch>()
    private val debounced = LinkedHashMap<String, Pair<QuestAnswer, Job>>()
    private val lastQueued = HashMap<String, QuestAnswer>()
    private var worker: Deferred<Drain>? = null
    private var createError: QaidQuestError? = null
    private var finished = false

    private sealed interface Drain {
        data class Done(val responseId: String) : Drain
        data object Offline : Drain
        data class Refused(val error: QaidQuestError) : Drain
    }

    /** Creates the response. Called when the quest first appears, as the web embed does. */
    fun start() = kick()

    /** Saves an answer: after [debounceMs] of quiet, or now when [immediate]. */
    fun answer(questionId: String, value: QuestAnswer, immediate: Boolean) {
        if (finished) return
        debounced.remove(questionId)?.let { it.second.cancel() }
        if (immediate || debounceMs <= 0) {
            enqueue(questionId, value)
            return
        }
        debounced[questionId] = value to scope.launch {
            delay(debounceMs)
            debounced.remove(questionId)
            enqueue(questionId, value)
        }
    }

    /** Sends every debounced answer now (Next, Back, Submit, close). */
    fun flush() {
        val pending = debounced.toList()
        debounced.clear()
        for ((questionId, entry) in pending) {
            entry.second.cancel()
            enqueue(questionId, entry.first)
        }
    }

    /**
     * Flushes, clears [hidden] questions' stored answers (a PATCH of null each, since the
     * server keeps only what was PATCHed), then submits [answers]. Safe to call again after
     * [SubmitOutcome.Failed]; a create refused earlier is tried once more, as the web does.
     */
    suspend fun submit(answers: Map<String, QuestAnswer>, hidden: List<String>): SubmitOutcome {
        if (finished) return SubmitOutcome.Sent
        flush()
        hidden.forEach { enqueue(it, QuestAnswer.None) }
        worker?.await()
        createError = null
        val body = QuestRequests.submitBody(answers)
        val id = when (val drained = drainNow()) {
            is Drain.Refused -> return SubmitOutcome.Failed(drained.error)
            is Drain.Done -> drained.responseId
            else -> return handOff(body)
        }
        return when (val r = transport.call(QuestRequests.submit(endpoints, id, userAgent, body), retry, ::delay)) {
            is CallOutcome.Ok -> {
                finished = true
                SubmitOutcome.Sent
            }
            is CallOutcome.Refused -> SubmitOutcome.Failed(r.error).also { onError(QuestOperation.SUBMIT, r.error) }
            else -> handOff(body)
        }
    }

    /** Everything unsent, for the offline queue. This sync sends nothing more. */
    private fun handOff(submitBody: String): SubmitOutcome {
        finished = true
        val pending = PendingResponse(
            id = "${now()}-${UUID.randomUUID()}",
            createdAtMs = now(),
            base = endpoints.base,
            userAgent = userAgent,
            responseId = responseId,
            createBody = createBody,
            patches = queue.toList(),
            submitBody = submitBody,
        )
        queue.clear()
        return SubmitOutcome.Offline(pending)
    }

    private fun enqueue(questionId: String, value: QuestAnswer) {
        if (lastQueued[questionId] == value) return
        lastQueued[questionId] = value
        queue.addLast(PendingPatch(questionId, value))
        kick()
    }

    private fun kick() {
        if (worker?.isActive == true) return
        worker = scope.async { drain() }
    }

    private suspend fun drainNow(): Drain = scope.async { drain() }.also { worker = it }.await()

    private suspend fun drain(): Drain {
        busy.value = true
        try {
            val id = responseId ?: create() ?: return createError?.let { Drain.Refused(it) } ?: Drain.Offline
            while (queue.isNotEmpty()) {
                val patch = queue.first()
                val body = QuestRequests.patchBody(patch.questionId, patch.value)
                when (val r = transport.call(QuestRequests.patch(endpoints, id, userAgent, body), retry, ::delay)) {
                    is CallOutcome.Refused -> onError(QuestOperation.SAVE_ANSWER, r.error)
                    is CallOutcome.Ok -> Unit
                    else -> return Drain.Offline
                }
                queue.removeFirst()
            }
            return Drain.Done(id)
        } finally {
            busy.value = false
        }
    }

    /** The new response's id, or null: offline, or refused (then [createError] says why). */
    private suspend fun create(): String? {
        if (createError != null) return null
        return when (val r = transport.call(QuestRequests.create(endpoints, userAgent, createBody), retry, ::delay)) {
            is CallOutcome.Ok -> QuestRequests.createdId(r.body).also { id ->
                if (id == null) refuseCreate(QaidQuestError.Decoding("no response id")) else responseId = id
            }
            is CallOutcome.Refused -> null.also { refuseCreate(r.error) }
            else -> null
        }
    }

    private fun refuseCreate(error: QaidQuestError) {
        createError = error
        onError(QuestOperation.CREATE_RESPONSE, error)
    }
}
