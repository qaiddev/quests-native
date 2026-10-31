# qaid quests for Android — notes

Artifact `dev.qaid:quests:0.1.0` (not on Maven Central yet), namespace `dev.qaid.quests`, minSdk 26, AAR `minCompileSdk` 35.
Compose BOM 2026.04.01 (Compose 1.11, Material 3 1.4), core-ktx 1.16.0, activity-ktx 1.10.1, coroutines 1.11.0.

## Including by source

`:qaidquests` applies `com.android.library` **and** `org.jetbrains.kotlin.plugin.compose`
(no version in the module file). An app that includes it by source must have the Compose
compiler plugin on its classpath at its own Kotlin version, as any Compose app does
(`id("org.jetbrains.kotlin.plugin.compose") apply false` in the root build). Publishing and
Kover are applied only from `android/build.gradle.kts`.

## Public API

Names, options, text keys, error cases and operations match the iOS SDK (`QaidQuests`); only
the type idioms differ (ARGB ints here, CSS colour strings there).

```kotlin
// dev.qaid.quests
object QaidQuests {
    fun configure(context: Context, config: QaidQuestsConfig)
    val isConfigured: Boolean
    var onError: ((QaidQuestsErrorEvent) -> Unit)?
    fun present(
        activity: Activity,
        questId: String,
        screen: String? = null,
        metadata: Map<String, String>? = null,
        onComplete: ((Map<String, QuestAnswer>) -> Unit)? = null,
        onClose: (() -> Unit)? = null,
    )
    fun flushOfflineQueue()
    fun setUser(id: String? = null, email: String? = null, name: String? = null)
    fun clearUser()
    fun setMetadata(key: String, value: String?)
    fun clearMetadata()
}

// dev.qaid.quests.ui
@Composable
fun QaidQuest(
    questId: String,
    modifier: Modifier = Modifier,
    screen: String? = null,
    metadata: Map<String, String>? = null,
    onComplete: (Map<String, QuestAnswer>) -> Unit = {},
    onClose: (() -> Unit)? = null,
)

class QaidQuestView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0,
) : AbstractComposeView {
    var questId: String?
    var screen: String?
    var metadata: Map<String, String>?
    var onComplete: ((Map<String, QuestAnswer>) -> Unit)?
    var onClose: (() -> Unit)?
}

/** Test tags; the same strings as iOS's accessibility identifiers. */
object QaidQuestTags { CARD, PRIMARY, BACK, CLOSE, ERROR, PROGRESS, TEXT, CURRENCY, RANGE, DATE, RETRY; fun option(value: String): String }

// dev.qaid.quests.core — everything else in this package is internal
object QaidQuestsSdk { const val VERSION = "0.1.0" }

data class QaidQuestsConfig(
    val apiKey: String,
    val appName: String,
    val base: String = "https://qaid.dev/api/quests",
    val pageUrl: String? = null,              // default app://<package name, lower case>; + "/<screen-slug>"
    val theme: QaidQuestTheme? = null,
    val themeId: String? = null,
    val themeVersion: Int? = null,
    val text: QaidQuestText = QaidQuestText(),
    val autoAdvance: Boolean = false,
    val progressPosition: QaidQuestProgressPosition? = null,
    val visitorId: String? = null,            // default: the feedback SDK's per-install id
    val saveDebounceMs: Long = 500,
) { companion object { const val DEFAULT_BASE: String } }

enum class QaidQuestProgressPosition { TOP, BOTTOM }

data class QaidQuestTheme(
    val accent: Int? = null, val error: Int? = null, val focus: Int? = null,   // ARGB
    val fontFamily: String? = null, val fontSize: Float? = null, val cornerRadius: Float? = null,
    val mode: Mode? = null, val preset: Preset? = null,
) {
    enum class Mode { AUTO, LIGHT, DARK }
    enum class Preset { DEFAULT, MINIMAL, PILL, DENSE }
}

data class QaidQuestText(/* every visible / spoken string, all defaulted */)
// next, back, submit, thankYouTitle, close,
// validationRequired, validationMinLength ({count}), validationAmount, validationDate,
// validationSelectOne, validationSelectMany,
// loading, loadErrorTitle, loadErrorStatus ({status}), loadErrorEmpty, loadErrorInvalid, loadErrorOffline,
// retry, submitErrorTitle, submitErrorMessage, tryAgain, sending, saving, savedOffline, notConfigured,
// unsupportedQuestion, datePlaceholder, clearDate, dateConfirm, dateCancel,
// required, closeForm, progressLabel, progressValue ({n}, {total}), stepAnnouncement ({n}, {total})

sealed interface QuestAnswer {
    data class Text(val value: String); data class Number(val value: Double)
    data class Choices(val values: List<String>); data object None
}

sealed class QaidQuestsError : Exception {
    data class Network(val detail: String); data class Server(val status: Int)
    data class Rejected(val status: Int, val message: String)
    data class InvalidResponse(val detail: String); data class Storage(val detail: String)
    val isRetryable: Boolean   // Network, Server, 408, 429
}

data class QaidQuestsErrorEvent(
    val questId: String,
    val operation: Operation,
    val questionId: String? = null,   // SAVE_ANSWER
    val error: QaidQuestsError,
) {
    enum class Operation { LOAD_QUEST, LOAD_THEME, CREATE_RESPONSE, SAVE_ANSWER, SUBMIT, OFFLINE_QUEUE }
}
```

## Behaviour notes

- Theme `css` and `unstyled` have no native meaning: ignored and logged. A theme that can't be
  loaded is reported as `LOAD_THEME` and the quest draws with qaid's defaults.
- Offline: a finished quest that can't reach qaid is saved in `noBackupFilesDir/qaid-quests-queue/`
  (20 responses, 7 days) and sent at once (in case the network is back), on `configure`, on the
  app's next foreground, and when a network comes up. The thank-you screen says so, and
  `onComplete` still fires. If the queue can't be written, the person gets Try again with
  nothing lost.
- Submit: once a call has failed on the network, submit goes straight to the offline queue; it
  never stays on "Sending…" more than 20 s before queueing.
- Text lengths count UTF-16 units and blanks use JavaScript's whitespace, as the web embed and
  iOS do. Currency reads the device's decimal separator and `.`; a grouped number ("1,000" in
  English) is sent as null.
- Text and currency fields are named through the text field's label slot (not a content
  description), so TalkBack reads what was typed.
- Both the inline quest and the presented sheet survive a configuration change: the sheet is
  shown again over the rebuilt activity with the same step and answers, without `onClose`.
- Robolectric tests run on SDK 34: Robolectric's SDK 35 runtime needs JDK 21, this build uses 17.
