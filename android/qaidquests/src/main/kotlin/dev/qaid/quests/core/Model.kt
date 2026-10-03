package dev.qaid.quests.core

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

enum class ProgressPosition { TOP, BOTTOM }

enum class TextInputType { TEXT, EMAIL, TEL, URL }

enum class ImageAlignment { HORIZONTAL, VERTICAL }

/** A quest's live version, as `GET {base}/{questId}/definition` serves it. */
data class Questionnaire(
    /** The quest's id, which the create call posts back as `questId`. */
    val id: String,
    val slug: String? = null,
    val versionId: String? = null,
    val versionNumber: Int? = null,
    val title: String? = null,
    val description: String? = null,
    val hideTitle: Boolean = false,
    val hideProgress: Boolean = false,
    val progressPosition: ProgressPosition? = null,
    val thankYouTitle: String? = null,
    val thankYouMessage: String? = null,
    val submitLabel: String? = null,
    val nextLabel: String? = null,
    val backLabel: String? = null,
    val questions: List<Question>,
)

/** What every question type shares. */
sealed interface Question {
    val id: String
    val label: String
    val description: String?
    val required: Boolean
    val visibleIf: VisibilityRule?
}

data class TextQuestion(
    override val id: String,
    override val label: String,
    override val description: String? = null,
    override val required: Boolean = false,
    override val visibleIf: VisibilityRule? = null,
    val placeholder: String? = null,
    val multiline: Boolean = false,
    /** A hard cap on typing, as HTML `maxlength`. */
    val maxLength: Int? = null,
    /** Only enforced when [required], as on the web. */
    val minLength: Int? = null,
    /** Keyboard only; formats are not validated (the web doesn't either). Ignored when [multiline]. */
    val inputType: TextInputType = TextInputType.TEXT,
) : Question

data class CurrencyQuestion(
    override val id: String,
    override val label: String,
    override val description: String? = null,
    override val required: Boolean = false,
    override val visibleIf: VisibilityRule? = null,
    val currency: String = "USD",
    /** Only picks the symbol; the device locale when null. */
    val locale: String? = null,
    val min: Double? = null,
    val max: Double? = null,
    val placeholder: String? = null,
) : Question

data class RangeQuestion(
    override val id: String,
    override val label: String,
    override val description: String? = null,
    override val required: Boolean = false,
    override val visibleIf: VisibilityRule? = null,
    val min: Double,
    val max: Double,
    val step: Double = 1.0,
    val defaultValue: Double? = null,
    val unit: String? = null,
) : Question

data class DateQuestion(
    override val id: String,
    override val label: String,
    override val description: String? = null,
    override val required: Boolean = false,
    override val visibleIf: VisibilityRule? = null,
    /** `YYYY-MM-DD`; bounds the picker only, as on the web. */
    val min: String? = null,
    val max: String? = null,
) : Question

data class ChoiceOption(
    val value: String,
    val label: String,
    val description: String? = null,
    val image: String? = null,
)

data class ChoiceQuestion(
    override val id: String,
    override val label: String,
    override val description: String? = null,
    override val required: Boolean = false,
    override val visibleIf: VisibilityRule? = null,
    val options: List<ChoiceOption>,
    val multiple: Boolean = false,
    val imageAlignment: ImageAlignment = ImageAlignment.HORIZONTAL,
) : Question {
    /** The alignment only applies when some option has an image. */
    val hasImages: Boolean get() = options.any { it.image != null }
}

/**
 * A question this SDK version can't draw: a type added to qaid after it shipped, or a known
 * type whose options are unusable. See [Unsupported].
 */
data class UnknownQuestion(
    override val id: String,
    override val label: String,
    override val description: String? = null,
    override val required: Boolean = false,
    override val visibleIf: VisibilityRule? = null,
    val type: String,
) : Question

/**
 * The one place that decides what happens to a question the SDK can't draw. Today: it is
 * shown as a card saying so, never blocks Next (not required), and its answer stays unset, so
 * nothing is sent for it. Change the policy here and every caller follows.
 */
object Unsupported {
    fun isUnsupported(question: Question): Boolean = question is UnknownQuestion
}

class QuestDecodeException(val detail: String) : Exception(detail)

/**
 * Lenient decoding: unknown fields are ignored, a question with no id is dropped, an unknown
 * type becomes [UnknownQuestion]. Only an unreadable body or no questions at all fail, with
 * the web embed's messages.
 */
object QuestDecoder {
    fun questionnaire(raw: String, fallbackId: String): Questionnaire {
        val obj = try {
            JSONObject(raw)
        } catch (_: JSONException) {
            throw QuestDecodeException("Invalid questionnaire")
        }
        val questions = (obj.opt("questions") as? JSONArray)?.let { array ->
            (0 until array.length()).mapNotNull { (array.opt(it) as? JSONObject)?.let(::question) }
        }.orEmpty()
        if (questions.isEmpty()) throw QuestDecodeException("Questionnaire is empty")
        return Questionnaire(
            id = obj.str("id") ?: fallbackId,
            slug = obj.str("slug"),
            versionId = obj.str("versionId"),
            versionNumber = obj.num("versionNumber")?.toInt(),
            title = obj.str("title"),
            description = obj.str("description"),
            hideTitle = obj.opt("hideTitle") == true,
            hideProgress = obj.opt("hideProgress") == true,
            progressPosition = progressPosition(obj.str("progressPosition")),
            thankYouTitle = obj.str("thankYouTitle"),
            thankYouMessage = obj.str("thankYouMessage"),
            submitLabel = obj.str("submitLabel"),
            nextLabel = obj.str("nextLabel"),
            backLabel = obj.str("backLabel"),
            questions = questions,
        )
    }

    fun progressPosition(raw: String?): ProgressPosition? = when (raw) {
        "top" -> ProgressPosition.TOP
        "bottom" -> ProgressPosition.BOTTOM
        else -> null
    }

    fun question(obj: JSONObject): Question? {
        val id = obj.str("id")?.takeIf { it.isNotBlank() } ?: return null
        val label = obj.str("label").orEmpty()
        val description = obj.str("description")
        val required = obj.opt("required") == true
        val rule = obj.opt("visibleIf")?.let(VisibilityRule::decode)
        val type = obj.str("type").orEmpty()
        fun unknown() = UnknownQuestion(id, label, description, required, rule, type)
        return when (type) {
            "text" -> TextQuestion(
                id, label, description, required, rule,
                placeholder = obj.str("placeholder"),
                multiline = obj.opt("multiline") == true,
                maxLength = obj.num("maxLength")?.takeIf { it > 0 }?.toInt(),
                minLength = obj.num("minLength")?.takeIf { it >= 0 }?.toInt(),
                inputType = when (obj.str("inputType")) {
                    "email" -> TextInputType.EMAIL
                    "tel" -> TextInputType.TEL
                    "url" -> TextInputType.URL
                    else -> TextInputType.TEXT
                },
            )
            "currency" -> CurrencyQuestion(
                id, label, description, required, rule,
                currency = obj.str("currency")?.takeIf { it.isNotBlank() } ?: "USD",
                locale = obj.str("locale")?.takeIf { it.isNotBlank() },
                min = obj.num("min"),
                max = obj.num("max"),
                placeholder = obj.str("placeholder"),
            )
            "range" -> {
                val min = obj.num("min")
                val max = obj.num("max")
                if (min == null || max == null || min >= max) return unknown()
                RangeQuestion(
                    id, label, description, required, rule,
                    min = min,
                    max = max,
                    step = obj.num("step")?.takeIf { it > 0 } ?: 1.0,
                    defaultValue = obj.num("defaultValue"),
                    unit = obj.str("unit"),
                )
            }
            "date" -> DateQuestion(id, label, description, required, rule, min = obj.str("min"), max = obj.str("max"))
            "multiple-choice" -> {
                val array = obj.opt("options") as? JSONArray ?: JSONArray()
                val options = (0 until array.length()).mapNotNull { i -> (array.opt(i) as? JSONObject)?.let(::option) }
                if (options.isEmpty()) return unknown()
                ChoiceQuestion(
                    id, label, description, required, rule,
                    options = options,
                    multiple = obj.opt("multiple") == true,
                    imageAlignment = if (obj.str("imageAlignment") == "vertical") ImageAlignment.VERTICAL else ImageAlignment.HORIZONTAL,
                )
            }
            else -> unknown()
        }
    }

    private fun option(obj: JSONObject): ChoiceOption? {
        val value = obj.str("value")?.takeIf { it.isNotBlank() } ?: return null
        return ChoiceOption(
            value = value,
            label = obj.str("label") ?: value,
            description = obj.str("description"),
            image = obj.str("image")?.takeIf { it.isNotBlank() },
        )
    }
}

/** A string field, or null when missing or not a string. */
internal fun JSONObject.str(key: String): String? = opt(key) as? String

/** A number field, or null. (Always finite: org.json reads NaN and Infinity as strings and refuses to hold them.) */
internal fun JSONObject.num(key: String): Double? = (opt(key) as? Number)?.toDouble()
