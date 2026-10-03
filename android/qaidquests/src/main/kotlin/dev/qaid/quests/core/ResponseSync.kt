package dev.qaid.quests.core

import dev.qaid.quests.core.QaidQuestsErrorEvent.Operation as Op
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/** How a submit ended. */
internal sealed interface SubmitOutcome {
    data object Sent : SubmitOutcome

    /** In the offline queue: it goes out when qaid can be reached. */
    data object SavedOffline : SubmitOutcome

    /** qaid refused the create or the submit (4xx), or the offline queue couldn't be written. */
    data class Failed(val error: QaidQuestsError) : SubmitOutcome
}

/**
 * One response's traffic: the create, then every answer save, then the submit, in that
 * order, one at a time. Improves on the web embed (which fires saves concurrently and never
 * checks them):
 * - a save made before the create returns waits for it;
 * - typing is debounced per question, and an answer equal to the last one queued is not resent;
 * - the network and 5xx are retried with backoff; a refused (4xx) save is reported through
 *   [onError] and dropped; a refused create waits for submit, which tries it once more;
 * - once a call can't get through, nothing more is sent until submit, so a person offline
 *   doesn't start a retry chain on every keystroke;
 * - at submit, a network failure goes straight to the offline queue ([persist]), and sending
 *   never keeps the person on "Sending…" longer than [submitTimeoutMs]. iOS has the same rules.
 *
 * [busy] is true while a call is in flight or waiting to retry: the "Saving…" indicator.
 * Not thread-safe: drive it from one thread (the SDK uses the main thread; network calls
 * suspend inside the transport).
 */
internal class ResponseSync(
    private val transport: QuestTransport,
    private val endpoints: QuestEndpoints,
    private val questId: String,
    private val createBody: String,
    private val userAgent: String,
    private val scope: CoroutineScope,
    /** Saves an unsent response to send later. False when it couldn't be written. */
    private val persist: (PendingResponse) -> Boolean,
    private val retry: RetryPolicy = RetryPolicy(),
    private val debounceMs: Long = 500,
    private val submitTimeoutMs: Long = SUBMIT_TIMEOUT_MS,
    private val now: () -> Long = System::currentTimeMillis,
    private val busy: MutableStateFlow<Boolean> = MutableStateFlow(false),
    private val onError: (QaidQuestsErrorEvent) -> Unit = {},
) {
    var responseId: String? = null
        private set

    private sealed interface Pending {
        data class Patch(val patch: PendingPatch) : Pending
        data class Submit(val body: String) : Pending
    }

    private val queue = ArrayDeque<Pending>()
    private val debounced = LinkedHashMap<String, Pair<QuestAnswer, Job>>()
    private val lastQueued = HashMap<String, QuestAnswer>()
    private var worker: Job? = null

    /** Why sending stopped: the network, a 5xx, or a refused create or submit. Submit resumes it. */
    private var stall: QaidQuestsError? = null
    private var outcome: SubmitOutcome? = null

    /** Creates the response. Called when the quest first appears, as the web embed does. */
    fun start() {
        kick()
    }

    /** Saves an answer: after [debounceMs] of quiet, or now when [immediate]. */
    fun answer(questionId: String, value: QuestAnswer, immediate: Boolean) {
        if (outcome != null) return
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
     * [SubmitOutcome.Failed]: Try again.
     */
    suspend fun submit(answers: Map<String, QuestAnswer>, hidden: List<String>): SubmitOutcome {
        outcome?.let { return it }
        flush()
        hidden.forEach { enqueue(it, QuestAnswer.None) }
        val body = QuestRequests.submitBody(answers)
        queue.removeAll { it is Pending.Submit }
        queue.addLast(Pending.Submit(body))
        // The network already failed: another round of retries would only keep the person
        // watching "Sending…". Anything else (a 5xx, a refused create) is tried once more.
        if (stall !is QaidQuestsError.Network) {
            stall = null
            val job = kick()
            val finished = withTimeoutOrNull(submitTimeoutMs) {
                job.join()
                true
            }
            if (finished == null) {
                // A cancelled call never touches the queue again, so what is unsent stays exact.
                job.cancel()
                stall = QaidQuestsError.Network("timed out after ${submitTimeoutMs / 1000} s")
            }
        }
        val error = stall ?: return finish(SubmitOutcome.Sent)
        if (!error.isRetryable) return SubmitOutcome.Failed(error)
        return saveOffline(body)
    }

    /** Everything unsent goes to the offline queue; once it is written, this sync sends nothing more. */
    private fun saveOffline(submitBody: String): SubmitOutcome {
        val pending = PendingResponse(
            id = "${now()}-${UUID.randomUUID()}",
            createdAtMs = now(),
            questId = questId,
            base = endpoints.base,
            userAgent = userAgent,
            responseId = responseId,
            createBody = createBody,
            patches = queue.mapNotNull { (it as? Pending.Patch)?.patch },
            submitBody = submitBody,
        )
        if (!persist(pending)) {
            // Nothing is lost: the answers stay here for Try again.
            val failure = QaidQuestsError.Storage("couldn't save the answers to send later")
            onError(QaidQuestsErrorEvent(questId, Op.OFFLINE_QUEUE, error = failure))
            return SubmitOutcome.Failed(failure)
        }
        queue.clear()
        return finish(SubmitOutcome.SavedOffline)
    }

    private fun finish(result: SubmitOutcome): SubmitOutcome {
        outcome = result
        return result
    }

    private fun enqueue(questionId: String, value: QuestAnswer) {
        if (lastQueued[questionId] == value) return
        lastQueued[questionId] = value
        queue.addLast(Pending.Patch(PendingPatch(questionId, value)))
        if (stall == null) kick()
    }

    /** The running drain, or a new one. */
    private fun kick(): Job {
        worker?.takeIf { it.isActive }?.let { return it }
        return scope.launch { drain() }.also { worker = it }
    }

    private suspend fun drain() {
        busy.value = true
        try {
            while (stall == null) {
                val id = responseId ?: create() ?: return
                val next = queue.firstOrNull() ?: return
                val request = when (next) {
                    is Pending.Patch -> QuestRequests.patch(
                        endpoints, id, userAgent, QuestRequests.patchBody(next.patch.questionId, next.patch.value),
                    )
                    is Pending.Submit -> QuestRequests.submit(endpoints, id, userAgent, next.body)
                }
                when (val r = transport.call(request, retry, ::delay)) {
                    is CallOutcome.Ok -> Unit
                    is CallOutcome.Unreachable -> {
                        stall = r.error
                        return
                    }
                    is CallOutcome.Refused -> if (next is Pending.Patch) {
                        onError(QaidQuestsErrorEvent(questId, Op.SAVE_ANSWER, next.patch.questionId, r.error))
                    } else {
                        stall = r.error
                        onError(QaidQuestsErrorEvent(questId, Op.SUBMIT, error = r.error))
                        return
                    }
                }
                queue.removeFirst()
            }
        } finally {
            busy.value = false
        }
    }

    /** The new response's id, or null with [stall] saying why. */
    private suspend fun create(): String? =
        when (val r = transport.call(QuestRequests.create(endpoints, userAgent, createBody), retry, ::delay)) {
            is CallOutcome.Ok -> QuestRequests.createdId(r.body).also { id ->
                if (id == null) refuseCreate(QaidQuestsError.InvalidResponse("no response id")) else responseId = id
            }
            is CallOutcome.Refused -> null.also { refuseCreate(r.error) }
            is CallOutcome.Unreachable -> null.also { stall = r.error }
        }

    private fun refuseCreate(error: QaidQuestsError) {
        stall = error
        onError(QaidQuestsErrorEvent(questId, Op.CREATE_RESPONSE, error = error))
    }

    companion object {
        /** The longest a submit keeps the person on "Sending…" before the answers go to the queue. */
        const val SUBMIT_TIMEOUT_MS = 20_000L
    }
}
