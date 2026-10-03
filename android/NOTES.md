# qaid quests for Android — notes

Artifact `dev.qaid:quests:0.1.0`, namespace `dev.qaid.quests`, minSdk 26, AAR `minCompileSdk` 35.
Compose BOM 2026.04.01 (Compose 1.11, Material 3 1.4), core-ktx 1.16.0, activity-ktx 1.10.1, coroutines 1.11.0.

## Including by source

`:qaidquests` applies `com.android.library` **and** `org.jetbrains.kotlin.plugin.compose`
(no version in the module file). An app that includes it by source must have the Compose
compiler plugin on its classpath at its own Kotlin version, as any Compose app does
(`id("org.jetbrains.kotlin.plugin.compose") apply false` in the root build). Publishing and
Kover are applied only from `android/build.gradle.kts`.

## Public API

```kotlin
// dev.qaid.quests
object QaidQuests {
    fun configure(context: Context, config: QaidQuestsConfig)
    val isConfigured: Boolean
    fun setUser(id: String? = null, email: String? = null, name: String? = null)
    fun clearUser()
    fun setMetadata(key: String, value: String?)
    fun clearMetadata()
    var onError: ((QaidQuestErrorEvent) -> Unit)?
    fun present(
        activity: Activity,
        questId: String,
        screen: String? = null,
        metadata: Map<String, String>? = null,
        onComplete: ((Map<String, QuestAnswer>) -> Unit)? = null,
        onClose: (() -> Unit)? = null,
    )
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

object QaidQuestTags { CARD, PRIMARY, BACK, CLOSE, ERROR, PROGRESS, TEXT, CURRENCY, RANGE, DATE, RETRY; fun option(value: String): String }

// dev.qaid.quests.core
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
    val progressPosition: ProgressPosition? = null,
    val visitorId: String? = null,            // default: the feedback SDK's per-install id
    val saveDebounceMs: Long = 500,
)

data class QaidQuestTheme(
    val accent: Int? = null, val error: Int? = null, val focus: Int? = null,   // ARGB
    val fontFamily: String? = null, val fontSize: Float? = null, val cornerRadius: Float? = null,
    val mode: QaidQuestThemeMode? = null, val preset: String? = null,
)
enum class QaidQuestThemeMode { AUTO, LIGHT, DARK }
enum class ProgressPosition { TOP, BOTTOM }

data class QaidQuestText(/* every visible / spoken string; English defaults are the web embed's */)

sealed interface QuestAnswer {
    data class Text(val value: String); data class Number(val value: Double)
    data class Choices(val values: List<String>); data object None
}

sealed class QaidQuestError : Exception {
    data class Network(val detail: String); data class Http(val status: Int, val error: String)
    data class Decoding(val detail: String); data class Storage(val detail: String)
    val isRetryable: Boolean
}
enum class QuestOperation { LOAD_DEFINITION, CREATE_RESPONSE, SAVE_ANSWER, SUBMIT, SEND_SAVED }
data class QaidQuestErrorEvent(val questId: String, val operation: QuestOperation, val error: QaidQuestError)
```

Everything else in `dev.qaid.quests.core` (session, controller, sync, queue, theme mapping) is
public so it can be tested on the JVM; it is not a supported API.

## Behaviour notes

- Theme `css` and `unstyled` have no native meaning: ignored and logged.
- Offline: a finished quest that can't reach qaid is saved in `noBackupFilesDir/qaid-quests-queue/`
  (20 responses, 7 days) and sent on `configure`, on the app's next foreground, and when a network
  comes up. The thank-you screen says so, and `onComplete` still fires.
- The inline quest survives a configuration change; the presented sheet (a `ComponentDialog`)
  closes with its activity.
- Robolectric tests run on SDK 34: Robolectric's SDK 35 runtime needs JDK 21, this build uses 17.
