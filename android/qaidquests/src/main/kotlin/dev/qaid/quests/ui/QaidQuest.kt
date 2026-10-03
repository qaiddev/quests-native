package dev.qaid.quests.ui

import android.app.Activity
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import dev.qaid.quests.QaidQuests
import dev.qaid.quests.core.QuestAnswer
import dev.qaid.quests.core.QuestController
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
 */
internal object QuestSheet {
    fun show(activity: Activity, controller: QuestController, onClose: (() -> Unit)?) {
        val dialog = ComponentDialog(activity, android.R.style.Theme_Translucent_NoTitleBar)
        var closed = false
        val view = ComposeView(activity).apply {
            setContent { Sheet(controller, onDismiss = { dialog.dismiss() }) }
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
            if (!closed) {
                closed = true
                controller.close()
                onClose?.invoke()
            }
        }
        // The dialog can't outlive its activity (a rotation would leak its window).
        (activity as? LifecycleOwner)?.lifecycle?.addObserver(
            LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_DESTROY && dialog.isShowing) dialog.dismiss() },
        )
        dialog.show()
        controller.start()
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
