package dev.qaid.quests.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelTest {
    @Test fun decodesTheDefinitionAndIgnoresUnknownFields() {
        val q = QuestDecoder.questionnaire(
            """{"id":"abc","slug":"s","versionId":"v","versionNumber":3,"title":"T","description":"D",
               "hideTitle":true,"hideProgress":true,"progressPosition":"bottom","thankYouTitle":"Ta","thankYouMessage":"M",
               "submitLabel":"Go","nextLabel":"On","backLabel":"Prev","futureField":{"x":1},
               "questions":[{"id":"a","label":"A","type":"text"}]}""",
            "fallback",
        )
        assertEquals("abc", q.id)
        assertEquals("s", q.slug)
        assertEquals("v", q.versionId)
        assertEquals(3, q.versionNumber)
        assertEquals("T", q.title)
        assertEquals("D", q.description)
        assertTrue(q.hideTitle)
        assertTrue(q.hideProgress)
        assertEquals(QaidQuestProgressPosition.BOTTOM, q.progressPosition)
        assertEquals("Ta", q.thankYouTitle)
        assertEquals("M", q.thankYouMessage)
        assertEquals("Go", q.submitLabel)
        assertEquals("On", q.nextLabel)
        assertEquals("Prev", q.backLabel)
    }

    @Test fun missingIdFallsBackAndDefaultsApply() {
        val q = QuestDecoder.questionnaire("""{"questions":[{"id":"a","label":"A","type":"date"}],"hideTitle":"yes"}""", "fb")
        assertEquals("fb", q.id)
        assertFalse(q.hideTitle)
        assertNull(q.progressPosition)
        assertNull(q.versionNumber)
        assertEquals(QaidQuestProgressPosition.TOP, QuestDecoder.progressPosition("top"))
    }

    @Test fun badBodiesFailWithTheWebMessages() {
        fun message(raw: String) = runCatching { QuestDecoder.questionnaire(raw, "x") }.exceptionOrNull()?.message
        assertEquals("Invalid questionnaire", message("not json"))
        assertEquals("Questionnaire is empty", message("""{"questions":[]}"""))
        assertEquals("Questionnaire is empty", message("""{"title":"no questions"}"""))
        assertEquals("Questionnaire is empty", message("""{"questions":[{"label":"no id","type":"text"}, 5]}"""))
    }

    @Test fun textOptions() {
        val q = question("""{"id":"t","label":"L","type":"text","placeholder":"P","multiline":true,"maxLength":10,"minLength":2,"inputType":"email","required":true,"description":"d"}""") as TextQuestion
        assertEquals(TextQuestion("t", "L", "d", true, null, "P", true, 10, 2, TextInputType.EMAIL), q)
        assertEquals(TextInputType.TEL, (question("""{"id":"t","type":"text","inputType":"tel"}""") as TextQuestion).inputType)
        assertEquals(TextInputType.URL, (question("""{"id":"t","type":"text","inputType":"url"}""") as TextQuestion).inputType)
        val plain = question("""{"id":"t","type":"text","inputType":"bogus","maxLength":0,"minLength":-1}""") as TextQuestion
        assertEquals(TextInputType.TEXT, plain.inputType)
        assertNull(plain.maxLength)
        assertNull(plain.minLength)
        assertEquals("", plain.label)
    }

    @Test fun currencyOptions() {
        val q = question("""{"id":"c","label":"L","type":"currency","currency":"EUR","locale":"de-DE","min":1,"max":9.5,"placeholder":"p"}""") as CurrencyQuestion
        assertEquals("EUR", q.currency)
        assertEquals("de-DE", q.locale)
        assertEquals(1.0, q.min!!, 0.0)
        assertEquals(9.5, q.max!!, 0.0)
        val bare = question("""{"id":"c","type":"currency","currency":" ","locale":""}""") as CurrencyQuestion
        assertEquals("USD", bare.currency)
        assertEquals("USD", (question("""{"id":"c","type":"currency"}""") as CurrencyQuestion).currency)
        assertNull(bare.locale)
    }

    @Test fun rangeOptionsAndUnusableRanges() {
        val q = question("""{"id":"r","type":"range","min":1,"max":5,"step":0.5,"defaultValue":2,"unit":"kg"}""") as RangeQuestion
        assertEquals(RangeQuestion("r", "", min = 1.0, max = 5.0, step = 0.5, defaultValue = 2.0, unit = "kg"), q)
        assertEquals(1.0, (question("""{"id":"r","type":"range","min":1,"max":5,"step":0}""") as RangeQuestion).step, 0.0)
        assertTrue(question("""{"id":"r","type":"range","min":5,"max":5}""") is UnknownQuestion)
        assertTrue(question("""{"id":"r","type":"range","max":5}""") is UnknownQuestion)
        assertTrue(question("""{"id":"r","type":"range","min":5}""") is UnknownQuestion)
    }

    @Test fun dateOptions() {
        assertEquals(DateQuestion("d", "", min = "2024-01-01", max = "2024-12-31"), question("""{"id":"d","type":"date","min":"2024-01-01","max":"2024-12-31"}"""))
    }

    @Test fun choiceOptions() {
        val q = question(
            """{"id":"m","type":"multiple-choice","multiple":true,"imageAlignment":"vertical","options":[
               {"value":"a","label":"A","description":"da","image":"https://x/a.png"},
               {"value":"b","image":" "},{"value":" ","label":"skip"},{"label":"no value"},7]}""",
        ) as ChoiceQuestion
        assertTrue(q.multiple)
        assertEquals(ImageAlignment.VERTICAL, q.imageAlignment)
        assertEquals(listOf(ChoiceOption("a", "A", "da", "https://x/a.png"), ChoiceOption("b", "b")), q.options)
        assertTrue(q.hasImages)
        val single = question("""{"id":"m","type":"multiple-choice","options":[{"value":"a","label":"A"}]}""") as ChoiceQuestion
        assertFalse(single.multiple)
        assertFalse(single.hasImages)
        assertEquals(ImageAlignment.HORIZONTAL, single.imageAlignment)
        assertTrue(question("""{"id":"m","type":"multiple-choice","options":[]}""") is UnknownQuestion)
        assertTrue(question("""{"id":"m","type":"multiple-choice"}""") is UnknownQuestion)
    }

    @Test fun unknownTypesAreKeptAsUnsupported() {
        val q = question("""{"id":"x","label":"Sign","type":"signature","required":true,"visibleIf":{"questionId":"a","answered":true}}""") as UnknownQuestion
        assertEquals("signature", q.type)
        assertTrue(q.required)
        assertEquals(VisibilityRule.Answered("a", true), q.visibleIf)
        assertTrue(Unsupported.isUnsupported(q))
        assertFalse(Unsupported.isUnsupported(text("t")))
        assertEquals("", (question("""{"id":"x"}""") as UnknownQuestion).type)
        assertNull(question("""{"id":" ","type":"text"}"""))
    }

    @Test fun answersRoundTripThroughJson() {
        assertEquals("hi", QuestAnswer.Text("hi").toJson())
        assertEquals(2.5, QuestAnswer.Number(2.5).toJson())
        assertEquals(JSONObject.NULL, QuestAnswer.None.toJson())
        assertEquals("[\"a\",\"b\"]", QuestAnswer.Choices(listOf("a", "b")).toJson().toString())
        assertEquals(QuestAnswer.Text("x"), QuestAnswer.fromJson("x"))
        assertEquals(QuestAnswer.Number(3.0), QuestAnswer.fromJson(3))
        assertEquals(QuestAnswer.Choices(listOf("a")), QuestAnswer.fromJson(JSONArray().put("a")))
        assertEquals(QuestAnswer.None, QuestAnswer.fromJson(JSONObject.NULL))
        assertEquals(QuestAnswer.None, QuestAnswer.fromJson(null))
        assertEquals("""{"a":1,"b":null}""", answersToJson(linkedMapOf("a" to QuestAnswer.Number(1.0), "b" to QuestAnswer.None)).toString())
    }

    @Test fun answeredFollowsTheWeb() {
        assertTrue(QuestAnswer.Text("x").isAnswered)
        assertFalse(QuestAnswer.Text("  ").isAnswered)
        assertTrue(QuestAnswer.Number(0.0).isAnswered)
        assertTrue(QuestAnswer.Choices(listOf("a")).isAnswered)
        assertFalse(QuestAnswer.Choices(emptyList()).isAnswered)
        assertFalse(QuestAnswer.None.isAnswered)
    }

    private fun question(json: String) = QuestDecoder.question(JSONObject(json))
}
