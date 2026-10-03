package dev.qaid.quests.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuestSessionTest {
    private val effects = RecordingEffects()
    private val text = QaidQuestText()
    private fun session(q: Questionnaire) = QuestSession(q, text, effects)

    private val branching = quest(
        choice("role", "dev", "pm", required = true),
        text("why", rule = VisibilityRule.Equals("role", RuleValue.Str("dev"))),
        text("last"),
    )

    @Test fun walksForwardAndBackWithLabels() {
        val s = session(branching)
        assertEquals("role", s.current.id)
        assertFalse(s.canGoBack)
        assertEquals("Next", s.primaryLabel)
        assertEquals("Back", s.backLabel)
        assertEquals(1f / 2, s.progressFraction)
        assertTrue(s.showsProgress)

        assertEquals(QuestSession.Nav.INVALID, s.next())
        assertEquals("Please select an option.", s.error)
        s.setAnswer("role", QuestAnswer.Text("dev"))
        assertNull(s.error)
        assertEquals(listOf("role", "why", "last"), s.visible.map { it.id })
        assertEquals(QuestSession.Nav.MOVED, s.next())
        assertEquals("why", s.current.id)
        assertEquals(2f / 3, s.progressFraction)
        assertTrue(s.canGoBack)
        assertEquals(QuestSession.Nav.MOVED, s.next())
        assertTrue(s.isLast)
        assertEquals("Submit", s.primaryLabel)
        assertEquals(QuestSession.Nav.SUBMIT, s.next())

        assertTrue(s.back())
        assertEquals("why", s.current.id)
        assertTrue(s.back())
        assertFalse(s.back())
        assertEquals(3, effects.flushes)
    }

    @Test fun savesAreImmediateExceptTyping() {
        val s = session(quest(text("t"), CurrencyQuestion("c", "C"), DateQuestion("d", "D"), choice("m", "a")))
        s.setAnswer("t", QuestAnswer.Text("x"))
        s.setAnswer("c", QuestAnswer.Number(1.0))
        s.setAnswer("d", QuestAnswer.Text("2024-01-01"))
        s.setAnswer("m", QuestAnswer.Text("a"))
        s.setAnswer("nope", QuestAnswer.Text("ignored"))
        assertEquals(listOf(false, false, true, true), effects.saves.map { it.third })
        assertFalse(s.hasAnswer("nope"))
    }

    @Test fun nextSavesEveryVisitedQuestionWithItsEmptyValue() {
        val s = session(quest(text("t"), choice("single", "a"), choice("multi", "a", multiple = true), DateQuestion("d", "D")))
        repeat(3) { s.next() }
        assertEquals(
            listOf(
                Triple("t", QuestAnswer.Text(""), true),
                Triple("single", QuestAnswer.None, true),
                Triple("multi", QuestAnswer.Choices(emptyList()), true),
            ),
            effects.saves,
        )
        assertEquals(mapOf("t" to QuestAnswer.Text(""), "single" to QuestAnswer.None, "multi" to QuestAnswer.Choices(emptyList())), s.submittedAnswers())
    }

    @Test fun rangeIsAnsweredWhenShown() {
        val range = RangeQuestion("r", "R", min = 0.0, max = 10.0, defaultValue = 4.0)
        val s = session(quest(text("t"), range, text("follow", rule = VisibilityRule.Answered("r", true))))
        assertFalse(s.hasAnswer("r"))
        assertEquals(QuestAnswer.Number(4.0), s.value(range))
        s.next()
        assertTrue(s.hasAnswer("r"))
        assertEquals(Triple("r", QuestAnswer.Number(4.0), false), effects.saves.last())
        assertEquals(3, s.visible.size)
        s.back()
        s.next()
        // Already answered: not pushed again.
        assertEquals(1, effects.saves.count { it.first == "r" && !it.third })
    }

    @Test fun hiddenAnswersAreLeftOutAndCleared() {
        val s = session(branching)
        s.setAnswer("role", QuestAnswer.Text("dev"))
        s.next()
        s.setAnswer("why", QuestAnswer.Text("because"))
        s.back()
        s.setAnswer("role", QuestAnswer.Text("pm"))
        assertEquals(listOf("role", "last"), s.visible.map { it.id })
        assertEquals(mapOf("role" to QuestAnswer.Text("pm")), s.submittedAnswers())
        assertEquals(listOf("why"), s.hiddenAnswered())
        s.setAnswer("why", QuestAnswer.None)
        assertTrue(s.hiddenAnswered().isEmpty())
    }

    @Test fun unsupportedQuestionsNeverBlockAndAreNeverAnswered() {
        val s = session(quest(UnknownQuestion("u", "U", required = true, type = "signature"), text("t")))
        s.setAnswer("u", QuestAnswer.Text("x"))
        assertFalse(s.hasAnswer("u"))
        assertEquals(QuestSession.Nav.MOVED, s.next())
        assertTrue(effects.saves.isEmpty())
    }

    @Test fun questLabelsWinAndProgressHidesOnRawCount() {
        val custom = quest(text("a"), text("b", rule = VisibilityRule.Never)) {
            it.copy(nextLabel = "On", submitLabel = "Send", backLabel = "Prev", hideProgress = false)
        }
        val s = session(custom)
        // The second question is hidden, so the first is last: "1 / 1", still shown (raw count 2).
        assertEquals("Send", s.primaryLabel)
        assertEquals("Prev", s.backLabel)
        assertTrue(s.showsProgress)
        assertEquals(1f, s.progressFraction)
        assertFalse(session(quest(text("a"))).showsProgress)
        assertFalse(session(quest(text("a"), text("b")) { it.copy(hideProgress = true) }).showsProgress)
        assertEquals("On", session(quest(text("a"), text("b")) { it.copy(nextLabel = "On") }).primaryLabel)
    }

    @Test fun validationErrorClearsOnChangeAndOnMove() {
        val s = session(quest(text("a", required = true), text("b")))
        s.next()
        assertEquals("This field is required.", s.error)
        s.setAnswer("a", QuestAnswer.Text("x"))
        assertNull(s.error)
        s.next()
        assertNull(s.error)
    }
}
