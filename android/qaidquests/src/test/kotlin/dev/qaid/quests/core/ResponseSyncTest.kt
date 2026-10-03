package dev.qaid.quests.core

import dev.qaid.quests.core.QaidQuestsErrorEvent.Operation as Op
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
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
class ResponseSyncTest {
    private val endpoints = QuestEndpoints("https://qaid.dev/api/quests")
    private val errors = mutableListOf<QaidQuestsErrorEvent>()
    private val persisted = mutableListOf<PendingResponse>()
    private var canPersist = true
    private val busy = MutableStateFlow(false)

    private fun TestScope.sync(
        server: QuestTransport,
        debounce: Long = 500,
        retry: RetryPolicy = RetryPolicy(),
        createBody: String = """{"questId":"q"}""",
    ) = ResponseSync(
        server, endpoints, "quest1", createBody, "ua", this,
        persist = { persisted += it; canPersist },
        retry = retry, debounceMs = debounce, now = { 42L }, busy = busy, onError = { errors += it },
    )

    private fun values(server: FakeTransport) = server.patches().map { JSONObject(it).let { o -> "${o.getString("questionId")}=${o.get("value")}" } }

    @Test fun answersBeforeCreateWaitAndGoInOrder() = runTest {
        val created = CompletableDeferred<Unit>()
        val server = FakeTransport()
        val gated = QuestTransport { request ->
            if (request.method == "POST") created.await()
            server.send(request)
        }
        val sync = sync(gated, createBody = "{}")
        sync.start()
        sync.answer("a", QuestAnswer.Text("1"), immediate = true)
        sync.answer("b", QuestAnswer.Text("2"), immediate = true)
        runCurrent()
        assertTrue(busy.value)
        assertTrue(server.patches().isEmpty())
        created.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("a=1", "b=2"), values(server))
        assertEquals("r1", sync.responseId)
        assertFalse(busy.value)
        assertEquals("https://qaid.dev/api/quests/responses/r1", server.requests[1].url)
    }

    @Test fun typingIsDebouncedPerQuestionAndDeduplicated() = runTest {
        val server = FakeTransport()
        val sync = sync(server)
        sync.start()
        sync.answer("t", QuestAnswer.Text("h"), immediate = false)
        advanceTimeBy(300)
        sync.answer("t", QuestAnswer.Text("hi"), immediate = false)
        advanceTimeBy(300)
        assertTrue(server.patches().isEmpty())
        advanceUntilIdle()
        assertEquals(listOf("t=hi"), values(server))
        // The same value again is not re-sent.
        sync.answer("t", QuestAnswer.Text("hi"), immediate = true)
        advanceUntilIdle()
        assertEquals(1, server.patches().size)
    }

    @Test fun flushSendsPendingTypingNow() = runTest {
        val server = FakeTransport()
        val sync = sync(server)
        sync.answer("a", QuestAnswer.Text("x"), immediate = false)
        sync.answer("b", QuestAnswer.Number(2.0), immediate = false)
        sync.flush()
        advanceUntilIdle()
        assertEquals(listOf("a=x", "b=2"), values(server))
    }

    @Test fun zeroDebounceSendsAtOnce() = runTest {
        val server = FakeTransport()
        sync(server, debounce = 0).answer("a", QuestAnswer.Text("x"), immediate = false)
        advanceUntilIdle()
        assertEquals(listOf("a=x"), values(server))
    }

    @Test fun refusedSaveIsReportedAndSkipped() = runTest {
        val server = FakeTransport { r ->
            if (r.method == "PATCH" && r.body!!.contains("\"bad\"")) HttpResponse(400, """{"error":"Unknown questionId"}""") else FakeTransport.defaultResponse(r)
        }
        val sync = sync(server)
        sync.answer("bad", QuestAnswer.Text("x"), true)
        sync.answer("good", QuestAnswer.Text("y"), true)
        advanceUntilIdle()
        assertEquals(listOf("bad=x", "good=y"), values(server))
        assertEquals(listOf(QaidQuestsErrorEvent("quest1", Op.SAVE_ANSWER, "bad", QaidQuestsError.Rejected(400, "Unknown questionId"))), errors)
    }

    @Test fun submitClearsHiddenAnswersThenSubmits() = runTest {
        val server = FakeTransport()
        val sync = sync(server)
        sync.start()
        sync.answer("a", QuestAnswer.Text("typing"), immediate = false)
        val outcome = sync.submit(mapOf("a" to QuestAnswer.Text("typing")), hidden = listOf("h"))
        assertEquals(SubmitOutcome.Sent, outcome)
        assertEquals(listOf("a=typing", "h=null"), values(server))
        val submit = server.requests.last()
        assertEquals("https://qaid.dev/api/quests/responses/r1/submit", submit.url)
        assertEquals("""{"answers":{"a":"typing"}}""", submit.body)
        // Finished: nothing more is sent.
        sync.answer("a", QuestAnswer.Text("late"), true)
        assertEquals(SubmitOutcome.Sent, sync.submit(emptyMap(), emptyList()))
        advanceUntilIdle()
        assertEquals(2, server.patches().size)
        assertTrue(persisted.isEmpty())
    }

    @Test fun networkFailuresRetryWithBackoff() = runTest {
        var fails = 2
        val server = FakeTransport { r -> if (r.method == "PATCH" && fails-- > 0) throw IOException("flaky") else FakeTransport.defaultResponse(r) }
        val sync = sync(server)
        sync.answer("a", QuestAnswer.Text("x"), true)
        advanceTimeBy(500)
        assertEquals(1, server.patches().size)
        advanceUntilIdle()
        assertEquals(3, server.patches().size)
        assertEquals(4_000L, currentTime)
        assertTrue(errors.isEmpty())
    }

    @Test fun offlineGoesStraightToTheQueueAtSubmit() = runTest {
        val server = FakeTransport(FakeTransport.offline)
        val sync = sync(server, retry = RetryPolicy(listOf(10)))
        sync.start()
        sync.answer("a", QuestAnswer.Text("x"), true)
        advanceUntilIdle()
        // The create gave up after its retries; answers wait in memory, with no retry storm per answer.
        assertEquals(2, server.requests.size)
        sync.answer("b", QuestAnswer.Text("y"), true)
        advanceUntilIdle()
        assertEquals(2, server.requests.size)
        val started = currentTime
        val outcome = sync.submit(mapOf("a" to QuestAnswer.Text("x")), listOf("h"))
        // The network already failed: no second round of retries before the queue.
        assertEquals(SubmitOutcome.SavedOffline, outcome)
        assertEquals(2, server.requests.size)
        assertEquals(started, currentTime)
        val p = persisted.single()
        assertNull(p.responseId)
        assertEquals("quest1", p.questId)
        assertEquals("""{"questId":"q"}""", p.createBody)
        assertEquals(
            listOf(PendingPatch("a", QuestAnswer.Text("x")), PendingPatch("b", QuestAnswer.Text("y")), PendingPatch("h", QuestAnswer.None)),
            p.patches,
        )
        assertEquals("""{"answers":{"a":"x"}}""", p.submitBody)
        assertEquals(42L, p.createdAtMs)
        assertEquals("ua", p.userAgent)
        assertEquals("https://qaid.dev/api/quests", p.base)
        assertTrue(p.id.startsWith("42-"))
        // Saved: nothing more goes out, and a second submit is the same answer.
        sync.answer("c", QuestAnswer.Text("z"), true)
        advanceUntilIdle()
        assertEquals(SubmitOutcome.SavedOffline, sync.submit(emptyMap(), emptyList()))
        assertEquals(2, server.requests.size)
        assertEquals(1, persisted.size)
    }

    @Test fun offlineAfterCreateKeepsTheResponseId() = runTest {
        var online = true
        val server = FakeTransport { r -> if (!online) throw IOException() else FakeTransport.defaultResponse(r) }
        val sync = sync(server, retry = RetryPolicy(emptyList()))
        sync.start()
        advanceUntilIdle()
        online = false
        sync.answer("a", QuestAnswer.Text("x"), true)
        advanceUntilIdle()
        assertEquals(SubmitOutcome.SavedOffline, sync.submit(mapOf("a" to QuestAnswer.Text("x")), emptyList()))
        val p = persisted.single()
        assertEquals("r1", p.responseId)
        assertEquals(listOf(PendingPatch("a", QuestAnswer.Text("x"))), p.patches)
    }

    @Test fun submitThatCannotReachTheServerIsQueued() = runTest {
        val server = FakeTransport { r -> if (r.url.endsWith("/submit")) throw IOException() else FakeTransport.defaultResponse(r) }
        val outcome = sync(server, retry = RetryPolicy(emptyList())).submit(emptyMap(), emptyList())
        assertEquals(SubmitOutcome.SavedOffline, outcome)
        assertEquals("r1", persisted.single().responseId)
        assertTrue(persisted.single().patches.isEmpty())
    }

    @Test fun aServerErrorIsTriedOnceMoreAtSubmit() = runTest {
        var down = true
        val server = FakeTransport { r -> if (down && r.method == "PATCH") HttpResponse(503, "") else FakeTransport.defaultResponse(r) }
        val sync = sync(server, retry = RetryPolicy(emptyList()))
        sync.start()
        sync.answer("a", QuestAnswer.Text("x"), true)
        advanceUntilIdle()
        assertEquals(1, server.patches().size)
        down = false
        assertEquals(SubmitOutcome.Sent, sync.submit(mapOf("a" to QuestAnswer.Text("x")), emptyList()))
        assertEquals(2, server.patches().size)
        assertTrue(persisted.isEmpty())
    }

    @Test fun aStalledSubmitIsQueuedAfterTheTimeLimit() = runTest {
        var sentSubmit = 0
        val stalled = QuestTransport { r ->
            if (r.url.endsWith("/submit")) {
                sentSubmit++
                delay(10 * 60_000)
            }
            FakeTransport.defaultResponse(r)
        }
        val sync = sync(stalled)
        sync.start()
        sync.answer("a", QuestAnswer.Text("x"), true)
        advanceUntilIdle()
        val started = currentTime
        val outcome = sync.submit(mapOf("a" to QuestAnswer.Text("x")), emptyList())
        assertEquals(SubmitOutcome.SavedOffline, outcome)
        assertEquals(ResponseSync.SUBMIT_TIMEOUT_MS, currentTime - started)
        assertEquals(1, sentSubmit)
        val p = persisted.single()
        assertEquals("r1", p.responseId)
        assertTrue(p.patches.isEmpty())
        assertEquals("""{"answers":{"a":"x"}}""", p.submitBody)
        // The cancelled call is gone for good: nothing it does later reaches the queue or the server.
        advanceUntilIdle()
        assertEquals(1, sentSubmit)
        assertFalse(busy.value)
    }

    @Test fun aQueueThatCantBeWrittenKeepsEverythingForTryAgain() = runTest {
        canPersist = false
        val server = FakeTransport(FakeTransport.offline)
        val sync = sync(server, retry = RetryPolicy(emptyList()))
        sync.start()
        sync.answer("a", QuestAnswer.Text("x"), true)
        advanceUntilIdle()
        val failed = sync.submit(mapOf("a" to QuestAnswer.Text("x")), emptyList())
        assertEquals(SubmitOutcome.Failed(QaidQuestsError.Storage("couldn't save the answers to send later")), failed)
        assertEquals(listOf(Op.OFFLINE_QUEUE), errors.map { it.operation })
        // Not "sent": Try again saves the very same answers.
        canPersist = true
        assertEquals(SubmitOutcome.SavedOffline, sync.submit(mapOf("a" to QuestAnswer.Text("x")), emptyList()))
        assertEquals(2, persisted.size)
        assertEquals(listOf(PendingPatch("a", QuestAnswer.Text("x"))), persisted[1].patches)
        assertEquals(persisted[0].submitBody, persisted[1].submitBody)
    }

    @Test fun refusedCreateFailsSubmitAndIsRetriedOnTheNextSubmit() = runTest {
        var key = "bad"
        val server = FakeTransport { r ->
            if (r.method == "POST" && r.url.endsWith("/responses") && key == "bad") HttpResponse(401, """{"error":"Invalid API key"}""")
            else FakeTransport.defaultResponse(r)
        }
        val sync = sync(server)
        sync.start()
        sync.answer("a", QuestAnswer.Text("x"), true)
        advanceUntilIdle()
        assertEquals(1, server.count("POST", "/responses"))
        assertEquals(Op.CREATE_RESPONSE, errors.single().operation)
        // A refused create isn't retried on every answer — only at submit, as the web does.
        sync.answer("b", QuestAnswer.Text("y"), true)
        advanceUntilIdle()
        assertEquals(1, server.count("POST", "/responses"))
        assertEquals(SubmitOutcome.Failed(QaidQuestsError.Rejected(401, "Invalid API key")), sync.submit(emptyMap(), emptyList()))
        assertEquals(2, server.count("POST", "/responses"))
        key = "good"
        assertEquals(SubmitOutcome.Sent, sync.submit(emptyMap(), emptyList()))
        assertEquals(listOf("a=x", "b=y"), values(server))
        assertEquals(1, server.count("POST", "/submit"))
    }

    @Test fun createWithoutAnIdIsRefused() = runTest {
        val server = FakeTransport { r -> if (r.method == "POST") HttpResponse(201, "{}") else FakeTransport.defaultResponse(r) }
        val outcome = sync(server).submit(emptyMap(), emptyList())
        assertEquals(SubmitOutcome.Failed(QaidQuestsError.InvalidResponse("no response id")), outcome)
    }

    @Test fun refusedSubmitFails() = runTest {
        val server = FakeTransport { r -> if (r.url.endsWith("/submit")) HttpResponse(410, """{"error":"Quest is archived"}""") else FakeTransport.defaultResponse(r) }
        val outcome = sync(server).submit(emptyMap(), emptyList())
        assertEquals(SubmitOutcome.Failed(QaidQuestsError.Rejected(410, "Quest is archived")), outcome)
        assertEquals(Op.SUBMIT, errors.last().operation)
    }
}
