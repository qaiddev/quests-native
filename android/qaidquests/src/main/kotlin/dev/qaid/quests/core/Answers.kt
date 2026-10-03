package dev.qaid.quests.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * One answer, in the shapes the server accepts: text (also a date as `YYYY-MM-DD` and a
 * single choice's option value), a number (currency and range), a multi-select's values in
 * the order they were picked, or [None] (JSON `null`).
 */
sealed interface QuestAnswer {
    data class Text(val value: String) : QuestAnswer
    data class Number(val value: Double) : QuestAnswer
    data class Choices(val values: List<String>) : QuestAnswer
    data object None : QuestAnswer

    /**
     * The web embed's "answered", which visibleIf reads: not null, not whitespace-only text,
     * not an empty list. Zero is answered.
     */
    val isAnswered: Boolean
        get() = when (this) {
            is Text -> value.isNotBlank()
            is Number -> true
            is Choices -> values.isNotEmpty()
            else -> false
        }

    /** The value as it goes in a JSON body. */
    fun toJson(): Any = when (this) {
        is Text -> value
        is Number -> value
        is Choices -> JSONArray(values)
        else -> JSONObject.NULL
    }

    companion object {
        /** Back from a JSON body (the offline queue). Anything else reads as [None]. */
        fun fromJson(value: Any?): QuestAnswer = when (value) {
            is String -> Text(value)
            is kotlin.Number -> Number(value.toDouble())
            is JSONArray -> Choices((0 until value.length()).map { value.optString(it) })
            else -> None
        }
    }
}

/** `{ questionId: value }` as the submit body and `onComplete` carry it. */
internal fun answersToJson(answers: Map<String, QuestAnswer>): JSONObject =
    JSONObject().apply { answers.forEach { (id, value) -> put(id, value.toJson()) } }
