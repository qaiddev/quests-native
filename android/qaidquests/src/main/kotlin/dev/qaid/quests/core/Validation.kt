package dev.qaid.quests.core

import java.math.BigDecimal
import java.text.DecimalFormatSymbols
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
internal object QuestValidation {
    /** The error to show, or null when [value] may go on. */
    fun message(question: Question, value: QuestAnswer, text: QaidQuestText): String? = when (question) {
        is TextQuestion -> {
            // JavaScript's trim and UTF-16 length, as the web embed measures.
            val v = JsText.trim((value as? QuestAnswer.Text)?.value.orEmpty())
            val min = question.minLength ?: 0
            when {
                !question.required || (v.isNotEmpty() && v.length >= min) -> null
                // The web shows the length message even for an empty field when a minimum is set.
                min > 0 -> text.minLengthMessage(min)
                else -> text.validationRequired
            }
        }
        is CurrencyQuestion -> {
            val v = (value as? QuestAnswer.Number)?.value
            val ok = if (v == null) {
                !question.required
            } else {
                (question.min == null || v >= question.min) && (question.max == null || v <= question.max)
            }
            if (ok) null else text.validationAmount
        }
        is DateQuestion -> if (question.required && !value.isAnswered) text.validationDate else null
        is ChoiceQuestion -> when {
            !question.required || value.isAnswered -> null
            question.multiple -> text.validationSelectMany
            else -> text.validationSelectOne
        }
        // A range always holds a value; an unsupported question never blocks.
        is RangeQuestion, is UnknownQuestion -> null
    }
}

/**
 * JavaScript's whitespace (what `trim()` strips), which the web embed's `required` check and
 * visibleIf's "answered" use. Kotlin's own set differs: it lacks U+FEFF and adds U+001C–U+001F.
 * iOS uses the same set.
 */
internal object JsText {
    private const val SPACES = "\t\n\u000B\u000C\r \u00A0\u1680\u2000\u2001\u2002\u2003\u2004\u2005\u2006" +
        "\u2007\u2008\u2009\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF"

    fun isSpace(c: Char): Boolean = SPACES.indexOf(c) >= 0

    fun trim(value: String): String = value.trim(::isSpace)

    fun isBlank(value: String): Boolean = value.all(::isSpace)
}

/** A text field's typing rules. */
internal object TextRules {
    /**
     * HTML `maxlength`, in UTF-16 units as the web counts: typing stops at the cap. An emoji
     * that would straddle it is left out whole rather than cut in half.
     */
    fun clip(value: String, maxLength: Int?): String {
        if (maxLength == null || value.length <= maxLength) return value
        val cut = value.take(maxLength)
        return if (cut.last().isHighSurrogate()) cut.dropLast(1) else cut
    }
}

/** A currency field: what is typed → the number sent, and the symbol shown before it. */
internal object CurrencyRules {
    /**
     * `""` → null; a finite number → itself; anything else → null, as `Number(input.value)`
     * on an `<input type=number>`. The locale's decimal separator and `.` are both read as the
     * decimal point; a grouping separator ("1,000" in English) makes it unreadable, so null is
     * sent, as on iOS and the web.
     */
    fun parse(raw: String, decimalSeparator: Char): Double? {
        val trimmed = JsText.trim(raw)
        val normalized = if (decimalSeparator != '.') trimmed.replace(decimalSeparator, '.') else trimmed
        // Only plain decimals: the JVM would also read "1d", "0x1p3" and "Infinity".
        if (!DECIMAL.matches(normalized)) return null
        return normalized.toDoubleOrNull()?.takeIf { it.isFinite() }
    }

    private val DECIMAL = Regex("-?(\\d+\\.?\\d*|\\.\\d+)")

    /** The decimal separator the keyboard offers in [locale]. */
    fun decimalSeparator(locale: Locale): Char = DecimalFormatSymbols.getInstance(locale).decimalSeparator

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
internal object RangeRules {
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
internal object NumberText {
    fun format(value: Double): String =
        if (value == floor(value) && kotlin.math.abs(value) < 1e15) {
            value.toLong().toString()
        } else {
            BigDecimal(value.toString()).stripTrailingZeros().toPlainString()
        }
}

/** A date answer's wire form, `YYYY-MM-DD`, and the UTC midnight a Material date picker uses. */
internal object DateRules {
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
