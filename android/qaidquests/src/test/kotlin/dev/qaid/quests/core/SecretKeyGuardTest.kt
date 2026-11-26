package dev.qaid.quests.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.security.MessageDigest

@OptIn(ExperimentalCoroutinesApi::class)
class SecretKeyGuardTest {
    private val secret = "QAID_SECRET_abcdefghijklmnopqrstuvwxyz0123456789ABCD"
    private val pub = "QAID_PUB_abcdefghijklmnopqrstuvwxyz0123456789"

    private fun sha256(text: String) =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    @Before @After fun forget() = SecretKeyReports.reset()

    @Test fun aCleanConfigIsUntouched() {
        val config = QaidQuestsConfig(apiKey = pub, appName = "Crew", themeId = "t1", visitorId = "v1")
        val found = mutableListOf<String>()
        assertSame(config, SecretKeyGuard.scrub(config, found))
        assertTrue(found.isEmpty())
    }

    @Test fun aSecretApiKeyIsDropped() {
        val found = mutableListOf<String>()
        val clean = SecretKeyGuard.scrub(QaidQuestsConfig(apiKey = secret, appName = "Crew"), found)
        assertEquals(listOf(secret), found)
        assertEquals("", clean.apiKey)
        assertEquals("Crew", clean.appName)
        assertEquals(QaidQuestsConfig.DEFAULT_BASE, clean.base)
        // The theme URL carries the key; it is the cleaned one.
        assertFalse(clean.copy(themeId = "t").themeUrl()!!.contains(secret))
    }

    @Test fun secretsAnywhereSentAreDropped() {
        val config = QaidQuestsConfig(
            apiKey = pub,
            appName = "Crew $secret",
            base = "https://stg.qaid.dev/api/quests?k=$secret",
            pageUrl = "https://acme.com/app/$secret",
            themeId = secret,
            visitorId = "v $secret",
        )
        val found = mutableListOf<String>()
        val clean = SecretKeyGuard.scrub(config, found)
        assertEquals(5, found.size)
        assertEquals(pub, clean.apiKey)
        assertEquals("Crew", clean.appName)
        assertEquals("https://stg.qaid.dev/api/quests?k=", clean.base)
        assertEquals("https://acme.com/app/", clean.pageUrl)
        assertEquals("", clean.themeId)
        assertEquals("v", clean.visitorId)
    }

    @Test fun aBaseThatIsOnlyASecretFallsBackToQaid() {
        val found = mutableListOf<String>()
        assertEquals(QaidQuestsConfig.DEFAULT_BASE, SecretKeyGuard.scrub(QaidQuestsConfig(apiKey = pub, appName = "A", base = secret), found).base)
        assertEquals(listOf(secret), found)
    }

    @Test fun aSecretInMetadataIsDropped() {
        val found = mutableListOf<String>()
        assertEquals(mapOf("feedbackId" to "fb_1", "token" to "x"), SecretKeyGuard.scrub(mapOf("feedbackId" to "fb_1", "token" to "x $secret"), found))
        assertEquals(QaidUser(id = "", email = "a@b.co"), SecretKeyGuard.scrub(QaidUser(id = secret, email = "a@b.co"), found))
        assertNull(SecretKeyGuard.scrub(null as QaidUser?, found))
        assertNull(SecretKeyGuard.scrubOrNull(null, found))
        assertEquals(listOf(secret, secret), found)
        // Blank after cleaning: the user is left out, as an empty one is.
        assertNull(SecretKeyGuard.scrub(QaidUser(id = secret), found)?.cleaned())
    }

    @Test fun theEmbedKeyAndAShortTailDoNotMatch() {
        val found = mutableListOf<String>()
        SecretKeyGuard.scrub(pub, found)
        SecretKeyGuard.scrub("QAID_SECRET_short", found)
        SecretKeyGuard.scrub("QAID_SECRET_0123456789abcde", found) // 15
        SecretKeyGuard.scrub("qd_live_abcdefghijklmnopqrstuvwxyz0123456789ABCD", found) // old format: cut off server-side
        assertTrue(found.isEmpty())
        SecretKeyGuard.scrub("QAID_SECRET_0123456789abcdef", found) // 16
        assertEquals(listOf("QAID_SECRET_0123456789abcdef"), found)
    }

    @Test fun theReportEndpointIsOnTheBasesOrigin() {
        assertEquals("https://qaid.dev/api/leaked-key", SecretKeyGuard.reportUrl(QaidQuestsConfig.DEFAULT_BASE))
        assertEquals("http://10.0.2.2:4321/api/leaked-key", SecretKeyGuard.reportUrl(" http://10.0.2.2:4321/api/quests "))
        assertNull(SecretKeyGuard.reportUrl("/api/quests"))
        assertNull(SecretKeyGuard.reportUrl("mailto:team@example.com"))
        assertNull(SecretKeyGuard.reportUrl("http://["))
        assertNull(SecretKeyGuard.reportRequest(secret, "/api/quests", null))
    }

    @Test fun theReportCarriesTheHashNotTheKey() {
        val request = SecretKeyGuard.reportRequest(secret, "https://stg.qaid.dev/api/quests", "app://com.acme.crew")!!
        assertEquals("POST", request.method)
        assertEquals("https://stg.qaid.dev/api/leaked-key", request.url)
        assertEquals(mapOf("Content-Type" to "text/plain"), request.headers)
        val json = JSONObject(request.body!!)
        assertEquals(sha256(secret), json.getString("keyHash"))
        assertEquals("app://com.acme.crew", json.getString("pageUrl"))
        assertEquals("quests-android", json.getString("source"))
        assertEquals(3, json.length())
        assertFalse(request.body!!.contains(secret))
        assertFalse(JSONObject(SecretKeyGuard.reportBody(secret, null)).has("pageUrl"))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", SecretKeyGuard.sha256Hex(""))
    }

    @Test fun reportsOncePerProcessAndLogsEachTime() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeTransport()
        val logs = mutableListOf<String>()
        repeat(2) { SecretKeyReports.report(listOf(secret), QaidQuestsConfig.DEFAULT_BASE, "app://x", transport, this) { logs += it } }
        assertEquals(listOf(SecretKeyGuard.MESSAGE, SecretKeyGuard.MESSAGE), logs)
        assertTrue(SecretKeyGuard.MESSAGE.contains("QAID_PUB_"))
        assertEquals(1, transport.requests.size)
        assertEquals("https://qaid.dev/api/leaked-key", transport.requests[0].url)
        assertEquals("app://x", JSONObject(transport.requests[0].body!!).getString("pageUrl"))
    }

    @Test fun nothingFoundOrNoHostSendsNothing() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeTransport()
        var logs = 0
        SecretKeyReports.report(emptyList(), QaidQuestsConfig.DEFAULT_BASE, null, transport, this) { logs++ }
        assertEquals(0, logs)
        SecretKeyReports.report(listOf(secret), "/api/quests", null, transport, this) { logs++ }
        assertEquals(1, logs)
        assertTrue(transport.requests.isEmpty())
    }

    @Test fun aThrowingLogOrSendIsSwallowed() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeTransport { throw IOException("offline") }
        SecretKeyReports.report(listOf(secret), QaidQuestsConfig.DEFAULT_BASE, null, transport, this) { error("no log") }
        assertEquals(1, transport.requests.size)
    }
}
