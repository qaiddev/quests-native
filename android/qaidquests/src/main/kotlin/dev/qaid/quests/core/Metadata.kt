package dev.qaid.quests.core

import org.json.JSONObject

/**
 * The app and device a response came from: sent as the response's `metadata` and folded into
 * its user agent, the same shape the feedback SDK sends.
 */
data class DeviceInfo(
    val osVersion: String,
    val model: String,
    val appName: String,
    val appVersion: String,
    val build: String,
    val locale: String,
    val platform: String = "android",
    val sdkVersion: String = QaidQuestsSdk.VERSION,
) {
    val userAgent: String
        get() = "$appName/$appVersion ($build; ${if (platform == "android") "Android" else platform} $osVersion; $model) QaidQuests/$sdkVersion"

    companion object {
        /** "Google Pixel 8" from Google + "Pixel 8"; a model that already names its maker ("OnePlus 9") is left alone. */
        fun model(manufacturer: String, model: String): String =
            if (model.startsWith(manufacturer, ignoreCase = true)) model else "$manufacturer $model"
    }
}

/** Who is signed in, as the app chooses to say. Sent as `metadata.user`. */
data class QaidUser(val id: String? = null, val email: String? = null, val name: String? = null) {
    /** Blank fields dropped, each cut to the metadata value limit; null when nothing is left. */
    fun cleaned(): QaidUser? {
        fun clean(value: String?) = value.orEmpty().trim().take(CustomMetadata.MAX_VALUE_LENGTH).ifEmpty { null }
        val user = QaidUser(clean(id), clean(email), clean(name))
        return if (user.id == null && user.email == null && user.name == null) null else user
    }

    fun toJson(): JSONObject = JSONObject().apply {
        id?.let { put("id", it) }
        email?.let { put("email", it) }
        name?.let { put("name", it) }
    }
}

/**
 * The app's own key/value pairs, sent as `metadata.custom`, with the feedback SDK's limits:
 * [MAX_KEYS] keys (new ones past that are ignored), keys cut to [MAX_KEY_LENGTH], values to
 * [MAX_VALUE_LENGTH]. Safe from any thread.
 */
class CustomMetadata {
    private val values = LinkedHashMap<String, String>()

    /** Sets [key], or removes it when [value] is null. */
    @Synchronized
    fun set(key: String, value: String?) {
        val k = key.trim().take(MAX_KEY_LENGTH)
        if (k.isEmpty()) return
        if (value == null) {
            values.remove(k)
            return
        }
        if (k !in values && values.size >= MAX_KEYS) return
        values[k] = value.take(MAX_VALUE_LENGTH)
    }

    @Synchronized
    fun clear() = values.clear()

    @Synchronized
    fun snapshot(): Map<String, String> = LinkedHashMap(values)

    companion object {
        const val MAX_KEYS = 30
        const val MAX_KEY_LENGTH = 64
        const val MAX_VALUE_LENGTH = 500
    }
}

object QuestMetadata {
    const val SOURCE = "native-quest"

    /**
     * The create call's `metadata`: the built-in keys flat, `user` and `custom` nested (each
     * left out when empty), then [extra] — the view's own metadata, e.g. a `feedbackId` that
     * links the response to a feedback report — flat on top, so it wins.
     */
    fun build(
        device: DeviceInfo,
        screen: String?,
        user: QaidUser? = null,
        custom: Map<String, String> = emptyMap(),
        extra: Map<String, String> = emptyMap(),
    ): JSONObject = JSONObject().apply {
        put("platform", device.platform)
        put("osVersion", device.osVersion)
        put("device", device.model)
        put("app", device.appName)
        put("appVersion", device.appVersion)
        put("build", device.build)
        put("locale", device.locale)
        put("sdk", "qaid-quests-${device.platform}/${device.sdkVersion}")
        put("source", SOURCE)
        if (!screen.isNullOrEmpty()) put("screen", screen)
        user?.cleaned()?.let { put("user", it.toJson()) }
        if (custom.isNotEmpty()) put("custom", JSONObject(custom as Map<*, *>))
        extra.forEach { (k, v) -> put(k, v) }
    }
}

object PageUrls {
    /** The configured page URL, plus the screen as one more path segment. */
    fun pageUrl(base: String, screen: String?): String {
        val segment = screen?.let(::slug).orEmpty()
        return if (segment.isEmpty()) base else base.trimEnd('/') + "/" + segment
    }

    /** "Call Sheets" → "call-sheets": lower case, ASCII letters and digits, single hyphens. */
    fun slug(text: String): String {
        val out = StringBuilder()
        var pendingHyphen = false
        for (c in text.lowercase()) {
            if (c in 'a'..'z' || c in '0'..'9') {
                if (pendingHyphen && out.isNotEmpty()) out.append('-')
                pendingHyphen = false
                out.append(c)
            } else {
                pendingHyphen = true
            }
        }
        return out.toString().take(60)
    }
}
