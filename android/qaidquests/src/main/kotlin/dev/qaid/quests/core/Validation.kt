package dev.qaid.quests.core

import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.util.Currency
import java.util.Locale
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * The web embed's client-side checks and their exact messages, run on Next only. Formats
 * (email, url, tel), date bounds and currency decimals are not checked, as on the web.
 */
object QuestValidation {
    /** The error to show, or null when [value] may go on. */
    fun message(question: Question, value: QuestAnswer, text: QaidQuestText): String? = when (question) {
        is TextQuestion -> {
            val v = (value as? QuestAnswer.Text)?.value.orEmpty().trim()
            val min = question.minLength ?: 0
            when {
                !question.required || (v.isNotEmpty() && v.length >= min) -> null
                // The web shows the length message even for an empty field when a minimum is set.
                min > 0 -> text.fill(text.errorMinLength, min)
                else -> text.errorRequired
            }
        }
        is CurrencyQuestion -> {
            val v = (value as? QuestAnswer.Number)?.value
            val ok = if (v == null) {
                !question.required
            } else {
                (question.min == null || v >= question.min) && (question.max == null || v <= question.max)
            }
            if (ok) null else text.errorAmount
        }
        is DateQuestion -> if (question.required && !value.isAnswered) text.errorDate else null
        is ChoiceQuestion -> when {
            !question.required || value.isAnswered -> null
            question.multiple -> text.errorOptions
            else -> text.errorOption
        }
        // A range always holds a value; an unsupported question never blocks.
        is RangeQuestion, is UnknownQuestion -> null
    }
}

/** A text field's typing rules. */
object TextRules {
    /** HTML `maxlength`: typing stops at the cap. */
    fun clip(value: String, maxLength: Int?): String =
        if (maxLength != null && value.length > maxLength) value.take(maxLength) else value
}

/** A currency field: what is typed → the number sent, and the symbol shown before it. */
object CurrencyRules {
    /**
     * `""` → null; a finite number → itself; anything else → null, as `Number(input.value)`
     * on an `<input type=number>`. A comma counts as the decimal point when there is no dot,
     * for keyboards that offer only a comma.
     */
    fun parse(raw: String): Double? {
        val trimmed = raw.trim()
        val normalized = if ('.' !in trimmed) trimmed.replace(',', '.') else trimmed
        return normalized.toDoubleOrNull()?.takeIf { it.isFinite() }
    }

    /** Keeps what a decimal field may hold: digits, one separator, a leading minus. */
    fun sanitize(raw: String): String {
        val out = StringBuilder()
        var separator = false
        for ((i, c) in raw.withIndex()) {
            when {
                c.isDigit() -> out.append(c)
                (c == '.' || c == ',') && !separator -> { separator = true; out.append(c) }
                c == '-' && i == 0 -> out.append(c)
            }
        }
        return out.toString()
    }

    /** Narrow symbols (`$`, not `US$`), as the web's `currencyDisplay: "narrowSymbol"`. */
    private val NARROW = mapOf(
        "USD" to "$", "AUD" to "$", "CAD" to "$", "NZD" to "$", "MXN" to "$", "SGD" to "$", "HKD" to "$",
        "EUR" to "€", "GBP" to "£", "JPY" to "¥", "CNY" to "¥", "INR" to "₹", "KRW" to "₩", "BRL" to "R$",
        "ILS" to "₪", "NGN" to "₦", "PHP" to "₱", "PLN" to "zł", "RUB" to "₽", "TRY" to "₺", "UAH" to "₴",
        "VND" to "₫", "THB" to "฿", "ZAR" to "R",
    )

    /** The symbol for [code]: a narrow one when known, else the locale's, else the code. */
    fun symbol(code: String, locale: Locale): String {
        val upper = code.uppercase(Locale.ROOT)
        NARROW[upper]?.let { return it }
        return runCatching { Currency.getInstance(upper).getSymbol(locale) }.getOrNull() ?: upper
    }

    /** A host locale tag ("de-DE"), or [fallback] when there is none. */
    fun locale(tag: String?, fallback: Locale): Locale {
        if (tag.isNullOrBlank()) return fallback
        return Locale.forLanguageTag(tag).takeIf { it.language.isNotEmpty() } ?: fallback
    }

    /** How a stored number is shown back in the field: `12.5`, `12`. */
    fun display(value: Double?): String = value?.let(NumberText::format).orEmpty()
}

/** A range's slider maths, matching how a browser's `<input type=range>` snaps. */
object RangeRules {
    /** The starting value: [RangeQuestion.defaultValue] or the minimum, snapped to the grid. */
    fun initial(q: RangeQuestion): Double = snap(q, q.defaultValue ?: q.min)

    /** Nearest `min + k·step` inside [min, max]; ties round up, as browsers do. */
    fun snap(q: RangeQuestion, value: Double): Double {
        val maxSteps = floor((q.max - q.min) / q.step + 1e-9)
        val steps = floor((value - q.min) / q.step + 0.5).coerceIn(0.0, maxSteps)
        return clean(q.min + steps * q.step)
    }

    /** Discrete stops between the ends for a Compose Slider, or 0 for a continuous one. */
    fun sliderSteps(q: RangeQuestion): Int {
        val count = (q.max - q.min) / q.step
        val whole = count.roundToLong()
        // count > 0 always (min < max, step > 0), so a whole count is at least 1.
        return if (kotlin.math.abs(count - whole) < 1e-9 && whole <= 1000) (whole - 1).toInt() else 0
    }

    /** Drops float noise (0.1 + 0.2) that would otherwise show in the value label. */
    private fun clean(value: Double): Double = BigDecimal(value).setScale(9, java.math.RoundingMode.HALF_UP).toDouble()
}

/** Numbers as JavaScript prints them: `5`, `2.5`, `-0.25`. */
object NumberText {
    fun format(value: Double): String =
        if (value == floor(value) && kotlin.math.abs(value) < 1e15) {
            value.toLong().toString()
        } else {
            BigDecimal(value.toString()).stripTrailingZeros().toPlainString()
        }
}

/** A date answer's wire form, `YYYY-MM-DD`, and the UTC midnight a Material date picker uses. */
object DateRules {
    fun parse(value: String?): LocalDate? = value?.let {
        try {
            LocalDate.parse(it)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    fun toUtcMillis(date: LocalDate): Long = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    fun fromUtcMillis(millis: Long): String = dayOf(millis).toString()

    private fun dayOf(millis: Long): LocalDate = java.time.Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()

    /** Whether the picker may offer [utcMillis] for [question]'s bounds. */
    fun selectable(question: DateQuestion, utcMillis: Long): Boolean {
        val day = dayOf(utcMillis)
        val min = parse(question.min)
        val max = parse(question.max)
        return (min == null || !day.isBefore(min)) && (max == null || !day.isAfter(max))
    }
}
