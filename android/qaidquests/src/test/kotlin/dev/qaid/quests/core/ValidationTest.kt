package dev.qaid.quests.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

class ValidationTest {
    private val text = QaidQuestText()
    private fun msg(q: Question, v: QuestAnswer) = QuestValidation.message(q, v, text)

    @Test fun textMessagesMatchTheWeb() {
        assertNull(msg(text("t"), QuestAnswer.Text("")))
        assertNull(msg(text("t", minLength = 5), QuestAnswer.Text("ab")))
        assertEquals("This field is required.", msg(text("t", required = true), QuestAnswer.Text("  ")))
        assertEquals("This field is required.", msg(text("t", required = true, minLength = 0), QuestAnswer.None))
        assertNull(msg(text("t", required = true), QuestAnswer.Text("a")))
        // An empty required field with a minimum gets the length message, as on the web.
        assertEquals("Please enter at least 3 characters.", msg(text("t", required = true, minLength = 3), QuestAnswer.Text("")))
        assertEquals("Please enter at least 3 characters.", msg(text("t", required = true, minLength = 3), QuestAnswer.Text(" ab ")))
        assertNull(msg(text("t", required = true, minLength = 3), QuestAnswer.Text("abc")))
    }

    @Test fun currency() {
        val q = CurrencyQuestion("c", "C", min = 1.0, max = 10.0)
        assertNull(msg(q, QuestAnswer.None))
        assertNull(msg(q, QuestAnswer.Number(1.0)))
        assertNull(msg(q, QuestAnswer.Number(10.0)))
        assertEquals("Please enter a valid amount.", msg(q, QuestAnswer.Number(0.5)))
        assertEquals("Please enter a valid amount.", msg(q, QuestAnswer.Number(10.5)))
        assertEquals("Please enter a valid amount.", msg(q.copy(required = true), QuestAnswer.None))
        assertNull(msg(CurrencyQuestion("c", "C", required = true), QuestAnswer.Number(-3.0)))
    }

    @Test fun dateChoiceRangeAndUnknown() {
        assertEquals("Please pick a date.", msg(DateQuestion("d", "D", required = true), QuestAnswer.None))
        assertNull(msg(DateQuestion("d", "D", required = true), QuestAnswer.Text("2024-01-01")))
        assertNull(msg(DateQuestion("d", "D"), QuestAnswer.None))
        assertEquals("Please select an option.", msg(choice("m", "a", required = true), QuestAnswer.None))
        assertEquals("Please select at least one option.", msg(choice("m", "a", required = true, multiple = true), QuestAnswer.Choices(emptyList())))
        assertNull(msg(choice("m", "a", required = true), QuestAnswer.Text("a")))
        assertNull(msg(choice("m", "a"), QuestAnswer.None))
        assertNull(msg(RangeQuestion("r", "R", required = true, min = 0.0, max = 1.0), QuestAnswer.None))
        assertNull(msg(UnknownQuestion("u", "U", required = true, type = "x"), QuestAnswer.None))
    }

    @Test fun messagesAreOverridable() {
        val de = QaidQuestText(errorRequired = "Pflichtfeld.", errorMinLength = "Mindestens {n} Zeichen.")
        assertEquals("Pflichtfeld.", QuestValidation.message(text("t", required = true), QuestAnswer.Text(""), de))
        assertEquals("Mindestens 4 Zeichen.", QuestValidation.message(text("t", required = true, minLength = 4), QuestAnswer.Text(""), de))
        assertEquals("Question 2 of 5", text.fill(text.progressValue, 2, 5))
    }

    @Test fun textRules() {
        assertEquals("abc", TextRules.clip("abcdef", 3))
        assertEquals("ab", TextRules.clip("ab", 3))
        assertEquals("abcdef", TextRules.clip("abcdef", null))
    }

    @Test fun currencyParsingAndSymbols() {
        assertNull(CurrencyRules.parse(""))
        assertNull(CurrencyRules.parse("abc"))
        assertNull(CurrencyRules.parse("1e999"))
        assertEquals(12.5, CurrencyRules.parse("12.5")!!, 0.0)
        assertEquals(12.0, CurrencyRules.parse("12.")!!, 0.0)
        assertEquals(12.5, CurrencyRules.parse("12,5")!!, 0.0)
        assertEquals("-12.50", CurrencyRules.sanitize("-1a2.5.0"))
        assertEquals("12,5", CurrencyRules.sanitize("12,5,"))
        assertEquals("15", CurrencyRules.sanitize("1-5"))
        assertEquals("$", CurrencyRules.symbol("usd", Locale.GERMANY))
        assertEquals("€", CurrencyRules.symbol("EUR", Locale.US))
        assertEquals("CHF", CurrencyRules.symbol("CHF", Locale.US))
        assertEquals("XYZ", CurrencyRules.symbol("XYZ", Locale.US))
        assertEquals(Locale.GERMANY, CurrencyRules.locale("de-DE", Locale.US))
        assertEquals(Locale.US, CurrencyRules.locale(null, Locale.US))
        assertEquals(Locale.US, CurrencyRules.locale("", Locale.US))
        assertEquals(Locale.US, CurrencyRules.locale("!!", Locale.US))
        assertEquals("12.5", CurrencyRules.display(12.5))
        assertEquals("12", CurrencyRules.display(12.0))
        assertEquals("", CurrencyRules.display(null))
    }

    @Test fun rangeSnapsLikeABrowser() {
        val q = RangeQuestion("r", "R", min = 0.0, max = 10.0, step = 2.0)
        assertEquals(0.0, RangeRules.initial(q), 0.0)
        assertEquals(6.0, RangeRules.initial(q.copy(defaultValue = 5.0)), 0.0) // ties round up
        assertEquals(4.0, RangeRules.snap(q, 4.9), 0.0)
        assertEquals(10.0, RangeRules.snap(q, 99.0), 0.0)
        assertEquals(0.0, RangeRules.snap(q, -5.0), 0.0)
        // max off the grid: the top value is the last step under it.
        assertEquals(9.0, RangeRules.snap(RangeQuestion("r", "R", min = 0.0, max = 10.0, step = 3.0), 10.0), 0.0)
        assertEquals(0.3, RangeRules.snap(RangeQuestion("r", "R", min = 0.0, max = 1.0, step = 0.1), 0.3), 0.0)
        assertEquals(4, RangeRules.sliderSteps(q))
        assertEquals(0, RangeRules.sliderSteps(q.copy(step = 3.0)))
        assertEquals(0, RangeRules.sliderSteps(q.copy(step = 0.001)))
        assertEquals(0, RangeRules.sliderSteps(q.copy(step = 20.0)))
    }

    @Test fun numbersPrintAsJavaScriptDoes() {
        assertEquals("5", NumberText.format(5.0))
        assertEquals("-3", NumberText.format(-3.0))
        assertEquals("2.5", NumberText.format(2.5))
        assertEquals("0.1", NumberText.format(0.1))
        assertEquals("10000000000000000", NumberText.format(1e16))
    }

    @Test fun dates() {
        assertEquals(LocalDate.of(2024, 2, 29), DateRules.parse("2024-02-29"))
        assertNull(DateRules.parse("2023-02-29"))
        assertNull(DateRules.parse(null))
        val millis = DateRules.toUtcMillis(LocalDate.of(2024, 3, 1))
        assertEquals("2024-03-01", DateRules.fromUtcMillis(millis))
        val q = DateQuestion("d", "D", min = "2024-01-01", max = "2024-12-31")
        assertTrue(DateRules.selectable(q, millis))
        assertFalse(DateRules.selectable(q, DateRules.toUtcMillis(LocalDate.of(2023, 12, 31))))
        assertFalse(DateRules.selectable(q, DateRules.toUtcMillis(LocalDate.of(2025, 1, 1))))
        assertTrue(DateRules.selectable(DateQuestion("d", "D", min = "bad"), millis))
    }
}
