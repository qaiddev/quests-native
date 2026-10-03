package dev.qaid.quests

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.qaid.quests.core.CustomMetadata
import dev.qaid.quests.core.PageUrls
import dev.qaid.quests.core.PendingResponse
import dev.qaid.quests.core.QaidQuestError
import dev.qaid.quests.core.QaidQuestErrorEvent
import dev.qaid.quests.core.QaidQuestsConfig
import dev.qaid.quests.core.QaidUser
import dev.qaid.quests.core.QuestAnswer
import dev.qaid.quests.core.QuestCallbacks
import dev.qaid.quests.core.QuestController
import dev.qaid.quests.core.QuestEnvironment
import dev.qaid.quests.core.QuestMetadata
import dev.qaid.quests.core.QuestOperation
import dev.qaid.quests.core.QuestTransport
import dev.qaid.quests.internal.Environment
import dev.qaid.quests.internal.HttpTransport
import dev.qaid.quests.internal.PendingQueue
import dev.qaid.quests.internal.TAG
import dev.qaid.quests.internal.VisitorId
import dev.qaid.quests.ui.QuestSheet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * qaid.dev quests for an Android app, drawn natively with Jetpack Compose.
 *
 * ```kotlin
 * // once, in Application.onCreate
 * QaidQuests.configure(this, QaidQuestsConfig(apiKey = "<embed key>", appName = "MyApp"))
 *
 * // inline, in any composable
 * QaidQuest(questId = "<quest id>", screen = "Settings", onComplete = { answers -> … })
 *
 * // or as a sheet over an activity
 * QaidQuests.present(activity, questId = "<quest id>")
 * ```
 *
 * Nothing is fetched or sent until a quest appears on screen. Answers save as the person
 * goes; a finished quest that can't reach qaid is kept on the device and sent when it can.
 * `present` belongs on the main thread; the rest may be called from any thread.
 */
object QaidQuests {
    @Volatile
    private var config: QaidQuestsConfig? = null

    @Volatile
    private var user: QaidUser? = null
    private val customMetadata = CustomMetadata()
    private val main = Handler(Looper.getMainLooper())

    /** Told about every failed call to qaid, on the main thread. Never thrown, never blocks the person. */
    @Volatile
    var onError: ((QaidQuestErrorEvent) -> Unit)? = null

    /** The network; tests swap in a scripted one. */
    @Volatile
    internal var transport: QuestTransport = HttpTransport

    /** Quests outlive their composition (a sent save finishes after the view goes); main thread. */
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Inline quests kept across a configuration change, by their saved key. Main thread. */
    internal val hosts = HashMap<String, QuestHost>()

    val isConfigured: Boolean get() = config != null

    internal val currentConfig: QaidQuestsConfig? get() = config

    /** Configure, and send any responses saved while the app was offline. */
    fun configure(context: Context, config: QaidQuestsConfig) {
        this.config = config
        PendingQueue.attach(context)
        PendingQueue.flush()
    }

    /** Who is signed in, sent with each new response as `metadata.user`. Blank values are left out. */
    fun setUser(id: String? = null, email: String? = null, name: String? = null) {
        user = QaidUser(id, email, name).cleaned()
    }

    fun clearUser() {
        user = null
    }

    /**
     * One key/value of the app's own, sent with each new response under `metadata.custom`;
     * null removes the key. At most 30 keys, keys cut to 64 characters, values to 500.
     */
    fun setMetadata(key: String, value: String?) = customMetadata.set(key, value)

    fun clearMetadata() = customMetadata.clear()

    /**
     * Show a quest in a sheet over [activity]. Closing it (Close, back, a tap outside) never
     * submits, as on the web; what was typed is still saved.
     *
     * @param screen where the person is ("Checkout"): appended to the page URL and stored with
     *   the response.
     * @param metadata extra keys for the response's metadata, e.g. `feedbackId` to link it to
     *   a feedback report.
     */
    fun present(
        activity: Activity,
        questId: String,
        screen: String? = null,
        metadata: Map<String, String>? = null,
        onComplete: ((Map<String, QuestAnswer>) -> Unit)? = null,
        onClose: (() -> Unit)? = null,
    ) {
        checkNotNull(config) { "QaidQuests.configure() must be called before present()" }
        val host = newHost(activity, questId, screen, metadata)
        host.onComplete = { onComplete?.invoke(it) }
        QuestSheet.show(activity, host.controller, onClose)
    }

    /** The host kept under [key], or a new one. */
    internal fun host(key: String, context: Context, questId: String, screen: String?, metadata: Map<String, String>?): QuestHost? {
        hosts[key]?.let { return it }
        if (config == null) return null
        return newHost(context, questId, screen, metadata).also { hosts[key] = it }
    }

    /** The inline quest under [key] is gone for good: save what's typed, forget it. */
    internal fun release(key: String) {
        hosts.remove(key)?.controller?.close()
    }

    private fun newHost(context: Context, questId: String, screen: String?, metadata: Map<String, String>?): QuestHost {
        val config = checkNotNull(config)
        val app = context.applicationContext ?: context
        PendingQueue.attach(app)
        val device = Environment.deviceInfo(app, config.appName)
        val environment = QuestEnvironment(
            pageUrl = PageUrls.pageUrl(config.pageUrlBase(app.packageName), screen),
            visitorId = config.visitorId?.takeIf { it.isNotBlank() } ?: VisitorId.get(app),
            userAgent = device.userAgent,
            metadata = QuestMetadata.build(device, screen, user, customMetadata.snapshot(), metadata.orEmpty()),
        )
        val host = QuestHost()
        host.controller = QuestController(questId, config, environment, transport, scope, object : QuestCallbacks {
            override fun onComplete(answers: Map<String, QuestAnswer>) = host.onComplete(answers)
            override fun onError(event: QaidQuestErrorEvent) = report(event)
            override fun log(message: String) {
                Log.i(TAG, message)
            }
            override fun persist(pending: PendingResponse): Boolean = PendingQueue.save(app, pending)
        })
        return host
    }

    internal fun report(questId: String, operation: QuestOperation, error: QaidQuestError) =
        report(QaidQuestErrorEvent(questId, operation, error))

    private fun report(event: QaidQuestErrorEvent) {
        Log.w(TAG, "${event.operation} failed for quest ${event.questId}: ${event.error.message}")
        main.post { onError?.invoke(event) }
    }
}

/** One quest's controller and the host's latest onComplete (which a recomposition may replace). */
internal class QuestHost {
    lateinit var controller: QuestController
    var onComplete: (Map<String, QuestAnswer>) -> Unit = {}
}
