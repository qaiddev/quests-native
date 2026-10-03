package dev.qaid.quests.ui

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.util.AttributeSet
import android.util.Log
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import dev.qaid.quests.QaidQuests
import dev.qaid.quests.core.QaidQuestText
import dev.qaid.quests.core.QuestAnswer
import dev.qaid.quests.core.QuestController
import dev.qaid.quests.internal.ActivityCallbacks
import dev.qaid.quests.internal.TAG
import dev.qaid.quests.internal.findActivity
import java.util.UUID

/**
 * A qaid quest, inline. It loads when it first appears (nothing is fetched or sent before),
 * fills the space it is given, and scrolls inside it when that space is bounded. It keeps its
 * place across a configuration change. Leaving it never submits; what was typed is saved.
 *
 * @param questId the quest's id from qaid.
 * @param screen where the person is ("Checkout"): appended to the page URL and stored with
 *   the response.
 * @param metadata extra keys for the response's metadata, e.g. `feedbackId`.
 * @param onComplete called once, after the thank-you screen appears, with the submitted
 *   answers (hidden questions left out).
 * @param onClose shows a close button when set, and is called when it is tapped.
 */
@Composable
fun QaidQuest(
    questId: String,
    modifier: Modifier = Modifier,
    screen: String? = null,
    metadata: Map<String, String>? = null,
    onComplete: (Map<String, QuestAnswer>) -> Unit = {},
    onClose: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val key = rememberSaveable(questId, screen) { UUID.randomUUID().toString() }
    val host = remember(key) { QaidQuests.host(key, context, questId, screen, metadata) }
    if (host == null) {
        LaunchedEffect(Unit) { Log.e(TAG, "QaidQuests.configure() must be called before showing a quest") }
        Box(modifier, contentAlignment = Alignment.Center) { Text(QaidQuestText().notConfigured) }
        return
    }
    SideEffect { host.onComplete = onComplete }
    DisposableEffect(key) {
        onDispose {
            // A rotation rebuilds the activity: keep the quest for the new one.
            if (context.findActivity()?.isChangingConfigurations != true) QaidQuests.release(key)
        }
    }
    LaunchedEffect(host) { host.controller.start() }
    QuestContent(
        controller = host.controller,
        presented = false,
        onClose = onClose?.let { close -> { host.controller.close(); close() } },
        modifier = modifier,
    )
}

/**
 * [QaidQuest] for View-based layouts. Set [questId] (and the rest) before it is attached; the
 * hosting activity must be a ComponentActivity (AppCompatActivity is one).
 */
class QaidQuestView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : AbstractComposeView(context, attrs, defStyleAttr) {
    var questId: String? by mutableStateOf(null)
    var screen: String? by mutableStateOf(null)
    var metadata: Map<String, String>? by mutableStateOf(null)
    var onComplete: ((Map<String, QuestAnswer>) -> Unit)? by mutableStateOf(null)
    var onClose: (() -> Unit)? by mutableStateOf(null)

    @Composable
    override fun Content() {
        val id = questId ?: return
        val complete = onComplete
        QaidQuest(id, Modifier, screen, metadata, onComplete = { complete?.invoke(it) }, onClose = onClose)
    }
}

/**
 * The presented form: a bottom sheet in its own dialog window over the activity, so it works
 * from any Activity, Compose or not. Closing — Close, back, or a tap outside — never submits.
 *
 * A dialog can't outlive its activity, so when a rotation (or any configuration change)
 * rebuilds the activity, the sheet is taken down and shown again over the new one, with the
 * same controller: the person stays on the same step with the same answers, and neither
 * `close()` nor `onClose` runs. Main thread only.
 */
internal object QuestSheet {
    private class Presented(val controller: QuestController, val onClose: (() -> Unit)?)

    private class Shown(val dialog: ComponentDialog, val sheet: Presented) {
        /** Set when the dialog goes away for good, or to be shown again over a rebuilt activity. */
        var finished = false
    }

    /** Dialogs on screen, by activity. */
    private val showing = HashMap<Activity, MutableList<Shown>>()

    /** Sheets whose activity is being rebuilt, by [key], to show over the new one. */
    private val waiting = HashMap<String, MutableList<Presented>>()
    private var watching = false

    fun present(activity: Activity, controller: QuestController, onClose: (() -> Unit)?) {
        watch(activity.application)
        show(activity, Presented(controller, onClose))
        controller.start()
    }

    /** An activity and the one rebuilt in its place share their task and class. */
    private fun key(activity: Activity) = "${activity.taskId}/${activity.javaClass.name}"

    private fun watch(app: Application) {
        if (watching) return
        watching = true
        app.registerActivityLifecycleCallbacks(object : ActivityCallbacks() {
            override fun onActivityStarted(activity: Activity) {
                waiting.remove(key(activity))?.forEach { show(activity, it) }
            }

            override fun onActivityDestroyed(activity: Activity) {
                val rebuilding = activity.isChangingConfigurations
                showing.remove(activity)?.forEach { shown ->
                    if (rebuilding) {
                        shown.finished = true
                        waiting.getOrPut(key(activity)) { mutableListOf() } += shown.sheet
                    }
                    shown.dialog.dismiss()
                }
            }
        })
    }

    private fun show(activity: Activity, sheet: Presented) {
        val dialog = ComponentDialog(activity, android.R.style.Theme_Translucent_NoTitleBar)
        val shown = Shown(dialog, sheet)
        val view = ComposeView(activity).apply {
            setContent { Sheet(sheet.controller, onDismiss = { dialog.dismiss() }) }
        }
        dialog.setContentView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        dialog.window?.let { window ->
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            @Suppress("DEPRECATION")
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            WindowCompat.setDecorFitsSystemWindows(window, false)
        }
        dialog.setOnDismissListener {
            showing[activity]?.remove(shown)
            if (!shown.finished) {
                shown.finished = true
                sheet.controller.close()
                sheet.onClose?.invoke()
            }
        }
        showing.getOrPut(activity) { mutableListOf() } += shown
        dialog.show()
    }
}

@Composable
private fun Sheet(controller: QuestController, onDismiss: () -> Unit) {
    val state by controller.state.collectAsState()
    val title = state.title
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            // The sheet is a dialog pane: TalkBack names it by the quest's title.
            .semantics { paneTitle = title ?: "Questionnaire" }
            .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.4f))
            // A tap on the scrim closes, as the web's backdrop does. A gesture, not clickable(),
            // so the scrim adds no node of its own for a screen reader (back and Close do the same).
            .pointerInput(Unit) { detectTapGestures { onDismiss() } }
            .windowInsetsPadding(WindowInsets.statusBars),
        contentAlignment = Alignment.BottomCenter,
    ) {
        val maxSheet = maxHeight * 0.92f
        QuestContent(
            controller = controller,
            presented = true,
            onClose = onDismiss,
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .heightIn(max = maxSheet)
                // Taps on the sheet itself must not reach the scrim behind it.
                .pointerInput(Unit) { detectTapGestures {} },
        )
    }
}
