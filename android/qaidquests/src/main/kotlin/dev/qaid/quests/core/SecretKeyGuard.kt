package dev.qaid.quests.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest

/**
 * Catches a platform API secret (`QAID_SECRET_…`) put in this SDK's configuration or metadata.
 *
 * That key reads and changes a whole project, and anything in an app can be read by whoever
 * has the app. When one turns up, the SDK:
 * 1. drops it, so it is never sent with a response,
 * 2. logs an error saying what to do,
 * 3. reports its sha256 (never the key) to qaid, which revokes it and emails the owner.
 *
 * The same rule as the web embeds' `secret-key-guard.ts`, and as qaid thumbs for Android.
 */
internal object SecretKeyGuard {
    /** Only the new secret prefix; embed keys (`QAID_PUB_…`) never match. */
    val PATTERN = Regex("QAID_SECRET_[0-9A-Za-z]{16,}")

    /** What a report sends as `source`. */
    const val SOURCE = "quests-android"

    const val MESSAGE =
        "[qaid] A secret platform API key (QAID_SECRET_…) is in this app's config. " +
            "Anyone who has the app can read it, and it can read and change your project's data. " +
            "The SDK ignored it, and qaid revoked it and emailed the project owner. " +
            "Make a new platform key, keep it on your server, and use the embed key (QAID_PUB_…) here."

    /** [text] with every secret taken out (then trimmed), each one added to [found]. Unchanged when none. */
    fun scrub(text: String, found: MutableList<String>): String {
        val matches = PATTERN.findAll(text).map { it.value }.toList()
        if (matches.isEmpty()) return text
        found += matches
        return PATTERN.replace(text, "").trim()
    }

    fun scrubOrNull(text: String?, found: MutableList<String>): String? = text?.let { scrub(it, found) }

    /** The values of a metadata map; keys are left alone. */
    fun scrub(metadata: Map<String, String>, found: MutableList<String>): Map<String, String> =
        metadata.mapValues { (_, value) -> scrub(value, found) }

    fun scrub(user: QaidUser?, found: MutableList<String>): QaidUser? =
        user?.let { QaidUser(scrubOrNull(it.id, found), scrubOrNull(it.email, found), scrubOrNull(it.name, found)) }

    /**
     * Every configured string that leaves the device: the key, the base URL, the page URL, the
     * app name (it is in the user agent), the theme id and the visitor id. A base that is
     * nothing but a secret falls back to qaid's. The SDK's words and look are never sent and not
     * looked at. Returns [config] itself when nothing was found.
     */
    fun scrub(config: QaidQuestsConfig, found: MutableList<String>): QaidQuestsConfig {
        val before = found.size
        val out = config.copy(
            apiKey = scrub(config.apiKey, found),
            appName = scrub(config.appName, found),
            base = scrub(config.base, found).ifEmpty { QaidQuestsConfig.DEFAULT_BASE },
            pageUrl = scrubOrNull(config.pageUrl, found),
            themeId = scrubOrNull(config.themeId, found),
            visitorId = scrubOrNull(config.visitorId, found),
        )
        return if (found.size == before) config else out
    }

    /** `/api/leaked-key` on [base]'s origin; null for one with no scheme or host. */
    fun reportUrl(base: String): String? {
        val uri = runCatching { URI(base.trim()) }.getOrNull() ?: return null
        val scheme = uri.scheme ?: return null
        val host = uri.host ?: return null
        val port = if (uri.port == -1) "" else ":${uri.port}"
        return "$scheme://$host$port/api/leaked-key"
    }

    /** Lower-case hex sha256 of the UTF-8 text. */
    fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /** `{"keyHash", "pageUrl", "source"}`: the key's hash, never the key. */
    fun reportBody(secret: String, pageUrl: String?): String = JSONObject().apply {
        put("keyHash", sha256Hex(secret))
        if (pageUrl != null) put("pageUrl", pageUrl)
        put("source", SOURCE)
    }.toString()

    /** The report's request: a `text/plain` POST, as the web embeds send it. Null without a host. */
    fun reportRequest(secret: String, base: String, pageUrl: String?): HttpRequest? {
        val url = reportUrl(base) ?: return null
        return HttpRequest("POST", url, mapOf("Content-Type" to "text/plain"), reportBody(secret, pageUrl))
    }
}

/** Logs a found secret and reports each one once per process. Thread-safe. */
internal object SecretKeyReports {
    private val reported = HashSet<String>()

    /**
     * Logs [SecretKeyGuard.MESSAGE], then sends one report over [transport] in [scope] for each
     * secret not reported yet, without waiting. Every failure is swallowed.
     */
    fun report(
        found: List<String>,
        base: String,
        pageUrl: String?,
        transport: QuestTransport,
        scope: CoroutineScope,
        log: (String) -> Unit,
    ) {
        if (found.isEmpty()) return
        runCatching { log(SecretKeyGuard.MESSAGE) }
        val fresh = synchronized(reported) { found.filter(reported::add) }
        for (secret in fresh) {
            val request = SecretKeyGuard.reportRequest(secret, base, pageUrl) ?: continue
            scope.launch { runCatching { transport.send(request) } }
        }
    }

    /** Test hook: forget what this process reported. */
    fun reset() = synchronized(reported) { reported.clear() }
}
