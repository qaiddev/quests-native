package dev.qaid.quests.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.qaid.quests.core.ChoiceOption
import dev.qaid.quests.core.ChoiceQuestion
import dev.qaid.quests.core.CurrencyQuestion
import dev.qaid.quests.core.CurrencyRules
import dev.qaid.quests.core.DateQuestion
import dev.qaid.quests.core.DateRules
import dev.qaid.quests.core.ImageAlignment
import dev.qaid.quests.core.NumberText
import dev.qaid.quests.core.Question
import dev.qaid.quests.core.QuestAnswer
import dev.qaid.quests.core.QuestStep
import dev.qaid.quests.core.QuestViewState
import dev.qaid.quests.core.RangeQuestion
import dev.qaid.quests.core.RangeRules
import dev.qaid.quests.core.TextInputType
import dev.qaid.quests.core.TextQuestion
import dev.qaid.quests.core.TextRules
import dev.qaid.quests.internal.ImageCache
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** The control for the step's question. All rules live in Core; this only draws and reports. */
@Composable
internal fun QuestInput(
    state: QuestViewState,
    step: QuestStep,
    onChange: (QuestAnswer) -> Unit,
    onSubmit: () -> Unit,
) {
    when (val q = step.question) {
        is TextQuestion -> TextInput(q, step, onChange, onSubmit)
        is CurrencyQuestion -> CurrencyInput(q, step, onChange, onSubmit)
        is RangeQuestion -> RangeInput(q, step, onChange)
        is DateQuestion -> DateInput(state, q, step, onChange)
        is ChoiceQuestion -> ChoiceInput(q, step, onChange)
        else -> Unit
    }
}

/** The name, required flag and error a screen reader reads for a control. */
private fun Modifier.questControl(question: Question, error: String?, extra: String? = null) = semantics {
    contentDescription = question.label
    extra?.let { stateDescription = it }
    error?.let { error(it) }
}

@Composable
private fun fieldColors() = LocalQuestUi.current.colors.let { c ->
    OutlinedTextFieldDefaults.colors(
        focusedTextColor = c.inputFg.color(),
        unfocusedTextColor = c.inputFg.color(),
        focusedContainerColor = c.inputBg.color(),
        unfocusedContainerColor = c.inputBg.color(),
        errorContainerColor = c.inputBg.color(),
        focusedBorderColor = c.focus.color(),
        unfocusedBorderColor = c.inputBorder.color(),
        errorBorderColor = c.error.color(),
        cursorColor = c.focus.color(),
        focusedPlaceholderColor = c.inputPlaceholder.color(),
        unfocusedPlaceholderColor = c.inputPlaceholder.color(),
        focusedPrefixColor = c.mutedFg.color(),
        unfocusedPrefixColor = c.mutedFg.color(),
    )
}

@Composable
private fun TextInput(q: TextQuestion, step: QuestStep, onChange: (QuestAnswer) -> Unit, onSubmit: () -> Unit) {
    val ui = LocalQuestUi.current
    val initial = (step.value as? QuestAnswer.Text)?.value.orEmpty()
    // The field keeps its own text and caret (focus puts the caret at the end, as on the web);
    // every change is reported, untrimmed, as the web sends it.
    var field by remember(q.id) { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    val keyboard = when {
        q.multiline -> KeyboardType.Text
        q.inputType == TextInputType.EMAIL -> KeyboardType.Email
        q.inputType == TextInputType.TEL -> KeyboardType.Phone
        q.inputType == TextInputType.URL -> KeyboardType.Uri
        else -> KeyboardType.Text
    }
    OutlinedTextField(
        value = field,
        onValueChange = { next ->
            val clipped = TextRules.clip(next.text, q.maxLength)
            val changed = clipped != field.text
            field = if (clipped == next.text) next else TextFieldValue(clipped, TextRange(clipped.length))
            if (changed) onChange(QuestAnswer.Text(clipped))
        },
        modifier = Modifier.fillMaxWidth().testTag(QaidQuestTags.TEXT).questControl(q, step.error),
        placeholder = q.placeholder?.let { { Text(it) } },
        singleLine = !q.multiline,
        minLines = if (q.multiline) 4 else 1,
        isError = step.error != null,
        shape = ui.metrics.inputRadius.shape(),
        colors = fieldColors(),
        textStyle = androidx.compose.material3.LocalTextStyle.current.copy(color = ui.colors.inputFg.color()),
        // Enter moves on from a single-line field; a multiline one takes newlines.
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, imeAction = if (q.multiline) ImeAction.Default else ImeAction.Next),
        keyboardActions = KeyboardActions(onNext = { onSubmit() }),
    )
}

@Composable
private fun CurrencyInput(q: CurrencyQuestion, step: QuestStep, onChange: (QuestAnswer) -> Unit, onSubmit: () -> Unit) {
    val ui = LocalQuestUi.current
    var raw by remember(q.id) { mutableStateOf(CurrencyRules.display((step.value as? QuestAnswer.Number)?.value)) }
    val symbol = remember(q.currency, q.locale) { CurrencyRules.symbol(q.currency, CurrencyRules.locale(q.locale, Locale.getDefault())) }
    OutlinedTextField(
        value = raw,
        onValueChange = { typed ->
            val clean = CurrencyRules.sanitize(typed)
            if (clean != raw) {
                raw = clean
                onChange(CurrencyRules.parse(clean)?.let { QuestAnswer.Number(it) } ?: QuestAnswer.None)
            }
        },
        modifier = Modifier.fillMaxWidth().testTag(QaidQuestTags.CURRENCY).questControl(q, step.error),
        prefix = { Text(symbol) },
        placeholder = q.placeholder?.let { { Text(it) } },
        singleLine = true,
        isError = step.error != null,
        shape = ui.metrics.inputRadius.shape(),
        colors = fieldColors(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
        keyboardActions = KeyboardActions(onNext = { onSubmit() }),
    )
}

@Composable
private fun RangeInput(q: RangeQuestion, step: QuestStep, onChange: (QuestAnswer) -> Unit) {
    val ui = LocalQuestUi.current
    val c = ui.colors
    val value = (step.value as? QuestAnswer.Number)?.value ?: RangeRules.initial(q)
    val unit = q.unit.orEmpty()
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.Bottom) {
            Text(NumberText.format(value), fontSize = ui.em(2f), fontWeight = FontWeight.Bold, color = c.accent.color())
            if (unit.isNotEmpty()) Text(" $unit", fontSize = ui.em(1f), color = c.mutedFg.color())
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(QuestAnswer.Number(RangeRules.snap(q, it.toDouble()))) },
            valueRange = q.min.toFloat()..q.max.toFloat(),
            steps = RangeRules.sliderSteps(q),
            modifier = Modifier.fillMaxWidth().testTag(QaidQuestTags.RANGE).questControl(q, null, NumberText.format(value) + unit),
            colors = SliderDefaults.colors(
                thumbColor = c.accent.color(),
                activeTrackColor = c.accent.color(),
                inactiveTrackColor = c.progressTrack.color(),
                activeTickColor = c.accent.color(),
                inactiveTickColor = c.progressTrack.color(),
            ),
        )
        Row(Modifier.fillMaxWidth()) {
            Text(NumberText.format(q.min) + unit, color = c.mutedFg.color(), fontSize = ui.em(0.875f))
            Spacer(Modifier.weight(1f))
            Text(NumberText.format(q.max) + unit, color = c.mutedFg.color(), fontSize = ui.em(0.875f))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateInput(state: QuestViewState, q: DateQuestion, step: QuestStep, onChange: (QuestAnswer) -> Unit) {
    val ui = LocalQuestUi.current
    val c = ui.colors
    val text = state.text
    var open by rememberSaveable(q.id) { mutableStateOf(false) }
    val date = DateRules.parse((step.value as? QuestAnswer.Text)?.value)
    val shown = date?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(
            onClick = { open = true },
            shape = ui.metrics.inputRadius.shape(),
            color = c.inputBg.color(),
            border = BorderStroke(1.dp, if (step.error != null) c.error.color() else c.inputBorder.color()),
            modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag(QaidQuestTags.DATE).questControl(q, step.error, shown),
        ) {
            Text(
                shown ?: text.datePlaceholder,
                color = if (shown != null) c.inputFg.color() else c.inputPlaceholder.color(),
                modifier = Modifier.padding(horizontal = ui.metrics.inputPadding.horizontal.dp, vertical = ui.metrics.inputPadding.vertical.dp),
            )
        }
        if (date != null && !q.required) {
            TextButton(onClick = { onChange(QuestAnswer.None) }) { Text(text.dateClear, color = c.secondaryFg.color()) }
        }
    }
    if (open) {
        val picker = rememberDatePickerState(
            initialSelectedDateMillis = date?.let(DateRules::toUtcMillis),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = DateRules.selectable(q, utcTimeMillis)
            },
        )
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    picker.selectedDateMillis?.let { onChange(QuestAnswer.Text(DateRules.fromUtcMillis(it))) }
                    open = false
                }) { Text(text.dateConfirm) }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text(text.dateCancel) } },
        ) {
            DatePicker(state = picker)
        }
    }
}

@Composable
private fun ChoiceInput(q: ChoiceQuestion, step: QuestStep, onChange: (QuestAnswer) -> Unit) {
    val selected = when (val v = step.value) {
        is QuestAnswer.Text -> listOf(v.value)
        is QuestAnswer.Choices -> v.values
        else -> emptyList()
    }
    fun pick(option: ChoiceOption) {
        val value = option.value
        when {
            !q.multiple -> onChange(QuestAnswer.Text(value))
            value in selected -> onChange(QuestAnswer.Choices(selected - value))
            // Click order, as the web keeps it.
            else -> onChange(QuestAnswer.Choices(selected + value))
        }
    }
    val vertical = q.hasImages && q.imageAlignment == ImageAlignment.VERTICAL
    Column(
        Modifier.fillMaxWidth().selectableGroup().questControl(q, step.error),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (vertical) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val columns = (maxWidth / 140.dp).toInt().coerceAtLeast(1)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    q.options.chunked(columns).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { option ->
                                Box(Modifier.weight(1f)) { Option(q, option, option.value in selected, tile = true) { pick(option) } }
                            }
                            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        } else {
            q.options.forEach { option -> Option(q, option, option.value in selected, tile = false) { pick(option) } }
        }
    }
}

@Composable
private fun Option(q: ChoiceQuestion, option: ChoiceOption, selected: Boolean, tile: Boolean, onPick: () -> Unit) {
    val ui = LocalQuestUi.current
    val c = ui.colors
    val pad = ui.metrics.optionPadding
    val shape = ui.metrics.optionRadius.shape()
    val pickable = if (q.multiple) {
        Modifier.toggleable(value = selected, role = Role.Checkbox, onValueChange = { onPick() })
    } else {
        Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onPick)
    }
    Surface(
        shape = shape,
        color = if (selected) c.optionBgSelected.color() else c.optionBg.color(),
        contentColor = c.optionFg.color(),
        border = BorderStroke(1.dp, if (selected) c.optionBorderSelected.color() else c.optionBorder.color()),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(shape).then(pickable).testTag(QaidQuestTags.option(option.value)),
    ) {
        if (tile) {
            Box {
                Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    option.image?.let { OptionImage(it, Modifier.fillMaxWidth().aspectRatio(1f)) }
                    OptionText(option, TextAlign.Center)
                }
                Box(Modifier.align(Alignment.TopEnd).padding(12.dp)) { Marker(selected, q.multiple) }
            }
        } else {
            Row(
                Modifier.padding(vertical = pad.vertical.dp, horizontal = pad.horizontal.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Marker(selected, q.multiple)
                option.image?.let { OptionImage(it, Modifier.size(48.dp)) }
                OptionText(option, TextAlign.Start)
            }
        }
    }
}

@Composable
private fun OptionText(option: ChoiceOption, align: TextAlign) {
    val ui = LocalQuestUi.current
    Column(Modifier.padding(top = 2.dp)) {
        Text(option.label, fontWeight = FontWeight.Medium, textAlign = align, color = ui.colors.optionFg.color())
        option.description?.takeIf { it.isNotBlank() }?.let {
            Text(it, fontSize = ui.em(0.8125f), color = ui.colors.mutedFg.color(), textAlign = align)
        }
    }
}

/** The radio dot or checkbox tick, in the theme's marker colours. Decorative: the row carries the state. */
@Composable
private fun Marker(selected: Boolean, multiple: Boolean) {
    val c = LocalQuestUi.current.colors
    val shape = if (multiple) RoundedCornerShape(4.dp) else LocalQuestUi.current.metrics.markerRadius.shape()
    val fill = if (selected) c.markerBgSelected.color() else c.markerBg.color()
    val edge = if (selected) c.markerBgSelected.color() else c.markerBorder.color()
    Box(Modifier.size(20.dp).background(fill, shape).border(2.dp, edge, shape), contentAlignment = Alignment.Center) {
        if (!selected) return@Box
        val icon = c.markerIcon.color()
        if (multiple) {
            Canvas(Modifier.size(12.dp)) {
                val w = 2.dp.toPx()
                drawLine(icon, Offset(size.width * 0.1f, size.height * 0.55f), Offset(size.width * 0.4f, size.height * 0.85f), w, StrokeCap.Round)
                drawLine(icon, Offset(size.width * 0.4f, size.height * 0.85f), Offset(size.width * 0.9f, size.height * 0.2f), w, StrokeCap.Round)
            }
        } else {
            Box(Modifier.size(8.dp).background(icon, CircleShape))
        }
    }
}

/** An option's picture: decorative (`alt=""` on the web), loaded off the main thread and cached. */
@Composable
private fun OptionImage(url: String, modifier: Modifier) {
    val ui = LocalQuestUi.current
    val bitmap by produceState(ImageCache.cached(url), url) { if (value == null) value = ImageCache.load(url) }
    val shape = RoundedCornerShape(6.dp)
    Box(modifier.clip(shape).background(ui.colors.progressTrack.color(), shape)) {
        bitmap?.let {
            Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        }
    }
}
