package dev.qaid.quests.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class QuestControllerTest {
    private val completed = mutableListOf<Map<String, QuestAnswer>>()
    private val errors = mutableListOf<QaidQuestsErrorEvent>()
    private val logs = mutableListOf<String>()
    private val persisted = mutableListOf<PendingResponse>()
    private var canPersist = true

    private val callbacks = object : QuestCallbacks {
        override fun onComplete(answers: Map<String, QuestAnswer>) {
            completed += answers
        }
        override fun onError(event: QaidQuestsErrorEvent) {
            errors += event
        }
        override fun log(message: String) {
            logs += message
        }
        override fun persist(pending: PendingResponse): Boolean {
            persisted += pending
            return canPersist
        }
    }

    private val env = QuestEnvironment("app://p/home", "visitor", "ua", JSONObject().put("source", "native-quest"))
    private val config = QaidQuestsConfig(apiKey = "key", appName = "App")

    private fun TestScope.controller(server: FakeTransport, config: QaidQuestsConfig = this@QuestControllerTest.config) =
        QuestController("quest1", config, env, server, this, callbacks, RetryPolicy(emptyList()))

    @Test fun nothingHappensBeforeStart() = runTest {
        val server = FakeTransport()
        val c = controller(server)
        advanceUntilIdle()
        assertTrue(server.requests.isEmpty())
        assertEquals(QuestPhase.LOADING, c.state.value.phase)
        c.answer("role", QuestAnswer.Text("dev"))
        c.next()
        c.back()
        c.retryLoad()
        c.retrySubmit()
        c.close()
        assertTrue(server.requests.isEmpty())
    }

    @Test fun fullFlowSubmitsVisibleAnswers() = runTest {
        val server = FakeTransport()
        val c = controller(server)
        c.start()
        c.start()
        advanceUntilIdle()
        val s = c.state.value
        assertEquals(QuestPhase.ANSWERING, s.phase)
        assertEquals("Tell us", s.title)
        assertEquals("Two minutes", s.description)
        assertEquals("role", s.step!!.question.id)
        assertNull(s.step!!.backLabel)
        assertEquals("Next", s.step!!.primaryLabel)
        assertEquals(2, s.step!!.total)
        assertTrue(s.showProgress)
        assertEquals(0.5f, s.progressFraction)
        assertEquals(QaidQuestProgressPosition.TOP, s.progressPosition)

        val create = JSONObject(server.requests.first { it.method == "POST" }.body!!)
        assertEquals("key", create.getString("apiKey"))
        assertEquals("quest1", create.getString("questId"))
        assertEquals("app://p/home", create.getString("pageUrl"))
        assertEquals("visitor", create.getString("visitorId"))

        c.next()
        assertEquals("Please select an option.", c.state.value.step!!.error)
        c.answer("role", QuestAnswer.Text("dev"))
        assertNull(c.state.value.step!!.error)
        assertEquals(3, c.state.value.step!!.total)
        c.next()
        assertEquals("why", c.state.value.step!!.question.id)
        assertEquals("Back", c.state.value.step!!.backLabel)
        c.answer("why", QuestAnswer.Text("fun"))
        c.back()
        c.answer("role", QuestAnswer.Text("pm"))
        c.next()
        val range = c.state.value.step!!
        assertEquals("score", range.question.id)
        assertEquals(QuestAnswer.Number(0.0), range.value)
        assertEquals("Submit", range.primaryLabel)
        c.next()
        assertEquals(QuestPhase.SUBMITTING, c.state.value.phase)
        c.next() // ignored while submitting
        advanceUntilIdle()
        assertEquals(QuestPhase.DONE, c.state.value.phase)
        assertEquals("Thank you!", c.state.value.thankYouTitle)
        assertFalse(c.state.value.savedOffline)
        assertEquals(listOf(mapOf("role" to QuestAnswer.Text("pm"), "score" to QuestAnswer.Number(0.0))), completed)
        // The hidden "why" was cleared on the server before submit.
        assertTrue(server.patches().any { it == """{"questionId":"why","value":null}""" })
        // (The JVM test org.json doesn't keep key order; Android's does.)
        val submitted = JSONObject(server.requests.last().body!!).getJSONObject("answers")
        assertEquals(2, submitted.length())
        assertEquals("pm", submitted.getString("role"))
        assertEquals(0, submitted.getInt("score"))
        assertFalse(c.saving.value)
    }

    @Test fun loadFailuresShowTheWebsMessagesAndRetry() = runTest {
        var response = HttpResponse(404, """{"error":"Quest not found"}""")
        val server = FakeTransport { r -> if (r.url.endsWith("/definition")) response else FakeTransport.defaultResponse(r) }
        val c = controller(server)
        c.start()
        advanceUntilIdle()
        assertEquals(QuestPhase.LOAD_FAILED, c.state.value.phase)
        assertEquals("Failed to load questionnaire (404)", c.state.value.loadError)
        assertEquals(QaidQuestsErrorEvent.Operation.LOAD_QUEST, errors.single().operation)
        assertEquals(QaidQuestsError.Rejected(404, "Quest not found"), errors.single().error)

        response = HttpResponse(200, """{"questions":[]}""")
        c.retryLoad()
        assertEquals(QuestPhase.LOADING, c.state.value.phase)
        advanceUntilIdle()
        assertEquals("Questionnaire is empty", c.state.value.loadError)

        response = HttpResponse(200, "<html>")
        c.retryLoad()
        advanceUntilIdle()
        assertEquals("The questionnaire couldn't be read.", c.state.value.loadError)

        c.retryLoad()
        server.respond = FakeTransport.offline
        advanceUntilIdle()
        assertEquals("Check your connection and try again.", c.state.value.loadError)

        server.respond = FakeTransport::defaultResponse
        c.retryLoad()
        advanceUntilIdle()
        assertEquals(QuestPhase.ANSWERING, c.state.value.phase)
        c.retryLoad() // only from LOAD_FAILED
        assertEquals(QuestPhase.ANSWERING, c.state.value.phase)
    }

    @Test fun themeLoadsInParallelAndFailuresAreReported() = runTest {
        val themed = config.copy(themeId = "th1", theme = QaidQuestTheme(fontSize = 18f))
        val server = FakeTransport()
        val c = controller(server, themed)
        c.start()
        advanceUntilIdle()
        assertEquals("GET", server.requests[1].method)
        assertTrue(server.requests[1].url.endsWith("/api/quest-themes/th1/public?key=key"))
        assertEquals(QaidQuestTheme.Mode.DARK, c.state.value.style.mode)
        assertEquals(0xFFFF0000.toInt(), c.state.value.style.light.accent)
        assertEquals(18f, c.state.value.style.metrics.fontSize)

        val failing = FakeTransport { r -> if (r.url.contains("quest-themes")) HttpResponse(404, "") else FakeTransport.defaultResponse(r) }
        val d = controller(failing, themed)
        d.start()
        advanceUntilIdle()
        assertEquals(QuestPhase.ANSWERING, d.state.value.phase)
        assertEquals(QaidQuestTheme.Mode.AUTO, d.state.value.style.mode)
        assertEquals(
            QaidQuestsErrorEvent("quest1", QaidQuestsErrorEvent.Operation.LOAD_THEME, error = QaidQuestsError.Rejected(404, "")),
            errors.single(),
        )
        val down = FakeTransport { r -> if (r.url.contains("quest-themes")) throw IOException("down") else FakeTransport.defaultResponse(r) }
        controller(down, themed).start()
        advanceUntilIdle()
        assertTrue(errors.last().error is QaidQuestsError.Network)

        val cssy = FakeTransport { r -> if (r.url.contains("quest-themes")) HttpResponse(200, """{"css":".a{}"}""") else FakeTransport.defaultResponse(r) }
        controller(cssy, themed).start()
        advanceUntilIdle()
        assertTrue(logs.any { it.contains("no native equivalent") })
        val unstyled = FakeTransport { r -> if (r.url.contains("quest-themes")) HttpResponse(200, """{"unstyled":true}""") else FakeTransport.defaultResponse(r) }
        logs.clear()
        controller(unstyled, themed).start()
        advanceUntilIdle()
        assertTrue(logs.any { it.contains("no native equivalent") })

        val bad = FakeTransport { r -> if (r.url.contains("quest-themes")) HttpResponse(200, "nope") else FakeTransport.defaultResponse(r) }
        logs.clear()
        controller(bad, themed).start()
        advanceUntilIdle()
        assertEquals(QaidQuestsError.InvalidResponse("Invalid theme"), errors.last().error)
    }

    @Test fun offlineSubmitSavesAndThanks() = runTest {
        val server = FakeTransport { r -> if (r.method == "GET") FakeTransport.defaultResponse(r) else throw IOException("offline") }
        val c = controller(server)
        c.start()
        advanceUntilIdle()
        c.answer("role", QuestAnswer.Text("pm"))
        c.next()
        c.next()
        advanceUntilIdle()
        assertEquals(QuestPhase.DONE, c.state.value.phase)
        assertTrue(c.state.value.savedOffline)
        assertEquals(1, persisted.size)
        assertEquals(1, completed.size)
    }

    @Test fun offlineSubmitThatCannotBeSavedFails() = runTest {
        canPersist = false
        val server = FakeTransport { r -> if (r.method == "GET") FakeTransport.defaultResponse(r) else throw IOException("offline") }
        val c = controller(server)
        c.start()
        advanceUntilIdle()
        c.answer("role", QuestAnswer.Text("pm"))
        c.next()
        c.next()
        advanceUntilIdle()
        assertEquals(QuestPhase.SUBMIT_FAILED, c.state.value.phase)
        assertEquals(QaidQuestsErrorEvent.Operation.OFFLINE_QUEUE, errors.last().operation)
        assertTrue(errors.last().error is QaidQuestsError.Storage)
        assertTrue(completed.isEmpty())

        // Try again once the disk can take it: the same answers are saved, not reported as sent.
        canPersist = true
        c.retrySubmit()
        advanceUntilIdle()
        assertEquals(QuestPhase.DONE, c.state.value.phase)
        assertTrue(c.state.value.savedOffline)
        assertEquals(2, persisted.size)
        assertEquals(persisted[0].patches, persisted[1].patches)
        assertTrue(persisted[1].patches.contains(PendingPatch("role", QuestAnswer.Text("pm"))))
        assertEquals(1, completed.size)
    }

    @Test fun refusedSubmitShowsTheErrorScreenAndRetries() = runTest {
        var archived = true
        val server = FakeTransport { r ->
            if (r.url.endsWith("/submit") && archived) HttpResponse(410, """{"error":"Quest is archived"}""") else FakeTransport.defaultResponse(r)
        }
        val c = controller(server)
        c.start()
        advanceUntilIdle()
        c.answer("role", QuestAnswer.Text("pm"))
        c.next()
        c.next()
        advanceUntilIdle()
        assertEquals(QuestPhase.SUBMIT_FAILED, c.state.value.phase)
        archived = false
        c.retrySubmit()
        advanceUntilIdle()
        assertEquals(QuestPhase.DONE, c.state.value.phase)
        assertEquals(1, completed.size)
    }

    @Test fun autoAdvanceMovesOnAfterASingleChoicePick() = runTest {
        val c = controller(FakeTransport(), config.copy(autoAdvance = true, progressPosition = QaidQuestProgressPosition.BOTTOM))
        c.start()
        advanceUntilIdle()
        assertEquals(QaidQuestProgressPosition.BOTTOM, c.state.value.progressPosition)
        c.answer("role", QuestAnswer.Text("dev"))
        c.answer("role", QuestAnswer.Text("pm"))
        advanceTimeBy(100)
        assertEquals("role", c.state.value.step!!.question.id)
        advanceTimeBy(200)
        assertEquals("score", c.state.value.step!!.question.id)
        // A pick then Back within the delay: no advance.
        c.back()
        c.answer("role", QuestAnswer.Text("dev"))
        c.next()
        c.back()
        advanceUntilIdle()
        assertEquals("role", c.state.value.step!!.question.id)
        // Answering a later question doesn't advance.
        c.close()
        runCurrent()
    }

    @Test fun autoAdvanceIgnoresTypingAndMultiSelect() = runTest {
        val json = """{"id":"q","questions":[
            {"id":"m","label":"M","type":"multiple-choice","multiple":true,"options":[{"value":"a","label":"A"}]},
            {"id":"t","label":"T","type":"text"},
            {"id":"u","label":"U","type":"signature"}]}"""
        val server = FakeTransport { r -> if (r.url.endsWith("/definition")) HttpResponse(200, json) else FakeTransport.defaultResponse(r) }
        val c = controller(server, config.copy(autoAdvance = true))
        c.start()
        advanceUntilIdle()
        assertTrue(logs.any { it.contains("signature") })
        c.answer("m", QuestAnswer.Choices(listOf("a")))
        advanceUntilIdle()
        assertEquals("m", c.state.value.step!!.question.id)
        c.answer("t", QuestAnswer.Text("x")) // not the current question
        advanceUntilIdle()
        assertEquals("m", c.state.value.step!!.question.id)
        c.next()
        c.answer("t", QuestAnswer.Text("typed")) // the current question, but not a choice
        advanceUntilIdle()
        assertEquals("t", c.state.value.step!!.question.id)
        c.next()
        assertTrue(c.state.value.step!!.unsupported)
        assertNull(c.state.value.title)
    }

    @Test fun hiddenTitleAndAuthorThankYou() = runTest {
        val json = """{"id":"q","title":"T","description":"D","hideTitle":true,"thankYouTitle":"Ta!","thankYouMessage":"See you",
            "questions":[{"id":"a","label":"A","type":"text"}]}"""
        val server = FakeTransport { r -> if (r.url.endsWith("/definition")) HttpResponse(200, json) else FakeTransport.defaultResponse(r) }
        val c = controller(server)
        c.start()
        advanceUntilIdle()
        assertNull(c.state.value.title)
        assertNull(c.state.value.description)
        assertFalse(c.state.value.showProgress)
        assertEquals("Ta!", c.state.value.thankYouTitle)
        assertEquals("See you", c.state.value.thankYouMessage)
    }
}
