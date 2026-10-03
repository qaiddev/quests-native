package dev.qaid.quests.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class WireTest {
    private val endpoints = QuestEndpoints("https://qaid.dev/api/quests/")

    @Test fun endpoints() {
        assertEquals("https://qaid.dev/api/quests/a%20b/definition", endpoints.definition("a b"))
        assertEquals("https://qaid.dev/api/quests/responses", endpoints.responses)
        assertEquals("https://qaid.dev/api/quests/responses/r1", endpoints.response("r1"))
        assertEquals("https://qaid.dev/api/quests/responses/r1/submit", endpoints.submit("r1"))
    }

    @Test fun requestShapes() {
        val get = QuestRequests.get("u", "ua")
        assertEquals(HttpRequest("GET", "u", mapOf("Accept" to "application/json", "User-Agent" to "ua")), get)

        val body = JSONObject(QuestRequests.createBody("key", "q1", "app://p", "v1", "ua", JSONObject().put("a", 1)))
        assertEquals("key", body.getString("apiKey"))
        assertEquals("q1", body.getString("questId"))
        assertEquals("app://p", body.getString("pageUrl"))
        assertEquals("v1", body.getString("visitorId"))
        assertEquals("ua", body.getString("userAgent"))
        assertEquals(1, body.getJSONObject("metadata").getInt("a"))
        assertFalse(JSONObject(QuestRequests.createBody("", "q1", "p", "v", "ua", JSONObject())).has("apiKey"))

        val create = QuestRequests.create(endpoints, "ua", "{}")
        assertEquals("POST", create.method)
        assertEquals("application/json", create.headers["Content-Type"])
        assertEquals("{}", create.body)

        assertEquals("""{"questionId":"a","value":null}""", QuestRequests.patchBody("a", QuestAnswer.None))
        assertEquals("""{"questionId":"a","value":12.5}""", QuestRequests.patchBody("a", QuestAnswer.Number(12.5)))
        assertEquals("""{"questionId":"a","value":3}""", QuestRequests.patchBody("a", QuestAnswer.Number(3.0)))
        assertEquals("""{"questionId":"a","value":["x","y"]}""", QuestRequests.patchBody("a", QuestAnswer.Choices(listOf("x", "y"))))
        assertEquals("PATCH", QuestRequests.patch(endpoints, "r1", "ua", "{}").method)
        assertEquals("""{"answers":{"a":"x"}}""", QuestRequests.submitBody(mapOf("a" to QuestAnswer.Text("x"))))
        assertEquals("https://qaid.dev/api/quests/responses/r1/submit", QuestRequests.submit(endpoints, "r1", "ua", "{}").url)
    }

    @Test fun parsing() {
        assertEquals("abc", QuestRequests.createdId("""{"id":"abc"}"""))
        assertNull(QuestRequests.createdId("""{"id":""}"""))
        assertNull(QuestRequests.createdId("""{"id":7}"""))
        assertNull(QuestRequests.createdId("<html>"))
        assertEquals("Invalid API key", QuestRequests.errorText("""{"error":"Invalid API key"}"""))
        assertEquals("", QuestRequests.errorText("oops"))
    }

    @Test fun errors() {
        assertTrue(QaidQuestError.Network("x").isRetryable)
        assertTrue(QaidQuestError.Http(500, "").isRetryable)
        assertTrue(QaidQuestError.Http(408, "").isRetryable)
        assertTrue(QaidQuestError.Http(429, "").isRetryable)
        assertFalse(QaidQuestError.Http(404, "Quest not found").isRetryable)
        assertFalse(QaidQuestError.Decoding("x").isRetryable)
        assertFalse(QaidQuestError.Storage("x").isRetryable)
        assertEquals("HTTP 404 Quest not found", QaidQuestError.Http(404, "Quest not found").message)
        assertEquals("HTTP 500", QaidQuestError.Http(500, "").message)
        assertEquals(1_000L, RetryPolicy().delayAfter(0))
        assertNull(RetryPolicy().delayAfter(2))
    }

    @Test fun callRetriesNetworkAndServerErrorsOnly() = runTest {
        val waits = mutableListOf<Long>()
        var n = 0
        val flaky = FakeTransport { n++; if (n == 1) throw IOException("down") else if (n == 2) HttpResponse(503, "") else HttpResponse(200, "ok") }
        assertEquals(CallOutcome.Ok("ok"), flaky.call(QuestRequests.get("u", "ua"), RetryPolicy(listOf(5, 7)), { waits += it }))
        assertEquals(listOf(5L, 7L), waits)

        val refused = FakeTransport { HttpResponse(401, """{"error":"Invalid API key"}""") }
        assertEquals(CallOutcome.Refused(QaidQuestError.Http(401, "Invalid API key")), refused.call(QuestRequests.get("u", "ua"), RetryPolicy(), {}))
        assertEquals(1, refused.requests.size)

        val down = FakeTransport { throw IOException() }
        val outcome = down.call(QuestRequests.get("u", "ua"), RetryPolicy(listOf(1)), {})
        assertEquals(CallOutcome.Unreachable(QaidQuestError.Network("IOException")), outcome)
        assertEquals(2, down.requests.size)

        val informational = FakeTransport { HttpResponse(199, "") }
        assertEquals(CallOutcome.Refused(QaidQuestError.Http(199, "")), informational.call(QuestRequests.get("u", "ua"), RetryPolicy(), {}))

        val odd = FakeTransport { throw SecurityException("no INTERNET permission") }
        assertEquals(CallOutcome.Unreachable(QaidQuestError.Network("no INTERNET permission")), odd.call(QuestRequests.get("u", "ua"), RetryPolicy(emptyList()), {}))
    }

    @Test(expected = CancellationException::class)
    fun cancellationIsNotSwallowed() = runTest {
        FakeTransport { throw CancellationException("stop") }.call(QuestRequests.get("u", "ua"), RetryPolicy(), {})
    }
}
