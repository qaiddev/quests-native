package dev.qaid.quests.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.qaid.quests.core.ProgressPosition
import dev.qaid.quests.core.QuestController
import dev.qaid.quests.core.QuestPhase
import dev.qaid.quests.core.QuestStep
import dev.qaid.quests.core.QuestViewState

/** Test tags, also handy for an app's own UI tests. */
object QaidQuestTags {
    const val CARD = "qaid-quest"
    const val PRIMARY = "qaid-quest-primary"
    const val BACK = "qaid-quest-back"
    const val CLOSE = "qaid-quest-close"
    const val ERROR = "qaid-quest-error"
    const val PROGRESS = "qaid-quest-progress"
    const val TEXT = "qaid-quest-text"
    const val CURRENCY = "qaid-quest-currency"
    const val RANGE = "qaid-quest-range"
    const val DATE = "qaid-quest-date"
    const val RETRY = "qaid-quest-retry"
    fun option(value: String) = "qaid-quest-option-$value"
}

/** The whole quest card for [controller]; [presented] is the sheet (a Close button on end screens). */
@Composable
internal fun QuestContent(
    controller: QuestController,
    presented: Boolean,
    onClose: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val state by controller.state.collectAsState()
    val saving by controller.saving.collectAsState()
    ProvideQuestTheme(state.style) {
        QuestCard(state, saving, presented, controller, onClose, modifier)
    }
}

@Composable
private fun QuestCard(
    state: QuestViewState,
    saving: Boolean,
    presented: Boolean,
    controller: QuestController,
    onClose: (() -> Unit)?,
    modifier: Modifier,
) {
    val ui = LocalQuestUi.current
    val c = ui.colors
    val m = ui.metrics
    Surface(
        modifier = modifier.testTag(QaidQuestTags.CARD),
        shape = if (presented) m.cardRadius.topShape() else m.cardRadius.shape(),
        color = c.cardBg.color(),
        contentColor = c.cardFg.color(),
        border = if (presented) null else BorderStroke(1.dp, c.cardBorder.color()),
        shadowElevation = if (presented && m.cardShadow) 12.dp else 0.dp,
    ) {
        // A sheet runs to the bottom edge: keep its content clear of the nav bar and keyboard.
        val insets = if (presented) Modifier.navigationBarsPadding().imePadding() else Modifier
        Column(insets.padding(m.cardPadding.dp)) {
            when (state.phase) {
                QuestPhase.LOADING -> Loading(state)
                QuestPhase.LOAD_FAILED -> EndScreen(
                    title = state.text.loadErrorTitle,
                    message = state.loadError,
                    assertive = true,
                    action = state.text.retry to controller::retryLoad,
                    close = onClose.takeIf { presented }?.let { state.text.close to it },
                )
                QuestPhase.ANSWERING, QuestPhase.SUBMITTING -> Steps(state, saving, controller, onClose)
                QuestPhase.SUBMIT_FAILED -> EndScreen(
                    title = state.text.submitErrorTitle,
                    message = state.text.submitErrorMessage,
                    assertive = true,
                    action = state.text.tryAgain to controller::retrySubmit,
                    close = onClose.takeIf { presented }?.let { state.text.close to it },
                )
                QuestPhase.DONE -> ThankYou(state, presented, onClose)
            }
        }
    }
}

@Composable
private fun Loading(state: QuestViewState) {
    val ui = LocalQuestUi.current
    Column(
        Modifier.fillMaxWidth().padding(vertical = 24.dp).semantics { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(color = ui.colors.accent.color(), modifier = Modifier.size(28.dp))
        Text(state.text.loading, color = ui.colors.mutedFg.color())
    }
}

@Composable
private fun ColumnScope.Steps(state: QuestViewState, saving: Boolean, controller: QuestController, onClose: (() -> Unit)?) {
    val step = state.step ?: return
    val ui = LocalQuestUi.current
    val m = ui.metrics
    val top = state.progressPosition == ProgressPosition.TOP
    val counter = state.showProgress
    val submitting = state.phase == QuestPhase.SUBMITTING
    if (state.title != null || (top && (counter || saving)) || onClose != null) {
        Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                state.title.orEmpty(),
                modifier = Modifier.weight(1f).semantics { heading() },
                fontSize = ui.em(1.125f),
                fontWeight = FontWeight.Bold,
            )
            if (top) {
                if (saving) Saving(state)
                if (counter) Counter(state, step)
            }
            if (onClose != null) CloseButton(state, onClose)
        }
    }
    state.description?.let {
        Text(it, color = ui.colors.mutedFg.color(), fontSize = ui.em(0.875f), modifier = Modifier.padding(bottom = 12.dp))
    }
    if (top && counter) ProgressBar(state, step, Modifier.fillMaxWidth().padding(bottom = m.sectionGap.dp))
    Body(Modifier.weight(1f, fill = false)) {
        AnimatedContent(
            targetState = step,
            contentKey = { "${it.index}:${it.question.id}" },
            transitionSpec = {
                if (ui.reduceMotion) {
                    EnterTransition.None togetherWith ExitTransition.None
                } else {
                    (fadeIn(tween(220)) + slideInVertically(tween(220)) { it / 12 }) togetherWith fadeOut(tween(90))
                }
            },
            label = "step",
        ) { shown -> StepBody(state, shown, controller) }
    }
    Row(
        Modifier.fillMaxWidth().padding(top = m.sectionGap.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        step.backLabel?.let { SecondaryButton(it, Modifier.testTag(QaidQuestTags.BACK), controller::back) }
        if (!top && counter) {
            ProgressBar(state, step, Modifier.weight(1f).widthIn(min = 60.dp))
            if (saving) Saving(state)
            Counter(state, step)
        } else {
            Spacer(Modifier.weight(1f))
        }
        PrimaryButton(
            label = if (submitting) state.text.sending else step.primaryLabel,
            busy = submitting,
            modifier = Modifier.testTag(QaidQuestTags.PRIMARY),
            onClick = controller::next,
        )
    }
}

/** Scrolls when the card has a bounded height (a sheet, a sized container); grows otherwise. */
@Composable
private fun Body(modifier: Modifier, content: @Composable () -> Unit) {
    BoxWithConstraints(modifier) {
        val scroll = if (constraints.hasBoundedHeight) Modifier.verticalScroll(rememberScrollState()) else Modifier
        Box(scroll) { content() }
    }
}

@Composable
private fun StepBody(state: QuestViewState, step: QuestStep, controller: QuestController) {
    val ui = LocalQuestUi.current
    val question = step.question
    val label = remember { FocusRequester() }
    // Focus moves to the new question's label on every step after the first, as the web's
    // embed moves it, so a screen reader starts at the question.
    LaunchedEffect(step.index, question.id) {
        if (step.index > 0) runCatching { label.requestFocus() }
    }
    Column(verticalArrangement = Arrangement.spacedBy(ui.metrics.gap.dp)) {
        Row(
            Modifier
                .focusRequester(label)
                .focusable()
                .semantics(mergeDescendants = true) {
                    heading()
                    contentDescription = if (question.required) "${question.label}, ${state.text.requiredLabel}" else question.label
                },
        ) {
            Text(question.label, fontSize = ui.em(1.125f), fontWeight = FontWeight.SemiBold)
            if (question.required) Text(" *", color = ui.colors.error.color(), fontSize = ui.em(1.125f))
        }
        question.description?.takeIf { it.isNotBlank() }?.let {
            Text(it, color = ui.colors.mutedFg.color(), fontSize = ui.em(0.875f))
        }
        if (step.unsupported) {
            Unsupported(state)
        } else {
            QuestInput(state, step, onChange = { controller.answer(question.id, it) }, onSubmit = controller::next)
        }
        if (step.error != null) {
            Text(
                step.error.orEmpty(),
                color = ui.colors.error.color(),
                fontSize = ui.em(0.8125f),
                modifier = Modifier.testTag(QaidQuestTags.ERROR).semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

@Composable
private fun Unsupported(state: QuestViewState) {
    val ui = LocalQuestUi.current
    Surface(
        shape = ui.metrics.optionRadius.shape(),
        color = ui.colors.optionBg.color(),
        border = BorderStroke(1.dp, ui.colors.optionBorder.color()),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(state.text.unsupportedQuestion, color = ui.colors.mutedFg.color(), modifier = Modifier.padding(16.dp))
    }
}

@Composable
private fun Saving(state: QuestViewState) {
    val ui = LocalQuestUi.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 6.dp)) {
        Box(Modifier.size(6.dp).background(ui.colors.accent.color(), CircleShape))
        Text(" ${state.text.saving}", color = ui.colors.mutedFg.color(), fontSize = ui.em(0.75f))
    }
}

@Composable
private fun Counter(state: QuestViewState, step: QuestStep) {
    val ui = LocalQuestUi.current
    Text(
        "${step.index + 1} / ${step.total}",
        color = ui.colors.mutedFg.color(),
        fontSize = ui.em(0.875f),
        modifier = Modifier.padding(horizontal = 6.dp).semantics {
            // Spoken as the web announces a new step; the bar carries "Question n of total".
            contentDescription = state.text.fill(state.text.stepAnnouncement, step.index + 1, step.total)
            liveRegion = LiveRegionMode.Polite
        },
    )
}

@Composable
private fun ProgressBar(state: QuestViewState, step: QuestStep, modifier: Modifier) {
    val ui = LocalQuestUi.current
    val fraction by animateFloatAsState(
        state.progressFraction,
        animationSpec = if (ui.reduceMotion) tween(0) else tween(300),
        label = "progress",
    )
    Box(
        modifier
            .height(4.dp)
            .background(ui.colors.progressTrack.color(), CircleShape)
            .testTag(QaidQuestTags.PROGRESS)
            .clearAndSetSemantics {
                contentDescription = state.text.progressLabel
                progressBarRangeInfo = ProgressBarRangeInfo((step.index + 1).toFloat(), 0f..step.total.toFloat())
                stateDescription = state.text.fill(state.text.progressValue, step.index + 1, step.total)
            },
    ) {
        Box(Modifier.fillMaxWidth(fraction).height(4.dp).background(ui.colors.accent.color(), CircleShape))
    }
}

@Composable
private fun CloseButton(state: QuestViewState, onClose: () -> Unit) {
    val ui = LocalQuestUi.current
    IconButton(
        onClick = onClose,
        modifier = Modifier.testTag(QaidQuestTags.CLOSE).semantics { contentDescription = state.text.closeLabel },
    ) {
        val color = ui.colors.mutedFg.color()
        Canvas(Modifier.size(12.dp)) {
            val w = 2.dp.toPx()
            drawLine(color, Offset.Zero, Offset(size.width, size.height), w, StrokeCap.Round)
            drawLine(color, Offset(size.width, 0f), Offset(0f, size.height), w, StrokeCap.Round)
        }
    }
}

@Composable
private fun ThankYou(state: QuestViewState, presented: Boolean, onClose: (() -> Unit)?) {
    val ui = LocalQuestUi.current
    val title = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { title.requestFocus() } }
    Column(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val accent = ui.colors.accent.color()
        Box(Modifier.size(48.dp).background(accent.copy(alpha = 0.18f), CircleShape), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(22.dp)) {
                val path = Path().apply {
                    moveTo(size.width * 0.15f, size.height * 0.55f)
                    lineTo(size.width * 0.4f, size.height * 0.8f)
                    lineTo(size.width * 0.85f, size.height * 0.25f)
                }
                drawPath(path, accent, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
            }
        }
        Text(
            state.thankYouTitle,
            fontSize = ui.em(1.25f),
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.focusRequester(title).focusable().semantics {
                heading()
                liveRegion = LiveRegionMode.Polite
            },
        )
        state.thankYouMessage?.let { Text(it, color = ui.colors.mutedFg.color(), textAlign = TextAlign.Center) }
        if (state.savedOffline) {
            Text(state.text.savedOffline, color = ui.colors.mutedFg.color(), fontSize = ui.em(0.875f), textAlign = TextAlign.Center)
        }
        if (presented && onClose != null) {
            Spacer(Modifier.height(8.dp))
            PrimaryButton(state.text.close, busy = false, modifier = Modifier.testTag(QaidQuestTags.CLOSE), onClick = onClose)
        }
    }
}

/** Load error and submit error: a title, a message, the action, and Close in a sheet. */
@Composable
private fun EndScreen(
    title: String,
    message: String?,
    assertive: Boolean,
    action: Pair<String, () -> Unit>,
    close: Pair<String, () -> Unit>?,
) {
    val ui = LocalQuestUi.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(title) { runCatching { focus.requestFocus() } }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title,
            fontSize = ui.em(1.125f),
            fontWeight = FontWeight.Bold,
            modifier = Modifier.focusRequester(focus).focusable().semantics {
                heading()
                liveRegion = if (assertive) LiveRegionMode.Assertive else LiveRegionMode.Polite
            },
        )
        message?.let { Text(it, color = ui.colors.mutedFg.color()) }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            close?.let { (label, onClick) -> SecondaryButton(label, Modifier.testTag(QaidQuestTags.CLOSE), onClick) }
            PrimaryButton(action.first, busy = false, modifier = Modifier.testTag(QaidQuestTags.RETRY), onClick = action.second)
        }
    }
}

@Composable
private fun PrimaryButton(label: String, busy: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val ui = LocalQuestUi.current
    val c = ui.colors
    val m = ui.metrics
    Button(
        // Busy keeps focus and stays enabled (aria-disabled, not disabled, on the web); the tap is ignored.
        onClick = { if (!busy) onClick() },
        modifier = modifier.heightIn(min = 48.dp).semantics { if (busy) stateDescription = label },
        shape = m.buttonRadius.shape(),
        colors = ButtonDefaults.buttonColors(containerColor = c.primaryBg.color(), contentColor = c.primaryFg.color()),
        border = BorderStroke(1.dp, c.primaryBorder.color()),
        contentPadding = PaddingValues(horizontal = m.buttonPadding.horizontal.dp, vertical = m.buttonPadding.vertical.dp),
    ) {
        Text(label, fontSize = ui.em(0.9375f), fontWeight = ui.buttonWeight)
    }
}

@Composable
private fun SecondaryButton(label: String, modifier: Modifier, onClick: () -> Unit) {
    val ui = LocalQuestUi.current
    val c = ui.colors
    val m = ui.metrics
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        shape = m.buttonRadius.shape(),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = c.secondaryBg.color(), contentColor = c.secondaryFg.color()),
        border = BorderStroke(1.dp, c.secondaryBorder.color()),
        contentPadding = PaddingValues(horizontal = m.buttonPadding.horizontal.dp, vertical = m.buttonPadding.vertical.dp),
    ) {
        Text(label, fontSize = ui.em(0.9375f), fontWeight = ui.buttonWeight)
    }
}
