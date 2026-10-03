package dev.qaid.quests.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val device = DeviceInfo(
    osVersion = "15", model = "Google Pixel 8", appName = "CinemaCrew", appVersion = "1.4", build = "812", locale = "en-US",
)

class MetadataTest {
    @Test fun builtInKeysStayFlatAndExtraWins() {
        val meta = QuestMetadata.build(device, "Errands", extra = mapOf("feedbackId" to "fb1", "source" to "override"))
        assertEquals(
            setOf("platform", "osVersion", "device", "app", "appVersion", "build", "locale", "sdk", "source", "screen", "feedbackId"),
            meta.keys().asSequence().toSet(),
        )
        assertEquals("qaid-quests-android/0.1.0", meta.getString("sdk"))
        assertEquals("override", meta.getString("source"))
        assertEquals("fb1", meta.getString("feedbackId"))
        assertEquals("native-quest", QuestMetadata.build(device, null).getString("source"))
        assertFalse(QuestMetadata.build(device, null).has("screen"))
        assertFalse(QuestMetadata.build(device, "").has("screen"))
    }

    @Test fun userAndCustomNest() {
        val meta = QuestMetadata.build(device, null, QaidUser(id = "u1", email = "a@b.c", name = " Ann "), mapOf("plan" to "pro"))
        val user = meta.getJSONObject("user")
        assertEquals("u1", user.getString("id"))
        assertEquals("Ann", user.getString("name"))
        assertEquals("pro", meta.getJSONObject("custom").getString("plan"))
        assertFalse(QuestMetadata.build(device, null, QaidUser(" "), emptyMap()).has("user"))
        assertFalse(QuestMetadata.build(device, null, null, emptyMap()).has("custom"))
        assertNull(QaidUser().cleaned())
        assertEquals("""{"email":"e"}""", QaidUser(email = "e").toJson().toString())
        assertEquals(QaidUser(email = "e"), QaidUser(email = "e").cleaned())
        assertEquals(QaidUser(name = "n"), QaidUser(name = " n").cleaned())
        val noEmail = QaidUser(id = "i", name = "n").toJson()
        assertEquals("i", noEmail.getString("id"))
        assertFalse(noEmail.has("email"))
        assertEquals(1, QaidUser(id = "i").toJson().length())
    }

    @Test fun customMetadataLimits() {
        val custom = CustomMetadata()
        custom.set("  ", "ignored")
        custom.set("k".repeat(100), "v".repeat(600))
        val (key, value) = custom.snapshot().entries.single()
        assertEquals(64, key.length)
        assertEquals(500, value.length)
        custom.set(key, null)
        assertTrue(custom.snapshot().isEmpty())
        repeat(40) { custom.set("k$it", "v") }
        assertEquals(30, custom.snapshot().size)
        custom.set("k0", "changed")
        assertEquals("changed", custom.snapshot()["k0"])
        custom.clear()
        assertTrue(custom.snapshot().isEmpty())
    }

    @Test fun deviceAndUserAgent() {
        assertEquals("CinemaCrew/1.4 (812; Android 15; Google Pixel 8) QaidQuests/0.1.0", device.userAgent)
        assertEquals("ios/1 (2; ios 18; x) QaidQuests/0.1.0", DeviceInfo("18", "x", "ios", "1", "2", "en", platform = "ios").userAgent)
        assertEquals("Google Pixel 8", DeviceInfo.model("Google", "Pixel 8"))
        assertEquals("OnePlus 9", DeviceInfo.model("OnePlus", "OnePlus 9"))
    }

    @Test fun pageUrls() {
        val config = QaidQuestsConfig(apiKey = "k", appName = "A")
        assertEquals("app://com.example.app", config.pageUrlBase("com.Example.App"))
        assertEquals("https://x.dev/app", config.copy(pageUrl = "https://x.dev/app").pageUrlBase("p"))
        assertEquals("app://p", config.copy(pageUrl = " ").pageUrlBase("p"))
        assertEquals("app://p/call-sheets", PageUrls.pageUrl("app://p/", "  Call Sheets!"))
        assertEquals("app://p", PageUrls.pageUrl("app://p", "!!"))
        assertEquals("app://p", PageUrls.pageUrl("app://p", null))
        assertEquals("a1-b", PageUrls.slug("-A1 -- b-"))
        assertEquals("caf-x", PageUrls.slug("café~{x"))
        assertEquals(60, PageUrls.slug("x".repeat(80)).length)
    }

    @Test fun themeUrls() {
        val config = QaidQuestsConfig(apiKey = "k ey", appName = "A", base = "http://localhost:4321/api/quests", themeId = "th1")
        assertEquals("http://localhost:4321/api/quest-themes/th1/public?key=k%20ey", config.themeUrl())
        assertEquals("http://localhost:4321/api/quest-themes/th1/v/3/public?key=k%20ey", config.copy(themeVersion = 3).themeUrl())
        assertEquals("https://qaid.dev/api/quest-themes/th1/public?key=k", QaidQuestsConfig("k", "A", themeId = " th1 ").themeUrl())
        assertNull(config.copy(themeId = null).themeUrl())
        assertNull(config.copy(themeId = " ").themeUrl())
        assertEquals("https://qaid.dev", QaidQuestsConfig.originOf("not a url"))
        assertEquals("https://qaid.dev", QaidQuestsConfig.originOf("::bad"))
        assertEquals("https://qaid.dev", QaidQuestsConfig.originOf("mailto:x@y"))
        assertEquals("https://qaid.dev", QaidQuestsConfig.originOf("/api/quests"))
        assertEquals("https://staging.qaid.dev", QaidQuestsConfig.originOf("https://staging.qaid.dev/api/quests"))
    }
}
