package dev.qaid.quests.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeTest {
    private fun argb(hex: Long) = hex.toInt()

    @Test fun decodesATheme() {
        val doc = QuestThemeDoc.decode(
            """{"id":"t","preset":"pill","mode":"dark","unstyled":true,"css":".x{}",
               "tokens":{"--qaid-q-accent":"#ff0000","--qaid-q-gap":5}}""",
        )
        assertEquals("pill", doc.preset)
        assertEquals(QaidQuestTheme.Mode.DARK, doc.mode)
        assertEquals(mapOf("--qaid-q-accent" to "#ff0000"), doc.tokens)
        assertTrue(doc.ignoredCss)
        assertTrue(doc.unstyled)
        val bare = QuestThemeDoc.decode("""{"mode":"sepia","css":" "}""")
        assertNull(bare.mode)
        assertFalse(bare.ignoredCss)
        assertFalse(bare.unstyled)
        assertTrue(bare.tokens.isEmpty())
        assertEquals(QaidQuestTheme.Mode.LIGHT, QuestThemeDoc.mode("light"))
        assertEquals(QaidQuestTheme.Mode.AUTO, QuestThemeDoc.mode("auto"))
        assertEquals("Invalid theme", runCatching { QuestThemeDoc.decode("nope") }.exceptionOrNull()?.message)
    }

    @Test fun defaultsMatchTheWebStylesheet() {
        val s = QuestStyles.DEFAULT
        assertEquals(QaidQuestTheme.Mode.AUTO, s.mode)
        assertEquals(argb(0xFF10B981), s.light.accent)
        assertEquals(argb(0xFFFFFFFF), s.light.accentText)
        assertEquals(argb(0xFFEF4444), s.light.error)
        assertEquals(argb(0xFF6366F1), s.light.focus)
        assertEquals(argb(0xFFFFFFFF), s.light.cardBg)
        assertEquals(argb(0xFF1F2937), s.dark.cardBg)
        assertEquals(argb(0xFF10B981), s.dark.primaryBg)
        // color-mix(accent 10%, transparent) = accent at 10% alpha.
        assertEquals(argb(0x1A10B981), s.light.optionBgSelected)
        assertEquals(0, s.light.secondaryBg)
        val m = s.metrics
        assertEquals(16f, m.fontSize)
        assertEquals(CssRadius(16f), m.cardRadius)
        assertEquals(24f, m.cardPadding)
        assertTrue(m.cardShadow)
        assertEquals(CssRadius(8f), m.buttonRadius)
        assertEquals(Insets(10f, 18f), m.buttonPadding)
        assertEquals(500, m.buttonFontWeight)
        assertEquals(Insets(12f, 14f), m.optionPadding)
        assertEquals(CssRadius(50f, isPercent = true), m.markerRadius)
        assertEquals(12f, m.gap)
        assertEquals(20f, m.sectionGap)
        assertEquals(listOf("system-ui", "-apple-system", "sans-serif"), s.fontFamilies)
    }

    @Test fun modeChoosesColours() {
        val s = QuestStyles.DEFAULT
        assertEquals(s.light, s.colors(systemDark = false))
        assertEquals(s.dark, s.colors(systemDark = true))
        val light = QuestStyles.build(null, QaidQuestTheme(mode = QaidQuestTheme.Mode.LIGHT))
        assertFalse(light.isDark(true))
        val dark = QuestStyles.build(QuestThemeDoc(mode = QaidQuestTheme.Mode.DARK), null)
        assertTrue(dark.isDark(false))
        assertEquals(dark.dark, dark.colors(false))
    }

    @Test fun themeTokensThenPresetThenHost() {
        val doc = QuestThemeDoc(
            preset = "pill",
            tokens = mapOf(
                "--qaid-q-accent" to "#3b82f6",
                "--qaid-q-btn-radius" to "4px",
                "--qaid-q-card-bg" to "light-dark(#fafafa, #000000)",
                "--qaid-q-gap" to "  ",
                "--qaid-q-input-border" to "not a colour",
            ),
        )
        val s = QuestStyles.build(doc, null)
        // Derived tokens follow the theme's accent.
        assertEquals(argb(0xFF3B82F6), s.light.primaryBg)
        assertEquals(argb(0xFF3B82F6), s.light.optionBorderSelected)
        // A theme accent does not recompute the button text (web quirk): still white.
        assertEquals(argb(0xFFFFFFFF), s.light.primaryFg)
        // The preset beats the theme's own token for the same key.
        assertEquals(CssRadius(9999f), s.metrics.buttonRadius)
        assertEquals(Insets(12f, 18f), s.metrics.inputPadding)
        assertEquals(argb(0xFFFAFAFA), s.light.cardBg)
        assertEquals(argb(0xFF000000), s.dark.cardBg)
        assertEquals(12f, s.metrics.gap) // blank token skipped
        assertEquals(argb(0xFFD1D5DB), s.light.inputBorder) // unreadable value falls back

        val host = QuestStyles.build(
            doc,
            QaidQuestTheme(
                accent = argb(0xFFFDE047), error = argb(0xFF000001), focus = argb(0xFF000002),
                fontFamily = "\"Georgia\", serif", fontSize = 20f, cornerRadius = 3f,
                mode = QaidQuestTheme.Mode.LIGHT, preset = QaidQuestTheme.Preset.MINIMAL,
            ),
        )
        assertEquals(argb(0xFFFDE047), host.light.primaryBg)
        assertEquals(argb(0xFF000000), host.light.primaryFg) // light accent → black text
        assertEquals(argb(0xFF000001), host.light.error)
        assertEquals(argb(0xFF000002), host.light.focus)
        assertEquals(listOf("Georgia", "serif"), host.fontFamilies)
        assertEquals(20f, host.metrics.fontSize)
        assertEquals(CssRadius(3f), host.metrics.cardRadius)
        assertEquals(CssRadius(3f), host.metrics.optionRadius)
        assertFalse(host.metrics.cardShadow) // minimal
        assertEquals(0, host.light.optionBgSelected) // minimal: transparent
        assertEquals(QaidQuestTheme.Mode.LIGHT, host.mode)
        assertEquals(argb(0xFFFFFFFF), QuestStyles.build(null, QaidQuestTheme(accent = argb(0xFF1E3A8A))).light.primaryFg)
    }

    @Test fun densePresetAndEmLengths() {
        val s = QuestStyles.build(QuestThemeDoc(preset = "dense", tokens = mapOf("--qaid-font-size" to "1.25rem", "--qaid-q-gap" to "1em")), null)
        assertEquals(20f, s.metrics.fontSize)
        assertEquals(16f, s.metrics.cardPadding)
        assertEquals(8f, s.metrics.gap) // the preset's 0.5rem beats the theme's 1em
        val em = QuestStyles.build(QuestThemeDoc(tokens = mapOf("--qaid-font-size" to "20px", "--qaid-q-section-gap" to "2em")), null)
        assertEquals(40f, em.metrics.sectionGap)
        assertEquals(16f, QuestStyles.build(QuestThemeDoc(preset = "unknown", tokens = mapOf("--qaid-font-size" to "huge")), null).metrics.fontSize)
    }

    @Test fun resolvesVarAndLightDark() {
        val tokens = mapOf("--a" to "var(--b)", "--b" to "light-dark(red, blue)", "--loop" to "var(--loop)", "--blank" to " ")
        assertEquals("red", Css.resolve("var(--a)", tokens, dark = false))
        assertEquals("blue", Css.resolve("var(--a)", tokens, dark = true))
        assertEquals("1px solid red", Css.resolve("1px solid var(--a)", tokens, false))
        assertEquals("#fff", Css.resolve("var(--missing, #fff)", tokens, false))
        assertEquals("#fff", Css.resolve("var(--blank, #fff)", tokens, false))
        assertEquals("", Css.resolve("var(--missing)", tokens, false))
        assertEquals("", Css.resolve("var(--loop)", tokens, false))
        assertEquals("only", Css.resolve("light-dark(only)", tokens, true))
        assertEquals("var(--a", Css.resolve("var(--a", tokens, false))
        assertEquals("rgba(1, 2, 3, 0.5)", Css.resolve("var(--x, rgba(1, 2, 3, 0.5))", tokens, false))
        assertEquals("myvar(--a)", Css.resolve("myvar(--a)", tokens, false))
        val many = (1..70).joinToString(" ") { "var(--b)" }
        assertTrue(Css.resolve(many, tokens, false).contains("var(--b)"))
    }

    @Test fun colours() {
        assertEquals(argb(0xFFFF0000), Css.color("#f00"))
        assertEquals(argb(0x88FF0000), Css.color("#f008"))
        assertEquals(argb(0xFF10B981), Css.color(" #10B981 "))
        assertEquals(argb(0x8010B981), Css.color("#10b98180"))
        assertNull(Css.color("#12345"))
        assertNull(Css.color("#1234567"))
        assertNull(Css.color("#ggg"))
        assertEquals(argb(0xFF0A141E), Css.color("rgb(10, 20, 30)"))
        assertEquals(argb(0x800A141E), Css.color("rgba(10,20,30,0.5)"))
        assertEquals(argb(0x800A141E), Css.color("rgb(10 20 30 / 50%)"))
        assertEquals(argb(0xFFFF0000), Css.color("rgb(100%, 0%, 0%)"))
        assertEquals(argb(0xFFFF0000), Css.color("rgb(300, -5, 0)"))
        assertNull(Css.color("rgb(1, 2)"))
        assertNull(Css.color("rgb(a, 2, 3)"))
        assertNull(Css.color("rgb(x%, 2, 3)"))
        assertNull(Css.color("rgba(1, 2, 3, z)"))
        assertNull(Css.color("rgba(1, 2, 3, z%)"))
        assertEquals(0, Css.color("transparent"))
        assertEquals(argb(0xFF000000), Css.color("BLACK"))
        assertNull(Css.color("chartreuse"))
    }

    @Test fun colorMix() {
        assertEquals(argb(0x1A10B981), Css.color("color-mix(in srgb, #10b981 10%, transparent)"))
        assertEquals(argb(0x1A10B981), Css.color("color-mix(in  srgb, 10% #10b981, transparent)"))
        assertEquals(argb(0xFF808080), Css.color("color-mix(in srgb, white, black)"))
        assertEquals(argb(0xFFBFBFBF), Css.color("color-mix(in srgb, white, black 25%)"))
        assertEquals(argb(0xFF404040), Css.color("color-mix(in srgb, white 25%, black)"))
        // Percentages summing under 100 scale the alpha.
        assertEquals(argb(0x80808080), Css.color("color-mix(in srgb, white 25%, black 25%)"))
        assertEquals(0, Css.color("color-mix(in srgb, transparent, transparent)"))
        assertNull(Css.color("color-mix(in srgb, white 0%, black 0%)"))
        assertNull(Css.color("color-mix(in oklch, white, black)"))
        assertNull(Css.color("color-mix(in srgb, white)"))
        assertNull(Css.color("color-mix(in srgb, nope 10%, black)"))
        assertNull(Css.color("color-mix(in srgb, white, nope)"))
        assertNull(Css.color("color-mix(in srgb, white x%, black)"))
        assertNull(Css.color("color-mix(in srgb, 50%, black)"))
        assertNull(Css.color("color-mix(in srgb, white, black"))
    }

    @Test fun lengthsAndShapes() {
        assertEquals(0f, Css.length("0", 16f))
        assertEquals(12f, Css.length("12px", 16f))
        assertEquals(24f, Css.length("1.5rem", 16f))
        assertEquals(10f, Css.length(".5em", 20f))
        assertNull(Css.length("12", 16f))
        assertNull(Css.length("auto", 16f))
        assertEquals(CssRadius(50f, true), Css.radius("50%", 16f))
        assertNull(Css.radius("x%", 16f))
        assertNull(Css.radius("big", 16f))
        assertEquals(Insets(8f, 8f), Css.padding("0.5rem", 16f))
        assertEquals(Insets(8f, 4f), Css.padding("8px 4px 2px", 16f))
        assertNull(Css.padding("x", 16f))
        assertNull(Css.padding("8px y", 16f))
        assertEquals(400, Css.fontWeight("normal"))
        assertEquals(700, Css.fontWeight("Bold"))
        assertEquals(600, Css.fontWeight("600"))
        assertNull(Css.fontWeight("2000"))
        assertNull(Css.fontWeight("0"))
        assertNull(Css.fontWeight("heavy"))
        assertEquals(listOf("Inter", "Helvetica Neue", "sans-serif"), Css.fontFamilies("Inter, 'Helvetica Neue', sans-serif, "))
        assertFalse(Css.hasShadow("none"))
        assertFalse(Css.hasShadow(" "))
        assertTrue(Css.hasShadow("0 1px 2px black"))
    }

    @Test fun helpers() {
        assertEquals("#10b981ff", Css.hex(argb(0xFF10B981)))
        assertEquals("#00000000", Css.hex(0))
        assertEquals(argb(0xFF000000), Css.contrastText(argb(0xFFFFFFFF)))
        assertEquals(argb(0xFFFFFFFF), Css.contrastText(argb(0xFF000000)))
        assertEquals(1.0, Css.luminance(argb(0xFFFFFFFF)), 1e-9)
        assertEquals(0.0, Css.luminance(argb(0xFF000000)), 1e-9)
        assertEquals(listOf("a", "f(b, c)", "d"), Css.splitArgs("a, f(b, c) ,d"))
        assertNull(Css.closingParen("f(a", 1))
    }

    @Test fun everyPresetHasItsStylesheet() {
        assertEquals(
            listOf("default", "minimal", "pill", "dense"),
            QaidQuestTheme.Preset.entries.map { it.cssName },
        )
        QaidQuestTheme.Preset.entries.forEach { assertTrue(it.cssName in QuestStyles.PRESETS) }
        val dense = QuestStyles.build(QuestThemeDoc(preset = "pill"), QaidQuestTheme(preset = QaidQuestTheme.Preset.DENSE))
        assertEquals(QuestStyles.build(QuestThemeDoc(preset = "dense"), null), dense)
    }
}
