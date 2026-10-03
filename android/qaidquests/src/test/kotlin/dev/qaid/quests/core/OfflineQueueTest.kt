package dev.qaid.quests.core

import dev.qaid.quests.core.QaidQuestsErrorEvent.Operation as Op
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class OfflineQueueTest {
    @get:Rule val tmp = TemporaryFolder()

    private val day = 24L * 60 * 60 * 1000
    private var now = 10 * day

    private fun pending(
        id: String,
        createdAt: Long = now,
        responseId: String? = null,
        patches: List<PendingPatch> = listOf(PendingPatch("a", QuestAnswer.Text("x")), PendingPatch("b", QuestAnswer.None)),
        submit: String? = """{"answers":{}}""",
    ) = PendingResponse(id, createdAt, "quest1", "https://qaid.dev/api/quests", "ua", responseId, """{"questId":"q"}""", patches, submit)

    @Test fun encodesAndDecodes() {
        val p = pending("p1", patches = listOf(PendingPatch("a", QuestAnswer.Choices(listOf("x"))), PendingPatch("n", QuestAnswer.Number(2.0))))
        assertEquals(p, PendingResponse.decode(p.encode()))
        val created = pending("p2", responseId = "r9", submit = null)
        assertEquals(created, PendingResponse.decode(created.encode()))
        assertNull(PendingResponse.decode("nope"))
        assertNull(PendingResponse.decode("""{"v":2}"""))
        assertNull(PendingResponse.decode("""{"v":1,"id":"x","createdAt":1,"base":"b","patches":[]}"""))
        val old = PendingResponse.decode("""{"v":1,"id":"x","createdAt":1,"base":"b","responseId":"r","createBody":"{}","patches":[]}""")
        assertEquals("", old?.userAgent)
        assertEquals("", old?.questId)
    }

    @Test fun policyDropsOldFutureAndOverflow() {
        val entries = listOf("old" to now - 8 * day, "future" to now + 8 * day) + (1..22).map { "n$it" to now - it }
        val drop = PendingPolicy.toDrop(entries, now)
        assertTrue("old" in drop && "future" in drop)
        // The two oldest of the 22 recent ones go, leaving 20.
        assertEquals(setOf("old", "future", "n22", "n21"), drop.toSet())
        assertTrue(PendingPolicy.toDrop(listOf("a" to now), now).isEmpty())
    }

    @Test fun storeSavesLoadsPrunesAndSweeps() {
        val dir = File(tmp.root, "queue")
        val store = PendingStore(dir) { now }
        assertTrue(store.loadAll().isEmpty())
        assertTrue(store.save(pending("b", createdAt = now - 1)))
        assertTrue(store.save(pending("a", createdAt = now - 2)))
        File(dir, "junk.json").writeText("garbage")
        File(dir, "half.tmp").writeText("x")
        assertEquals(listOf("a", "b"), store.loadAll().map { it.id })
        assertFalse(File(dir, "junk.json").exists())
        assertFalse(File(dir, "half.tmp").exists())
        store.delete("a")
        assertEquals(listOf("b"), store.loadAll().map { it.id })
        now += 8 * day
        assertTrue(store.loadAll().isEmpty())
    }

    @Test fun storeReportsWriteFailures() {
        val file = tmp.newFile("not-a-dir")
        assertFalse(PendingStore(file).save(pending("x")))
        val dir = tmp.newFolder("blocked")
        File(dir, "x.json").mkdirs() // a directory where the file should go: the rename fails
        assertFalse(PendingStore(dir).save(pending("x")))
        assertFalse(File(dir, "x.tmp").exists())
        val readOnly = tmp.newFolder("ro").apply { setWritable(false) }
        assertFalse(PendingStore(readOnly).save(pending("y")))
        readOnly.setWritable(true)
    }

    @Test fun flushSendsInOrderAndDeletes() = runTest {
        val store = PendingStore(tmp.newFolder()) { now }
        store.save(pending("p1", createdAt = now - 2))
        store.save(pending("p2", createdAt = now - 1, responseId = "r2", submit = null))
        val server = FakeTransport()
        assertEquals(2, PendingFlusher(store, server).flush())
        assertEquals(
            listOf(
                "POST https://qaid.dev/api/quests/responses",
                "PATCH https://qaid.dev/api/quests/responses/r1",
                "PATCH https://qaid.dev/api/quests/responses/r1",
                "POST https://qaid.dev/api/quests/responses/r1/submit",
                "PATCH https://qaid.dev/api/quests/responses/r2",
                "PATCH https://qaid.dev/api/quests/responses/r2",
            ),
            server.requests.map { "${it.method} ${it.url}" },
        )
        assertEquals("""{"questId":"q"}""", server.requests[0].body)
        assertEquals("ua", server.requests[0].headers["User-Agent"])
        assertTrue(store.loadAll().isEmpty())
    }

    @Test fun offlineStopsAndKeepsProgress() = runTest {
        val store = PendingStore(tmp.newFolder()) { now }
        store.save(pending("p1", createdAt = now - 2))
        store.save(pending("p2", createdAt = now - 1))
        var patches = 0
        val server = FakeTransport { r ->
            if (r.method == "PATCH" && ++patches == 2) throw java.io.IOException("gone") else FakeTransport.defaultResponse(r)
        }
        assertEquals(0, PendingFlusher(store, server).flush())
        val left = store.loadAll()
        assertEquals(listOf("p1", "p2"), left.map { it.id })
        assertEquals("r1", left[0].responseId)
        assertEquals(listOf("b"), left[0].patches.map { it.questionId })

        store.loadAll().forEach { store.delete(it.id) }
        store.save(pending("c"))
        // The default wait between retries is none: a flush runs at a moment the app chose.
        val down = FakeTransport(FakeTransport.offline)
        assertEquals(0, PendingFlusher(store, down, RetryPolicy(listOf(5))).flush())
        assertEquals(2, down.requests.size)
        assertEquals(1, store.loadAll().size)
        store.save(pending("s", responseId = "r", patches = emptyList()))
        store.delete("c")
        assertEquals(0, PendingFlusher(store, FakeTransport { r -> if (r.url.endsWith("/submit")) throw java.io.IOException() else FakeTransport.defaultResponse(r) }).flush())
        assertEquals(1, store.loadAll().size)
    }

    @Test fun refusedCallsAreDroppedAndReported() = runTest {
        val store = PendingStore(tmp.newFolder()) { now }
        val events = mutableListOf<QaidQuestsErrorEvent>()
        val refused: (QaidQuestsErrorEvent) -> Unit = { events += it }
        store.save(pending("create", createdAt = now - 4))
        PendingFlusher(store, FakeTransport { HttpResponse(401, """{"error":"Invalid API key"}""") }, onRefused = refused).flush()
        assertEquals(
            listOf(QaidQuestsErrorEvent("quest1", Op.CREATE_RESPONSE, error = QaidQuestsError.Rejected(401, "Invalid API key"))),
            events,
        )
        assertTrue(store.loadAll().isEmpty())

        store.save(pending("noid"))
        PendingFlusher(store, FakeTransport { HttpResponse(201, "{}") }, onRefused = refused).flush()
        assertTrue(store.loadAll().isEmpty())

        store.save(pending("patch", responseId = "r"))
        var sent = PendingFlusher(store, FakeTransport { r -> if (r.method == "PATCH") HttpResponse(400, "") else HttpResponse(200, "") }, onRefused = refused).flush()
        assertEquals(1, sent)

        store.save(pending("submit", responseId = "r", patches = emptyList()))
        sent = PendingFlusher(store, FakeTransport { HttpResponse(410, """{"error":"Quest is archived"}""") }, onRefused = refused).flush()
        assertEquals(0, sent)
        assertEquals(
            listOf(Op.CREATE_RESPONSE, Op.CREATE_RESPONSE, Op.SAVE_ANSWER, Op.SAVE_ANSWER, Op.SUBMIT),
            events.map { it.operation },
        )
        assertEquals(listOf(null, null, "a", "b", null), events.map { it.questionId })
        assertTrue(events.all { it.questId == "quest1" })
        assertTrue(store.loadAll().isEmpty())
    }
}
