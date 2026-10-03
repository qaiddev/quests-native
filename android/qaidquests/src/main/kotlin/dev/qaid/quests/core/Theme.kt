package dev.qaid.quests.core

import org.json.JSONException
import org.json.JSONObject
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * A saved quest theme, as `GET /api/quest-themes/{id}/public` serves it. Its free `css` and
 * `unstyled` have no native meaning (there is no stylesheet to add to or strip) and are
 * ignored; [ignoredCss] and [unstyled] are kept only so the SDK can say so in its log.
 */
internal data class QuestThemeDoc(
    val preset: String? = null,
    val mode: QaidQuestTheme.Mode? = null,
    val tokens: Map<String, String> = emptyMap(),
    val ignoredCss: Boolean = false,
    val unstyled: Boolean = false,
) {
    companion object {
        fun decode(raw: String): QuestThemeDoc {
            val obj = try {
                JSONObject(raw)
            } catch (_: JSONException) {
                throw QuestDecodeException("Invalid theme")
            }
            val tokens = LinkedHashMap<String, String>()
            (obj.opt("tokens") as? JSONObject)?.let { t ->
                t.keys().forEach { key -> (t.opt(key) as? String)?.let { tokens[key] = it } }
            }
            return QuestThemeDoc(
                preset = obj.str("preset"),
                mode = mode(obj.str("mode")),
                tokens = tokens,
                ignoredCss = !obj.str("css").isNullOrBlank(),
                unstyled = obj.opt("unstyled") == true,
            )
        }

        fun mode(raw: String?): QaidQuestTheme.Mode? = when (raw) {
            "light" -> QaidQuestTheme.Mode.LIGHT
            "dark" -> QaidQuestTheme.Mode.DARK
            "auto" -> QaidQuestTheme.Mode.AUTO
            else -> null
        }
    }
}

/** A corner radius in dp, or a percentage of the shape's size (`50%` = round). */
internal data class CssRadius(val value: Float, val isPercent: Boolean = false)

/** Padding in dp. */
internal data class Insets(val vertical: Float, val horizontal: Float)

/** One mode's colours, ARGB. */
internal data class QuestColors(
    val accent: Int,
    val accentText: Int,
    val error: Int,
    val focus: Int,
    val cardBg: Int,
    val cardFg: Int,
    val cardBorder: Int,
    val mutedFg: Int,
    val progressTrack: Int,
    val primaryBg: Int,
    val primaryFg: Int,
    val primaryBorder: Int,
    val secondaryBg: Int,
    val secondaryFg: Int,
    val secondaryBorder: Int,
    val optionBg: Int,
    val optionBgSelected: Int,
    val optionFg: Int,
    val optionBorder: Int,
    val optionBorderSelected: Int,
    val markerBg: Int,
    val markerBgSelected: Int,
    val markerBorder: Int,
    val markerIcon: Int,
    val inputBg: Int,
    val inputFg: Int,
    val inputBorder: Int,
    val inputPlaceholder: Int,
)

/** Sizes in dp, and the base text size in sp that every `em` size scales from. */
internal data class QuestMetrics(
    val fontSize: Float,
    val cardRadius: CssRadius,
    val cardPadding: Float,
    val cardShadow: Boolean,
    val buttonRadius: CssRadius,
    val buttonPadding: Insets,
    val buttonFontWeight: Int,
    val optionRadius: CssRadius,
    val optionPadding: Insets,
    val markerRadius: CssRadius,
    val inputRadius: CssRadius,
    val inputPadding: Insets,
    val gap: Float,
    val sectionGap: Float,
)

/** The resolved look: both modes' colours, so `auto` can follow the system as it changes. */
internal data class QuestStyle(
    val mode: QaidQuestTheme.Mode,
    val light: QuestColors,
    val dark: QuestColors,
    val metrics: QuestMetrics,
    /** CSS family names in order; the UI uses the first one it can load. */
    val fontFamilies: List<String>,
) {
    fun isDark(systemDark: Boolean): Boolean = when (mode) {
        QaidQuestTheme.Mode.LIGHT -> false
        QaidQuestTheme.Mode.DARK -> true
        QaidQuestTheme.Mode.AUTO -> systemDark
    }

    fun colors(systemDark: Boolean): QuestColors = if (isDark(systemDark)) dark else light
}

/**
 * Theme document + host overrides → [QuestStyle], layered as the web embed layers CSS:
 * qaid's defaults, then the theme's tokens, then the preset's (a preset beats a theme token
 * for the same key, as on the web), then the host's own values. `var()` and `light-dark()`
 * resolve per mode, so a token derived from the accent follows a new accent.
 */
internal object QuestStyles {
    /** `styles-shadow.css`'s defaults (auto mode). */
    val DEFAULT_TOKENS: Map<String, String> = mapOf(
        "--qaid-q-card-bg" to "light-dark(#ffffff, #1f2937)",
        "--qaid-q-card-fg" to "light-dark(#1f2937, #f9fafb)",
        "--qaid-q-card-border" to "light-dark(#e5e7eb, #374151)",
        "--qaid-q-card-radius" to "1rem",
        "--qaid-q-card-padding" to "1.5rem",
        "--qaid-q-card-shadow" to "0 25px 50px -12px light-dark(rgba(0, 0, 0, 0.25), rgba(0, 0, 0, 0.6))",
        "--qaid-q-muted-fg" to "light-dark(#6b7280, #9ca3af)",
        "--qaid-q-progress-track" to "light-dark(#e5e7eb, #374151)",
        "--qaid-q-btn-radius" to "0.5rem",
        "--qaid-q-btn-padding-y" to "0.625rem",
        "--qaid-q-btn-padding-x" to "1.125rem",
        "--qaid-q-btn-font-weight" to "500",
        "--qaid-q-btn-primary-bg" to "var(--qaid-q-accent, #10b981)",
        "--qaid-q-btn-primary-fg" to "var(--qaid-q-accent-text, #ffffff)",
        "--qaid-q-btn-primary-border" to "transparent",
        "--qaid-q-btn-secondary-bg" to "transparent",
        "--qaid-q-btn-secondary-fg" to "light-dark(#374151, #d1d5db)",
        "--qaid-q-btn-secondary-border" to "light-dark(#d1d5db, #4b5563)",
        "--qaid-q-option-bg" to "light-dark(#ffffff, #111827)",
        "--qaid-q-option-bg-selected" to "color-mix(in srgb, var(--qaid-q-accent, #10b981) 10%, transparent)",
        "--qaid-q-option-fg" to "light-dark(#1f2937, #f9fafb)",
        "--qaid-q-option-border" to "light-dark(#e5e7eb, #374151)",
        "--qaid-q-option-border-selected" to "var(--qaid-q-accent, #10b981)",
        "--qaid-q-option-radius" to "0.625rem",
        "--qaid-q-option-padding" to "0.75rem 0.875rem",
        "--qaid-q-marker-bg" to "light-dark(#ffffff, #111827)",
        "--qaid-q-marker-bg-selected" to "var(--qaid-q-accent, #10b981)",
        "--qaid-q-marker-border" to "light-dark(#d1d5db, #4b5563)",
        "--qaid-q-marker-icon" to "light-dark(#ffffff, #1f2937)",
        "--qaid-q-marker-radius" to "50%",
        "--qaid-q-input-bg" to "light-dark(#ffffff, #111827)",
        "--qaid-q-input-fg" to "light-dark(#1f2937, #f9fafb)",
        "--qaid-q-input-border" to "light-dark(#d1d5db, #374151)",
        "--qaid-q-input-radius" to "0.625rem",
        "--qaid-q-input-padding" to "0.75rem 0.875rem",
        "--qaid-q-input-placeholder" to "light-dark(#9ca3af, #6b7280)",
        "--qaid-q-gap" to "0.75rem",
        "--qaid-q-section-gap" to "1.25rem",
        "--qaid-font-family" to "system-ui, -apple-system, sans-serif",
        "--qaid-font-size" to "16px",
    )

    /** The embed's preset stylesheets: each overrides only these tokens. */
    val PRESETS: Map<String, Map<String, String>> = mapOf(
        "default" to emptyMap(),
        "minimal" to mapOf(
            "--qaid-q-card-radius" to "0",
            "--qaid-q-card-shadow" to "none",
            "--qaid-q-btn-radius" to "0",
            "--qaid-q-option-radius" to "0",
            "--qaid-q-option-bg-selected" to "transparent",
            "--qaid-q-input-radius" to "0",
        ),
        "pill" to mapOf(
            "--qaid-q-card-radius" to "1.5rem",
            "--qaid-q-btn-radius" to "9999px",
            "--qaid-q-btn-padding-x" to "1.5rem",
            "--qaid-q-option-radius" to "9999px",
            "--qaid-q-option-padding" to "0.75rem 1.125rem",
            "--qaid-q-input-radius" to "9999px",
            "--qaid-q-input-padding" to "0.75rem 1.125rem",
        ),
        "dense" to mapOf(
            "--qaid-q-card-padding" to "1rem",
            "--qaid-q-btn-padding-y" to "0.5rem",
            "--qaid-q-btn-padding-x" to "0.875rem",
            "--qaid-q-option-padding" to "0.5rem 0.625rem",
            "--qaid-q-input-padding" to "0.5rem 0.625rem",
            "--qaid-q-gap" to "0.5rem",
            "--qaid-q-section-gap" to "0.875rem",
        ),
    )

    val DEFAULT: QuestStyle = build(null, null)

    fun build(doc: QuestThemeDoc?, host: QaidQuestTheme?): QuestStyle {
        val tokens = HashMap(DEFAULT_TOKENS)
        doc?.tokens.orEmpty().forEach { (key, value) -> if (value.isNotBlank()) tokens[key] = value }
        PRESETS[host?.preset?.cssName ?: doc?.preset ?: "default"]?.let(tokens::putAll)
        if (host != null) applyHost(tokens, host)
        val light = Resolver(tokens, dark = false)
        return QuestStyle(
            mode = host?.mode ?: doc?.mode ?: QaidQuestTheme.Mode.AUTO,
            light = light.colors(),
            dark = Resolver(tokens, dark = true).colors(),
            metrics = light.metrics(),
            fontFamilies = Css.fontFamilies(light.raw("var(--qaid-font-family)")),
        )
    }

    /** The host's values, as the web embed applies its config: inline, over everything. */
    private fun applyHost(tokens: MutableMap<String, String>, host: QaidQuestTheme) {
        host.accent?.let {
            tokens["--qaid-q-accent"] = Css.hex(it)
            tokens["--qaid-q-accent-text"] = Css.hex(Css.contrastText(it))
        }
        host.error?.let { tokens["--qaid-q-error"] = Css.hex(it) }
        host.focus?.let { tokens["--qaid-q-focus"] = Css.hex(it) }
        host.fontFamily?.let { tokens["--qaid-font-family"] = it }
        host.fontSize?.let { tokens["--qaid-font-size"] = "${it}px" }
        host.cornerRadius?.let { r ->
            listOf("card", "btn", "option", "input").forEach { tokens["--qaid-q-$it-radius"] = "${r}px" }
        }
    }

    private class Resolver(val tokens: Map<String, String>, val dark: Boolean) {
        val fontSize: Float = read("var(--qaid-font-size)") { Css.length(it, 16f) }

        fun raw(expr: String): String = Css.resolve(expr, tokens, dark)

        /** The theme's value, or qaid's default when the theme's can't be read. */
        fun <T : Any> read(expr: String, parse: (String) -> T?): T =
            parse(raw(expr)) ?: checkNotNull(parse(Css.resolve(expr, DEFAULT_TOKENS, dark))) { expr }

        fun color(token: String, fallback: String? = null): Int =
            read(if (fallback == null) "var($token)" else "var($token, $fallback)", Css::color)

        fun length(token: String): Float = read("var($token)") { Css.length(it, fontSize) }
        fun radius(token: String): CssRadius = read("var($token)") { Css.radius(it, fontSize) }
        fun insets(token: String): Insets = read("var($token)") { Css.padding(it, fontSize) }

        fun colors() = QuestColors(
            accent = color("--qaid-q-accent", "#10b981"),
            accentText = color("--qaid-q-accent-text", "#ffffff"),
            error = color("--qaid-q-error", "#ef4444"),
            focus = color("--qaid-q-focus", "#6366f1"),
            cardBg = color("--qaid-q-card-bg"),
            cardFg = color("--qaid-q-card-fg"),
            cardBorder = color("--qaid-q-card-border"),
            mutedFg = color("--qaid-q-muted-fg"),
            progressTrack = color("--qaid-q-progress-track"),
            primaryBg = color("--qaid-q-btn-primary-bg"),
            primaryFg = color("--qaid-q-btn-primary-fg"),
            primaryBorder = color("--qaid-q-btn-primary-border"),
            secondaryBg = color("--qaid-q-btn-secondary-bg"),
            secondaryFg = color("--qaid-q-btn-secondary-fg"),
            secondaryBorder = color("--qaid-q-btn-secondary-border"),
            optionBg = color("--qaid-q-option-bg"),
            optionBgSelected = color("--qaid-q-option-bg-selected"),
            optionFg = color("--qaid-q-option-fg"),
            optionBorder = color("--qaid-q-option-border"),
            optionBorderSelected = color("--qaid-q-option-border-selected"),
            markerBg = color("--qaid-q-marker-bg"),
            markerBgSelected = color("--qaid-q-marker-bg-selected"),
            markerBorder = color("--qaid-q-marker-border"),
            markerIcon = color("--qaid-q-marker-icon"),
            inputBg = color("--qaid-q-input-bg"),
            inputFg = color("--qaid-q-input-fg"),
            inputBorder = color("--qaid-q-input-border"),
            inputPlaceholder = color("--qaid-q-input-placeholder"),
        )

        fun metrics() = QuestMetrics(
            fontSize = fontSize,
            cardRadius = radius("--qaid-q-card-radius"),
            cardPadding = length("--qaid-q-card-padding"),
            cardShadow = Css.hasShadow(raw("var(--qaid-q-card-shadow)")),
            buttonRadius = radius("--qaid-q-btn-radius"),
            buttonPadding = Insets(length("--qaid-q-btn-padding-y"), length("--qaid-q-btn-padding-x")),
            buttonFontWeight = read("var(--qaid-q-btn-font-weight)", Css::fontWeight),
            optionRadius = radius("--qaid-q-option-radius"),
            optionPadding = insets("--qaid-q-option-padding"),
            markerRadius = radius("--qaid-q-marker-radius"),
            inputRadius = radius("--qaid-q-input-radius"),
            inputPadding = insets("--qaid-q-input-padding"),
            gap = length("--qaid-q-gap"),
            sectionGap = length("--qaid-q-section-gap"),
        )
    }
}

/**
 * Just enough CSS to read qaid's theme tokens: `var()`, `light-dark()`, hex / `rgb()` /
 * `color-mix(in srgb, …)` colours, px / rem / em lengths, padding shorthands, font lists.
 */
internal object Css {
    private const val MAX_DEPTH = 16
    private const val MAX_STEPS = 64
    private const val ROOT_PX = 16f
    private val FUNCTION = Regex("""(?<![\w-])(var|light-dark)\(""")
    private val WS = Regex("""\s+""")
    private val LENGTH = Regex("""^(-?\d*\.?\d+)(px|rem|em)$""")
    private val NAMED = mapOf(
        "transparent" to 0x00000000,
        "black" to 0xFF000000.toInt(),
        "white" to 0xFFFFFFFF.toInt(),
        "red" to 0xFFFF0000.toInt(),
        "green" to 0xFF008000.toInt(),
        "blue" to 0xFF0000FF.toInt(),
        "gray" to 0xFF808080.toInt(),
        "grey" to 0xFF808080.toInt(),
    )

    /** Splits on commas outside parentheses. */
    fun splitArgs(value: String): List<String> {
        val out = ArrayList<String>()
        val current = StringBuilder()
        var depth = 0
        for (c in value) {
            if (c == ',' && depth == 0) {
                out += current.toString().trim()
                current.clear()
                continue
            }
            if (c == '(') depth++
            if (c == ')') depth--
            current.append(c)
        }
        out += current.toString().trim()
        return out
    }

    /** Index of the `)` closing the `(` at [open], or null when it never closes. */
    fun closingParen(value: String, open: Int): Int? {
        var depth = 0
        for (i in open until value.length) {
            if (value[i] == '(') depth++
            if (value[i] == ')' && --depth == 0) return i
        }
        return null
    }

    /**
     * Every `var(--x, fallback)` and `light-dark(a, b)` in [value] replaced: a blank or
     * missing token takes the fallback (or nothing); a cycle resolves to nothing.
     */
    fun resolve(value: String, tokens: Map<String, String>, dark: Boolean, depth: Int = 0): String {
        var s = value
        repeat(MAX_STEPS) {
            val match = FUNCTION.find(s) ?: return s.trim()
            val open = match.range.last
            val close = closingParen(s, open) ?: return s.trim()
            val args = splitArgs(s.substring(open + 1, close))
            val replacement = when {
                depth >= MAX_DEPTH -> ""
                match.groupValues[1] == "light-dark" -> resolve(args[if (dark && args.size > 1) 1 else 0], tokens, dark, depth + 1)
                else -> {
                    val token = tokens[args[0]]?.takeIf { it.isNotBlank() }
                    val next = token ?: args.drop(1).joinToString(", ")
                    resolve(next, tokens, dark, depth + 1)
                }
            }
            s = s.substring(0, match.range.first) + replacement + s.substring(close + 1)
        }
        return s.trim()
    }

    fun color(value: String): Int? {
        val s = value.trim().lowercase()
        return when {
            s.startsWith("#") -> hexColor(s.substring(1))
            s.startsWith("rgb(") || s.startsWith("rgba(") -> rgb(s)
            s.startsWith("color-mix(") -> mix(s)
            else -> NAMED[s]
        }
    }

    /** `#rrggbbaa`. */
    fun hex(argb: Int): String = "#%06x%02x".format(argb and 0xFFFFFF, (argb ushr 24) and 0xFF)

    /** The web's auto text colour: black on a light background, white on a dark one. */
    fun contrastText(argb: Int): Int = if (luminance(argb) > 0.4) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()

    /** WCAG relative luminance. */
    fun luminance(argb: Int): Double {
        fun channel(shift: Int): Double {
            val c = ((argb shr shift) and 0xFF) / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    private fun hexColor(h: String): Int? {
        if (h.any { Character.digit(it, 16) < 0 }) return null
        val full = when (h.length) {
            3, 4 -> h.map { "$it$it" }.joinToString("")
            6, 8 -> h
            else -> return null
        }
        val alpha = if (full.length == 8) full.substring(6).toInt(16) else 0xFF
        return (alpha shl 24) or full.substring(0, 6).toInt(16)
    }

    private fun rgb(s: String): Int? {
        val inner = s.substringAfter('(').substringBeforeLast(')')
        val parts = inner.replace(',', ' ').replace('/', ' ').trim().split(WS)
        if (parts.size != 3 && parts.size != 4) return null
        val channels = parts.take(3).map { p ->
            val n = (if (p.endsWith('%')) p.dropLast(1).toDoubleOrNull()?.times(2.55) else p.toDoubleOrNull()) ?: return null
            n.coerceIn(0.0, 255.0)
        }
        val alpha = parts.getOrNull(3)?.let { p ->
            (if (p.endsWith('%')) p.dropLast(1).toDoubleOrNull()?.div(100) else p.toDoubleOrNull()) ?: return null
        } ?: 1.0
        return argb(alpha.coerceIn(0.0, 1.0), channels[0], channels[1], channels[2])
    }

    /** `color-mix(in srgb, A p%, B q%)`, premultiplied as CSS Color 5 specifies. */
    private fun mix(s: String): Int? {
        val open = s.indexOf('(')
        val close = closingParen(s, open) ?: return null
        val args = splitArgs(s.substring(open + 1, close))
        if (args.size != 3 || args[0].replace(WS, " ") != "in srgb") return null
        val (c1, p1) = stop(args[1]) ?: return null
        val (c2, p2) = stop(args[2]) ?: return null
        val w1 = p1 ?: p2?.let { 100 - it } ?: 50.0
        val w2 = p2 ?: (100 - w1)
        val total = w1 + w2
        if (total <= 0) return null
        val f1 = w1 / total
        val f2 = w2 / total
        fun a(c: Int) = ((c ushr 24) and 0xFF) / 255.0
        fun ch(c: Int, shift: Int) = ((c shr shift) and 0xFF).toDouble()
        val alpha = a(c1) * f1 + a(c2) * f2
        if (alpha == 0.0) return 0
        fun mixed(shift: Int) = (ch(c1, shift) * a(c1) * f1 + ch(c2, shift) * a(c2) * f2) / alpha
        return argb(alpha * min(total, 100.0) / 100, mixed(16), mixed(8), mixed(0))
    }

    /** `color 10%`, `10% color` or `color` → the colour and its percentage. */
    private fun stop(arg: String): Pair<Int, Double?>? {
        val first = arg.substringBefore(' ')
        val last = arg.substringAfterLast(' ')
        val (text, pct) = when {
            last.endsWith('%') && last != arg -> arg.substringBeforeLast(' ') to last
            first.endsWith('%') && first != arg -> arg.substringAfter(' ') to first
            else -> arg to null
        }
        val color = color(text) ?: return null
        val percent = pct?.let { it.dropLast(1).toDoubleOrNull() ?: return null }
        return color to percent
    }

    private fun argb(alpha: Double, r: Double, g: Double, b: Double): Int =
        ((alpha * 255).roundToInt() shl 24) or (r.roundToInt() shl 16) or (g.roundToInt() shl 8) or b.roundToInt()

    /** A length in dp: px as is, rem against 16, em against [fontSize]. */
    fun length(value: String, fontSize: Float): Float? {
        val s = value.trim().lowercase()
        if (s == "0") return 0f
        val m = LENGTH.matchEntire(s) ?: return null
        val n = m.groupValues[1].toFloat()
        return when (m.groupValues[2]) {
            "px" -> n
            "rem" -> n * ROOT_PX
            else -> n * fontSize
        }
    }

    fun radius(value: String, fontSize: Float): CssRadius? {
        val s = value.trim()
        if (s.endsWith('%')) return s.dropLast(1).toFloatOrNull()?.let { CssRadius(it, isPercent = true) }
        return length(s, fontSize)?.let { CssRadius(it) }
    }

    /** `v` or `v h` (the first two of a longer shorthand: top and right). */
    fun padding(value: String, fontSize: Float): Insets? {
        val parts = value.trim().split(WS)
        val vertical = length(parts[0], fontSize) ?: return null
        val horizontal = if (parts.size > 1) length(parts[1], fontSize) ?: return null else vertical
        return Insets(vertical, horizontal)
    }

    fun fontWeight(value: String): Int? = when (val s = value.trim().lowercase()) {
        "normal" -> 400
        "bold" -> 700
        else -> s.toIntOrNull()?.takeIf { it in 1..1000 }
    }

    fun fontFamilies(value: String): List<String> =
        splitArgs(value).map { it.trim('"', '\'', ' ') }.filter { it.isNotEmpty() }

    fun hasShadow(value: String): Boolean = value.isNotBlank() && value.trim().lowercase() != "none"
}
