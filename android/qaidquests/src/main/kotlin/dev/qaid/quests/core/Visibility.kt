package dev.qaid.quests.core

import org.json.JSONArray
import org.json.JSONObject

/** A `visibleIf` value: a string or a number. Anything else never matches an answer. */
internal sealed interface RuleValue {
    data class Str(val value: String) : RuleValue
    data class Num(val value: Double) : RuleValue
    data object Other : RuleValue

    companion object {
        fun of(raw: Any?): RuleValue = when (raw) {
            is String -> Str(raw)
            is Number -> Num(raw.toDouble())
            else -> Other
        }
    }
}

/**
 * A `visibleIf` predicate, read the way `quests-embed`'s visibility.ts reads it: `allOf`, then
 * `anyOf`, then `answered`, `equals`, `notEquals`, `in`. A rule with none of those is [Never].
 */
internal sealed interface VisibilityRule {
    data class AllOf(val rules: List<VisibilityRule>) : VisibilityRule
    data class AnyOf(val rules: List<VisibilityRule>) : VisibilityRule
    data class Answered(val questionId: String?, val answered: Boolean) : VisibilityRule
    data class Equals(val questionId: String?, val value: RuleValue) : VisibilityRule
    data class NotEquals(val questionId: String?, val value: RuleValue) : VisibilityRule
    data class In(val questionId: String?, val values: List<RuleValue>) : VisibilityRule
    data object Never : VisibilityRule

    companion object {
        fun decode(raw: Any?): VisibilityRule {
            val obj = raw as? JSONObject ?: return Never
            val id = obj.opt("questionId") as? String
            return when {
                obj.has("allOf") -> AllOf(list(obj.opt("allOf")).map(::decode))
                obj.has("anyOf") -> AnyOf(list(obj.opt("anyOf")).map(::decode))
                obj.has("answered") -> Answered(id, truthy(obj.opt("answered")))
                obj.has("equals") -> Equals(id, RuleValue.of(obj.opt("equals")))
                obj.has("notEquals") -> NotEquals(id, RuleValue.of(obj.opt("notEquals")))
                obj.has("in") -> In(id, list(obj.opt("in")).map(RuleValue::of))
                else -> Never
            }
        }

        /** A JSON array's items; anything that isn't an array reads as empty. */
        private fun list(raw: Any?): List<Any?> =
            (raw as? JSONArray)?.let { a -> (0 until a.length()).map { a.opt(it) } }.orEmpty()

        /** JavaScript truthiness, which is what `rule.answered ? … : …` tests. */
        internal fun truthy(raw: Any?): Boolean = when (raw) {
            is Boolean -> raw
            is Number -> raw.toDouble().let { it != 0.0 && !it.isNaN() }
            is String -> raw.isNotEmpty()
            null, JSONObject.NULL -> false
            else -> true
        }
    }
}

/**
 * Which questions are visible: `quests-embed`'s visibility.ts, rule for rule. Pure; never throws.
 */
internal object Visibility {
    /**
     * Walks the questions in order. A rule only sees the answers of questions already
     * resolved visible, so a hidden question reads as unanswered whatever was typed into it,
     * and a chain of follow-ups collapses behind it.
     */
    fun visibleQuestions(questions: List<Question>, answers: Map<String, QuestAnswer>): List<Question> {
        val seen = HashMap<String, QuestAnswer>()
        val visible = ArrayList<Question>()
        for (q in questions) {
            val rule = q.visibleIf
            if (rule != null && !evaluate(rule, seen)) continue
            visible += q
            answers[q.id]?.let { seen[q.id] = it }
        }
        return visible
    }

    /** The answers of currently visible questions — what is submitted and handed to onComplete. */
    fun visibleAnswers(questions: List<Question>, answers: Map<String, QuestAnswer>): Map<String, QuestAnswer> {
        val out = LinkedHashMap<String, QuestAnswer>()
        for (q in visibleQuestions(questions, answers)) answers[q.id]?.let { out[q.id] = it }
        return out
    }

    fun evaluate(rule: VisibilityRule, answers: Map<String, QuestAnswer>): Boolean {
        val questionId = when (rule) {
            is VisibilityRule.AllOf -> return rule.rules.all { evaluate(it, answers) }
            is VisibilityRule.AnyOf -> return rule.rules.any { evaluate(it, answers) }
            VisibilityRule.Never -> return false
            is VisibilityRule.Answered -> rule.questionId
            is VisibilityRule.Equals -> rule.questionId
            is VisibilityRule.NotEquals -> rule.questionId
            is VisibilityRule.In -> rule.questionId
        }
        val answer = questionId?.let { answers[it] } ?: QuestAnswer.None
        val answered = answer.isAnswered
        if (rule is VisibilityRule.Answered) return rule.answered == answered
        // A comparison against an unanswered question is false — notEquals included, so a
        // "show when X is not Y" follow-up doesn't appear before X is answered at all.
        if (!answered) return false
        return when (rule) {
            is VisibilityRule.Equals -> matches(answer, rule.value)
            is VisibilityRule.NotEquals -> !matches(answer, rule.value)
            else -> (rule as VisibilityRule.In).values.any { matches(answer, it) }
        }
    }

    /**
     * JavaScript `===`: a string only equals a string, a number only a number (no coercion,
     * no trimming). A multi-select matches when any picked value does.
     */
    fun matches(answer: QuestAnswer, target: RuleValue): Boolean = when (answer) {
        is QuestAnswer.Choices -> answer.values.any { target is RuleValue.Str && target.value == it }
        is QuestAnswer.Text -> target is RuleValue.Str && target.value == answer.value
        // IEEE comparison of non-null Doubles: 0.0 == -0.0 and NaN never matches, as in JavaScript.
        is QuestAnswer.Number -> target is RuleValue.Num && target.value == answer.value
        QuestAnswer.None -> false
    }
}
