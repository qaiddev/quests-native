package dev.qaid.quests.ui

import android.graphics.Typeface
import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.qaid.quests.core.CssRadius
import dev.qaid.quests.core.QuestColors
import dev.qaid.quests.core.QuestMetrics
import dev.qaid.quests.core.QuestStyle

/** The resolved [QuestStyle] for the current system mode, as the composables read it. */
internal class QuestUi(
    val colors: QuestColors,
    val metrics: QuestMetrics,
    val font: FontFamily,
    val reduceMotion: Boolean,
) {
    /** A size in `em` of the base text size, as the web's stylesheet sizes text. */
    fun em(value: Float): TextUnit = (metrics.fontSize * value).sp

    val buttonWeight: FontWeight get() = FontWeight(metrics.buttonFontWeight.coerceIn(1, 1000))
}

internal val LocalQuestUi = staticCompositionLocalOf<QuestUi> { error("no quest theme") }

internal fun Int.color(): Color = Color(this)

internal fun CssRadius.shape(): RoundedCornerShape =
    if (isPercent) RoundedCornerShape(value.coerceIn(0f, 50f).toInt()) else RoundedCornerShape(value.dp)

/** Only the top corners, for the presented sheet. */
internal fun CssRadius.topShape(): RoundedCornerShape {
    val corner = if (isPercent) CornerSize(value.coerceIn(0f, 50f).toInt()) else CornerSize(value.dp)
    return RoundedCornerShape(topStart = corner, topEnd = corner, bottomStart = CornerSize(0.dp), bottomEnd = CornerSize(0.dp))
}

@Composable
internal fun ProvideQuestTheme(style: QuestStyle, content: @Composable () -> Unit) {
    val dark = style.isDark(isSystemInDarkTheme())
    val colors = style.colors(dark)
    val context = LocalContext.current
    val font = remember(style.fontFamilies) { fontFamily(style.fontFamilies) }
    // Reduced motion on Android is "Remove animations": the animator scale set to 0.
    val reduceMotion = remember {
        runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
            .getOrDefault(false)
    }
    val base = if (dark) darkColorScheme() else lightColorScheme()
    val scheme = base.copy(
        primary = colors.accent.color(),
        onPrimary = colors.primaryFg.color(),
        error = colors.error.color(),
        surface = colors.cardBg.color(),
        onSurface = colors.cardFg.color(),
        onSurfaceVariant = colors.mutedFg.color(),
        surfaceContainerHigh = colors.cardBg.color(),
        surfaceContainerHighest = colors.progressTrack.color(),
        outline = colors.inputBorder.color(),
    )
    val ui = QuestUi(colors, style.metrics, font, reduceMotion)
    val text = TextStyle(fontFamily = font, fontSize = ui.em(1f), color = colors.cardFg.color())
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(LocalQuestUi provides ui, LocalTextStyle provides text, content = content)
    }
}

/** The first family in the list Android can draw; CSS generic names map to Android's. */
internal fun fontFamily(names: List<String>): FontFamily {
    for (name in names) {
        when (name.lowercase()) {
            "serif", "ui-serif" -> return FontFamily.Serif
            "monospace", "ui-monospace" -> return FontFamily.Monospace
            "cursive" -> return FontFamily.Cursive
            "system-ui", "-apple-system", "ui-sans-serif", "sans-serif", "blinkmacsystemfont", "roboto" -> return FontFamily.Default
        }
        val typeface = Typeface.create(name, Typeface.NORMAL)
        if (typeface != Typeface.DEFAULT) return FontFamily(typeface)
    }
    return FontFamily.Default
}
