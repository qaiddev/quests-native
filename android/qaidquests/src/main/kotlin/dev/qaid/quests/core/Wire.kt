package dev.qaid.quests.core

import org.json.JSONException
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import java.io.IOException

/** One HTTP call, built in Core so its shape is tested without a network. */
internal data class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
)

internal data class HttpResponse(val status: Int, val body: String)

/**
 * The network, injectable: the SDK's own sends over HttpURLConnection; tests script one.
 * Throws [IOException] when the server can't be reached.
 */
internal fun interface QuestTransport {
    suspend fun send(request: HttpRequest): HttpResponse
}

/** What went wrong talking to qaid, for `QaidQuests.onError`. */
sealed class QaidQuestsError(message: String) : Exception(message) {
    /** The server was never reached: offline, timed out, DNS. */
    data class Network(val detail: String) : QaidQuestsError("network: $detail")

    /** A 5xx. Tried again with backoff, then queued. */
    data class Server(val status: Int) : QaidQuestsError("HTTP $status")

    /**
     * A 4xx: the request itself was refused (bad key, Domain Restriction, quest archived…).
     * [message] is the server's `error` text. Never retried, except 408 and 429.
     */
    data class Rejected(val status: Int, override val message: String) : QaidQuestsError("HTTP $status $message".trim())

    /** A 2xx without what it should carry, or a body that isn't JSON. */
    data class InvalidResponse(val detail: String) : QaidQuestsError(detail)

    /** The offline queue couldn't be written. */
    data class Storage(val detail: String) : QaidQuestsError(detail)

    /** Worth trying again by itself: the network or the server, never the request. 408 and 429 mean "later". */
    val isRetryable: Boolean
        get() = this is Network || this is Server || (this is Rejected && (status == 408 || status == 429))

    internal companion object {
        /** The error a non-2xx status means. */
        fun of(status: Int, body: String): QaidQuestsError =
            if (status >= 500) Server(status) else Rejected(status, QuestRequests.errorText(body))
    }
}

/** One failure, with what the SDK was doing when it happened. Never thrown, never blocks the person answering. */
data class QaidQuestsErrorEvent(
    val questId: String,
    val operation: Operation,
    /** For [Operation.SAVE_ANSWER]. */
    val questionId: String? = null,
    val error: QaidQuestsError,
) {
    enum class Operation { LOAD_QUEST, LOAD_THEME, CREATE_RESPONSE, SAVE_ANSWER, SUBMIT, OFFLINE_QUEUE }
}

/** How often and how long to wait before trying a call again. */
internal data class RetryPolicy(val delaysMs: List<Long> = listOf(1_000, 3_000)) {
    /** The wait before retry number [attempt] (0-based), or null to stop. */
    fun delayAfter(attempt: Int): Long? = delaysMs.getOrNull(attempt)
}

/** The quest endpoints under one `base` (`https://qaid.dev/api/quests`). */
internal data class QuestEndpoints(val base: String) {
    private val root = base.trim().trimEnd('/')

    fun definition(questId: String): String = "$root/${QaidQuestsConfig.encode(questId)}/definition"
    val responses: String get() = "$root/responses"
    fun response(id: String): String = "$responses/${QaidQuestsConfig.encode(id)}"
    fun submit(id: String): String = "${response(id)}/submit"
}

internal object QuestRequests {
    private const val JSON = "application/json"

    fun get(url: String, userAgent: String): HttpRequest =
        HttpRequest("GET", url, mapOf("Accept" to JSON, "User-Agent" to userAgent))

    private fun json(method: String, url: String, userAgent: String, body: String) =
        HttpRequest(method, url, mapOf("Content-Type" to JSON, "Accept" to JSON, "User-Agent" to userAgent), body)

    /**
     * `POST {base}/responses`. The key travels in the body — the server reads only that —
     * and is left out when empty, as the web embed does.
     */
    fun createBody(
        apiKey: String,
        questId: String,
        pageUrl: String,
        visitorId: String,
        userAgent: String,
        metadata: JSONObject,
    ): String = JSONObject().apply {
        if (apiKey.isNotEmpty()) put("apiKey", apiKey)
        put("questId", questId)
        put("pageUrl", pageUrl)
        put("visitorId", visitorId)
        put("userAgent", userAgent)
        put("metadata", metadata)
    }.toString()

    fun create(endpoints: QuestEndpoints, userAgent: String, body: String) =
        json("POST", endpoints.responses, userAgent, body)

    fun patchBody(questionId: String, value: QuestAnswer): String =
        JSONObject().put("questionId", questionId).put("value", value.toJson()).toString()

    fun patch(endpoints: QuestEndpoints, responseId: String, userAgent: String, body: String) =
        json("PATCH", endpoints.response(responseId), userAgent, body)

    /** The server reads no body here — answers come from the PATCHes — but the embed sends one. */
    fun submitBody(answers: Map<String, QuestAnswer>): String =
        JSONObject().put("answers", answersToJson(answers)).toString()

    fun submit(endpoints: QuestEndpoints, responseId: String, userAgent: String, body: String) =
        json("POST", endpoints.submit(responseId), userAgent, body)

    /** The new response's id from a create's body, or null. */
    fun createdId(body: String): String? = parse(body)?.str("id")?.takeIf { it.isNotEmpty() }

    /** An error response's `{ "error": … }` text, or "". */
    fun errorText(body: String): String = parse(body)?.str("error").orEmpty()

    private fun parse(body: String): JSONObject? = try {
        JSONObject(body)
    } catch (_: JSONException) {
        null
    }
}

/** What one call (with its retries) came to. */
internal sealed interface CallOutcome {
    data class Ok(val body: String) : CallOutcome

    /** The server said no (4xx): sending it again won't help. */
    data class Refused(val error: QaidQuestsError) : CallOutcome

    /** Still offline, or the server still failing, after every retry. */
    data class Unreachable(val error: QaidQuestsError) : CallOutcome
}

/** Sends [request], retrying network failures and 5xx by [retry], never a 4xx. */
internal suspend fun QuestTransport.call(
    request: HttpRequest,
    retry: RetryPolicy,
    sleep: suspend (Long) -> Unit,
): CallOutcome {
    var attempt = 0
    while (true) {
        val error = try {
            val response = send(request)
            if (response.status in 200..299) return CallOutcome.Ok(response.body)
            QaidQuestsError.of(response.status, response.body)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // IOException is the network; anything else (a missing INTERNET permission, a
            // malformed URL) is reported the same way rather than crashing the app.
            QaidQuestsError.Network(e.message ?: e.javaClass.simpleName)
        }
        if (!error.isRetryable) return CallOutcome.Refused(error)
        val wait = retry.delayAfter(attempt++) ?: return CallOutcome.Unreachable(error)
        sleep(wait)
    }
}
