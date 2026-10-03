package dev.qaid.quests.core

import org.json.JSONException
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import java.io.IOException

/** One HTTP call, built in Core so its shape is tested without a network. */
data class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
)

data class HttpResponse(val status: Int, val body: String)

/**
 * The network, injectable: the SDK's own sends over HttpURLConnection; tests script one.
 * Throws [IOException] when the server can't be reached.
 */
fun interface QuestTransport {
    suspend fun send(request: HttpRequest): HttpResponse
}

/** Why a call to qaid failed. */
sealed class QaidQuestError(message: String) : Exception(message) {
    /** The server couldn't be reached. */
    data class Network(val detail: String) : QaidQuestError("network: $detail")

    /** The server answered [status]; [error] is its `{ "error": … }` text. */
    data class Http(val status: Int, val error: String) : QaidQuestError("HTTP $status $error".trim())

    /** The body couldn't be read. */
    data class Decoding(val detail: String) : QaidQuestError(detail)

    /** Answers couldn't be saved to send later. */
    data class Storage(val detail: String) : QaidQuestError(detail)

    /** Worth trying again by itself: the network or the server, never a refused request. */
    val isRetryable: Boolean
        get() = this is Network || (this is Http && (status >= 500 || status == 408 || status == 429))
}

/** Which call failed, for [QaidQuestErrorEvent]. */
enum class QuestOperation { LOAD_DEFINITION, CREATE_RESPONSE, SAVE_ANSWER, SUBMIT, SEND_SAVED }

/** Handed to `QaidQuests.onError`: never thrown, never blocks the person answering. */
data class QaidQuestErrorEvent(val questId: String, val operation: QuestOperation, val error: QaidQuestError)

/** How often and how long to wait before trying a call again. */
data class RetryPolicy(val delaysMs: List<Long> = listOf(1_000, 3_000)) {
    /** The wait before retry number [attempt] (0-based), or null to stop. */
    fun delayAfter(attempt: Int): Long? = delaysMs.getOrNull(attempt)
}

/** The quest endpoints under one `base` (`https://qaid.dev/api/quests`). */
data class QuestEndpoints(val base: String) {
    private val root = base.trim().trimEnd('/')

    fun definition(questId: String): String = "$root/${QaidQuestsConfig.encode(questId)}/definition"
    val responses: String get() = "$root/responses"
    fun response(id: String): String = "$responses/${QaidQuestsConfig.encode(id)}"
    fun submit(id: String): String = "${response(id)}/submit"
}

object QuestRequests {
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
sealed interface CallOutcome {
    data class Ok(val body: String) : CallOutcome

    /** The server said no (4xx): sending it again won't help. */
    data class Refused(val error: QaidQuestError) : CallOutcome

    /** Still offline, or the server still failing, after every retry. */
    data class Unreachable(val error: QaidQuestError) : CallOutcome
}

/** Sends [request], retrying network failures and 5xx by [retry], never a 4xx. */
suspend fun QuestTransport.call(
    request: HttpRequest,
    retry: RetryPolicy,
    sleep: suspend (Long) -> Unit,
): CallOutcome {
    var attempt = 0
    while (true) {
        val error = try {
            val response = send(request)
            if (response.status in 200..299) return CallOutcome.Ok(response.body)
            QaidQuestError.Http(response.status, QuestRequests.errorText(response.body))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // IOException is the network; anything else (a missing INTERNET permission, a
            // malformed URL) is reported the same way rather than crashing the app.
            QaidQuestError.Network(e.message ?: e.javaClass.simpleName)
        }
        if (!error.isRetryable) return CallOutcome.Refused(error)
        val wait = retry.delayAfter(attempt++) ?: return CallOutcome.Unreachable(error)
        sleep(wait)
    }
}
