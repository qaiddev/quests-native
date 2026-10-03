package dev.qaid.quests.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisibilityTest {
    private fun rule(json: String) = VisibilityRule.decode(JSONObject(json))
    private fun eval(json: String, vararg answers: Pair<String, QuestAnswer>) = Visibility.evaluate(rule(json), mapOf(*answers))
    private val t = { s: String -> QuestAnswer.Text(s) }
    private val n = { d: Double -> QuestAnswer.Number(d) }

    @Test fun decodesInTheWebsKeyOrder() {
        assertEquals(VisibilityRule.AllOf(listOf(VisibilityRule.Never)), rule("""{"allOf":[{}],"anyOf":[]}"""))
        assertEquals(VisibilityRule.AnyOf(emptyList()), rule("""{"anyOf":"not a list"}"""))
        assertEquals(VisibilityRule.Answered("a", false), rule("""{"questionId":"a","answered":false,"equals":"x"}"""))
        assertEquals(VisibilityRule.Equals("a", RuleValue.Str("x")), rule("""{"questionId":"a","equals":"x","notEquals":"y"}"""))
        assertEquals(VisibilityRule.NotEquals("a", RuleValue.Num(2.0)), rule("""{"questionId":"a","notEquals":2}"""))
        assertEquals(VisibilityRule.In(null, listOf(RuleValue.Str("x"), RuleValue.Num(1.0), RuleValue.Other)), rule("""{"in":["x",1,true]}"""))
        assertEquals(VisibilityRule.Never, rule("""{"questionId":"a"}"""))
        assertEquals(VisibilityRule.Never, VisibilityRule.decode("not an object"))
    }

    @Test fun answeredUsesJavaScriptTruthiness() {
        assertTrue(VisibilityRule.truthy(true))
        assertFalse(VisibilityRule.truthy(false))
        assertTrue(VisibilityRule.truthy(1))
        assertFalse(VisibilityRule.truthy(0))
        assertFalse(VisibilityRule.truthy(Double.NaN))
        assertTrue(VisibilityRule.truthy("yes"))
        assertFalse(VisibilityRule.truthy(""))
        assertFalse(VisibilityRule.truthy(null))
        assertFalse(VisibilityRule.truthy(JSONObject.NULL))
        assertTrue(VisibilityRule.truthy(JSONObject()))
    }

    @Test fun allOfAndAnyOf() {
        assertTrue(eval("""{"allOf":[]}"""))
        assertFalse(eval("""{"anyOf":[]}"""))
        assertTrue(eval("""{"allOf":[{"questionId":"a","equals":"x"},{"questionId":"b","answered":false}]}""", "a" to t("x")))
        assertFalse(eval("""{"allOf":[{"questionId":"a","equals":"x"},{"questionId":"b","answered":true}]}""", "a" to t("x")))
        assertTrue(eval("""{"anyOf":[{"questionId":"a","equals":"y"},{"questionId":"a","equals":"x"}]}""", "a" to t("x")))
        assertFalse(eval("""{"anyOf":[{"questionId":"a","equals":"y"}]}""", "a" to t("x")))
    }

    @Test fun answered() {
        assertTrue(eval("""{"questionId":"a","answered":true}""", "a" to n(0.0)))
        assertFalse(eval("""{"questionId":"a","answered":true}""", "a" to t("   ")))
        assertTrue(eval("""{"questionId":"a","answered":false}""", "a" to QuestAnswer.Choices(emptyList())))
        assertTrue(eval("""{"questionId":"missing","answered":false}"""))
        assertTrue(eval("""{"answered":false}"""))
    }

    @Test fun comparisonsAgainstUnansweredAreFalseEvenNotEquals() {
        assertFalse(eval("""{"questionId":"a","notEquals":"x"}"""))
        assertFalse(eval("""{"questionId":"a","notEquals":"x"}""", "a" to t(" ")))
        assertFalse(eval("""{"questionId":"a","equals":"x"}""", "a" to QuestAnswer.None))
        assertFalse(eval("""{"questionId":"a","in":["x"]}"""))
    }

    @Test fun strictEqualityWithoutCoercionOrTrimming() {
        assertTrue(eval("""{"questionId":"a","equals":5}""", "a" to n(5.0)))
        assertFalse(eval("""{"questionId":"a","equals":"5"}""", "a" to n(5.0)))
        assertFalse(eval("""{"questionId":"a","equals":5}""", "a" to t("5")))
        assertFalse(eval("""{"questionId":"a","equals":"x"}""", "a" to t("x ")))
        assertFalse(eval("""{"questionId":"a","equals":true}""", "a" to t("true")))
        assertTrue(eval("""{"questionId":"a","notEquals":"y"}""", "a" to t("x")))
        assertFalse(eval("""{"questionId":"a","notEquals":"x"}""", "a" to t("x")))
        assertTrue(eval("""{"questionId":"a","in":[1,"x"]}""", "a" to t("x")))
        assertFalse(eval("""{"questionId":"a","in":"x"}""", "a" to t("x")))
        assertTrue(Visibility.matches(n(0.0), RuleValue.Num(-0.0)))
        assertFalse(Visibility.matches(n(Double.NaN), RuleValue.Num(Double.NaN)))
        assertFalse(Visibility.matches(QuestAnswer.None, RuleValue.Str("")))
    }

    @Test fun multiSelectMatchesAnyPickedValue() {
        val picked = QuestAnswer.Choices(listOf("a", "b"))
        assertTrue(eval("""{"questionId":"m","equals":"b"}""", "m" to picked))
        assertFalse(eval("""{"questionId":"m","equals":"c"}""", "m" to picked))
        assertFalse(eval("""{"questionId":"m","notEquals":"a"}""", "m" to picked))
        assertTrue(eval("""{"questionId":"m","in":["z","a"]}""", "m" to picked))
        assertFalse(Visibility.matches(picked, RuleValue.Num(1.0)))
    }

    @Test fun hiddenQuestionsReadAsUnansweredSoChainsCollapse() {
        val questions = listOf(
            choice("a", "yes", "no"),
            text("b", rule = VisibilityRule.Equals("a", RuleValue.Str("yes"))),
            text("c", rule = VisibilityRule.Answered("b", true)),
            text("d", rule = VisibilityRule.Answered("later", true)),
            text("later"),
        )
        val answers = mapOf("a" to QuestAnswer.Text("no"), "b" to QuestAnswer.Text("typed"), "later" to QuestAnswer.Text("x"))
        assertEquals(listOf("a", "later"), Visibility.visibleQuestions(questions, answers).map { it.id })
        assertEquals(mapOf("a" to QuestAnswer.Text("no"), "later" to QuestAnswer.Text("x")), Visibility.visibleAnswers(questions, answers))
        val shown = answers + ("a" to QuestAnswer.Text("yes"))
        assertEquals(listOf("a", "b", "c", "later"), Visibility.visibleQuestions(questions, shown).map { it.id })
        assertEquals(setOf("a", "b", "later"), Visibility.visibleAnswers(questions, shown).keys)
    }
}
