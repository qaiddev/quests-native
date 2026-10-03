package dev.qaid.quests.core

import java.net.URI
import java.net.URLEncoder

object QaidQuestsSdk {
    const val VERSION = "0.1.0"
}

/**
 * Everything the SDK needs to know about the qaid project its quests belong to.
 *
 * [pageUrl] is what qaid stores as the response's URL and checks against the project's
 * Domain Restriction. Leave it null and responses use `app://<package name>`, which the
 * server reads as a reversed domain: `app://com.example.myapp` passes a restriction of
 * `example.com`. Either way a screen passed to the view or `present` is appended as one more
 * path segment, as the feedback SDK does.
 */
data class QaidQuestsConfig(
    /** The project's embed API key — the same one the web embed uses (not a `qd_live_` key). */
    val apiKey: String,
    /** Sent with each response's metadata and user agent. */
    val appName: String,
    /** `https://qaid.dev/api/quests`; definitions, responses and themes are all found from it. */
    val base: String = DEFAULT_BASE,
    val pageUrl: String? = null,
    /** The app's own colours, font and corners; these win over a saved theme. */
    val theme: QaidQuestTheme? = null,
    /** A quest theme saved in qaid, fetched alongside each quest. */
    val themeId: String? = null,
    /** Pin [themeId] to one published version instead of whichever is live. */
    val themeVersion: Int? = null,
    /** Every visible string, for apps that are not in English. */
    val text: QaidQuestText = QaidQuestText(),
    /** Move on 180 ms after a single-choice pick, as the web embed's `autoAdvance`. */
    val autoAdvance: Boolean = false,
    /** Overrides the quest's own progress position. */
    val progressPosition: ProgressPosition? = null,
    /** The visitor id to send; null shares the feedback SDK's per-install id. */
    val visitorId: String? = null,
    /** How long typing settles before an answer is saved. */
    val saveDebounceMs: Long = 500,
) {
    /** [pageUrl], or `app://<packageName>` (lower case, as iOS sends its bundle id) when the app set none. */
    fun pageUrlBase(packageName: String): String =
        pageUrl?.takeIf { it.isNotBlank() } ?: "app://${packageName.lowercase()}"

    /** `GET` this for a saved theme, or null when none is set. */
    fun themeUrl(): String? {
        val id = themeId.orEmpty().trim()
        if (id.isEmpty()) return null
        val version = themeVersion
        val path = "/api/quest-themes/${encode(id)}" + (if (version != null) "/v/$version" else "") + "/public"
        return originOf(base) + path + "?key=" + encode(apiKey)
    }

    companion object {
        const val DEFAULT_BASE = "https://qaid.dev/api/quests"

        /** `scheme://host[:port]` of [url]; qaid.dev's when it has none. */
        fun originOf(url: String): String {
            val uri = runCatching { URI(url.trim()) }.getOrNull()
            val scheme = uri?.scheme ?: return "https://qaid.dev"
            val host = uri.host ?: return "https://qaid.dev"
            return if (uri.port == -1) "$scheme://$host" else "$scheme://$host:${uri.port}"
        }

        internal fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }
}

enum class QaidQuestThemeMode { AUTO, LIGHT, DARK }

/**
 * The app's own look, layered over a saved theme (or qaid's defaults). Colours are ARGB ints
 * (`0xFF10B981.toInt()`, `Color.parseColor`, or Compose's `Color.toArgb()`). Null keeps
 * whatever the theme says.
 */
data class QaidQuestTheme(
    /** Primary button, progress, selection and range. Button text turns black or white to suit it. */
    val accent: Int? = null,
    val error: Int? = null,
    /** The focus ring around a field being typed in. */
    val focus: Int? = null,
    /** A system font family name ("serif", "monospace", "sans-serif-condensed", …). */
    val fontFamily: String? = null,
    /** The base text size in sp; every other size scales from it. qaid's default is 16. */
    val fontSize: Float? = null,
    /** One radius in dp for the card, buttons, options and fields. */
    val cornerRadius: Float? = null,
    /** Light, dark, or follow the system. */
    val mode: QaidQuestThemeMode? = null,
    /** `default`, `minimal`, `pill` or `dense`, as in qaid's theme editor. */
    val preset: String? = null,
)
