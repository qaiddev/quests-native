package dev.qaid.quests.core

import dev.qaid.quests.core.QaidQuestsErrorEvent.Operation as Op
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One answer save still to send. */
internal data class PendingPatch(val questionId: String, val value: QuestAnswer)

/**
 * A finished response that couldn't reach qaid, saved to send later in order: the create
 * (when it never got an id), the answer saves, then the submit.
 */
internal data class PendingResponse(
    val id: String,
    val createdAtMs: Long,
    /** For error events; "" in a file saved before it was recorded. */
    val questId: String,
    /** The quests base it was answered against. */
    val base: String,
    val userAgent: String,
    /** Null until a create has gone through. */
    val responseId: String?,
    /** The create body; unused once [responseId] is known. */
    val createBody: String,
    val patches: List<PendingPatch>,
    val submitBody: String?,
) {
    fun encode(): String = JSONObject().apply {
        put("v", 1)
        put("id", id)
        put("createdAt", createdAtMs)
        put("questId", questId)
        put("base", base)
        put("userAgent", userAgent)
        responseId?.let { put("responseId", it) }
        put("createBody", createBody)
        put("patches", JSONArray().apply {
            patches.forEach { put(JSONObject().put("questionId", it.questionId).put("value", it.value.toJson())) }
        })
        submitBody?.let { put("submitBody", it) }
    }.toString()

    companion object {
        /** A saved response, or null for a file this version can't read (it is then dropped). */
        fun decode(raw: String): PendingResponse? = runCatching {
            val obj = JSONObject(raw)
            if (obj.optInt("v") != 1) return null
            val array = obj.getJSONArray("patches")
            val patches = (0 until array.length()).map { i ->
                val p = array.getJSONObject(i)
                PendingPatch(p.getString("questionId"), QuestAnswer.fromJson(p.opt("value")))
            }
            PendingResponse(
                id = obj.getString("id"),
                createdAtMs = obj.getLong("createdAt"),
                questId = obj.optString("questId"),
                base = obj.getString("base"),
                userAgent = obj.optString("userAgent"),
                responseId = obj.str("responseId"),
                createBody = obj.getString("createBody"),
                patches = patches,
                submitBody = obj.str("submitBody"),
            )
        }.getOrNull()
    }
}

/** How much the offline queue may hold. */
internal object PendingPolicy {
    const val MAX_ITEMS = 20
    const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

    /**
     * Ids to delete: everything older than a week (or stamped more than a week in the future,
     * a clock that jumped), then the oldest until at most [maxItems] remain.
     */
    fun toDrop(entries: List<Pair<String, Long>>, nowMs: Long, maxItems: Int = MAX_ITEMS, maxAgeMs: Long = MAX_AGE_MS): List<String> {
        val drop = ArrayList<String>()
        val kept = ArrayList<String>()
        for ((id, createdAt) in entries.sortedBy { it.second }) {
            val age = nowMs - createdAt
            if (age > maxAgeMs || age < -maxAgeMs) drop += id else kept += id
        }
        drop += kept.take((kept.size - maxItems).coerceAtLeast(0))
        return drop
    }
}

/**
 * The queue on disk: one `<id>.json` per response in [dir] (the app's no-backup directory —
 * a response holds the embed key and the person's answers, which don't belong in a cloud
 * backup). A file is written to `.tmp` then renamed, so a file on disk is always whole.
 */
internal class PendingStore(private val dir: File, private val now: () -> Long = System::currentTimeMillis) {
    private val lock = Any()

    /** Saves [response] and applies [PendingPolicy]. False when it couldn't be written. */
    fun save(response: PendingResponse): Boolean = synchronized(lock) {
        try {
            if (!dir.isDirectory && !dir.mkdirs()) return false
            val tmp = File(dir, "${response.id}.tmp")
            tmp.writeText(response.encode())
            val target = file(response.id)
            if (!tmp.renameTo(target)) {
                tmp.delete()
                return false
            }
            prune()
            target.exists()
        } catch (_: Exception) {
            false
        }
    }

    /** Everything saved, oldest first, after dropping what the policy or a bad file says to. */
    fun loadAll(): List<PendingResponse> = synchronized(lock) {
        prune()
        files().mapNotNull { f -> PendingResponse.decode(f.readText()) }.sortedBy { it.createdAtMs }
    }

    fun delete(id: String) {
        synchronized(lock) { file(id).delete() }
    }

    private fun prune() {
        // A leftover .tmp is a crash mid-save.
        dir.listFiles { f -> f.name.endsWith(".tmp") }?.forEach { it.delete() }
        val entries = files().mapNotNull { f ->
            val response = runCatching { PendingResponse.decode(f.readText()) }.getOrNull()
            if (response == null) {
                f.delete()
                null
            } else {
                response.id to response.createdAtMs
            }
        }
        PendingPolicy.toDrop(entries, now()).forEach { file(it).delete() }
    }

    private fun files(): List<File> = dir.listFiles { f -> f.name.endsWith(".json") }?.toList().orEmpty()

    private fun file(id: String) = File(dir, "$id.json")
}

/**
 * Sends what [store] holds, oldest first and each in order (create → saves → submit). A
 * refused call (4xx) is dropped and reported — it would never succeed — and a call that
 * can't reach the server stops the whole flush, keeping it and everything after it for next time.
 */
internal class PendingFlusher(
    private val store: PendingStore,
    private val transport: QuestTransport,
    private val retry: RetryPolicy = RetryPolicy(emptyList()),
    private val sleep: suspend (Long) -> Unit = {},
    private val onRefused: (QaidQuestsErrorEvent) -> Unit = {},
) {
    /** How many responses were fully sent. */
    suspend fun flush(): Int {
        var sent = 0
        for (saved in store.loadAll()) {
            when (send(saved)) {
                Step.DONE -> sent++
                Step.DROPPED -> Unit
                Step.STOP -> return sent
            }
        }
        return sent
    }

    private enum class Step { DONE, DROPPED, STOP }

    private suspend fun send(saved: PendingResponse): Step {
        val endpoints = QuestEndpoints(saved.base)
        val ua = saved.userAgent
        var current = saved
        var responseId = saved.responseId
        if (responseId == null) {
            when (val r = transport.call(QuestRequests.create(endpoints, ua, saved.createBody), retry, sleep)) {
                is CallOutcome.Ok -> {
                    responseId = QuestRequests.createdId(r.body)
                        ?: return drop(saved, Op.CREATE_RESPONSE, QaidQuestsError.InvalidResponse("no response id"))
                    current = current.copy(responseId = responseId)
                    store.save(current)
                }
                is CallOutcome.Refused -> return drop(saved, Op.CREATE_RESPONSE, r.error)
                else -> return Step.STOP
            }
        }
        while (current.patches.isNotEmpty()) {
            val patch = current.patches.first()
            val body = QuestRequests.patchBody(patch.questionId, patch.value)
            when (val r = transport.call(QuestRequests.patch(endpoints, responseId, ua, body), retry, sleep)) {
                is CallOutcome.Refused -> onRefused(QaidQuestsErrorEvent(saved.questId, Op.SAVE_ANSWER, patch.questionId, r.error))
                is CallOutcome.Ok -> Unit
                else -> return Step.STOP
            }
            current = current.copy(patches = current.patches.drop(1))
            store.save(current)
        }
        val submit = current.submitBody
        if (submit != null) {
            when (val r = transport.call(QuestRequests.submit(endpoints, responseId, ua, submit), retry, sleep)) {
                is CallOutcome.Refused -> return drop(current, Op.SUBMIT, r.error)
                is CallOutcome.Ok -> Unit
                else -> return Step.STOP
            }
        }
        store.delete(saved.id)
        return Step.DONE
    }

    private fun drop(saved: PendingResponse, operation: Op, error: QaidQuestsError): Step {
        store.delete(saved.id)
        onRefused(QaidQuestsErrorEvent(saved.questId, operation, error = error))
        return Step.DROPPED
    }
}
