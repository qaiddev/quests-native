package dev.qaid.quests.ui

import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.semantics.SemanticsActions
import dev.qaid.quests.QaidQuests
import dev.qaid.quests.core.FakeTransport
import dev.qaid.quests.core.HttpRequest
import dev.qaid.quests.core.HttpResponse
import dev.qaid.quests.core.QaidQuestErrorEvent
import dev.qaid.quests.core.QaidQuestTheme
import dev.qaid.quests.core.QaidQuestThemeMode
import dev.qaid.quests.core.QaidQuestsConfig
import dev.qaid.quests.core.QuestAnswer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import android.os.Looper
import java.io.File
import java.io.IOException
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class QaidQuestUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var server: FakeTransport
    private var definition = SAMPLE

    @Before fun setUp() {
        server = FakeTransport { r -> if (r.url.endsWith("/definition")) HttpResponse(200, definition) else FakeTransport.defaultResponse(r) }
        QaidQuests.transport = server
        QaidQuests.configure(compose.activity, QaidQuestsConfig(apiKey = "key", appName = "App"))
        QaidQuests.onError = null
    }

    @After fun tearDown() {
        File(compose.activity.noBackupFilesDir, "qaid-quests-queue").deleteRecursively()
    }

    private fun show(questId: String = "quest1", onComplete: (Map<String, QuestAnswer>) -> Unit = {}, onClose: (() -> Unit)? = null) {
        compose.setContent { QaidQuest(questId, Modifier.fillMaxSize(), screen = "Home", onComplete = onComplete, onClose = onClose) }
        compose.waitForIdle()
    }

    private fun idleMain(ms: Long = 0) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
        compose.waitForIdle()
    }

    private fun next() = compose.onNodeWithTag(QaidQuestTags.PRIMARY).performClick().also { compose.waitForIdle() }

    @Test fun choiceStepValidatesThenMovesForwardAndBack() {
        show()
        compose.onNodeWithText("Tell us").assertIsDisplayed()
        compose.onNodeWithText("Your role").assertExists()
        compose.onNodeWithText("Product").assertIsDisplayed()
        compose.onNodeWithText("1 / 2").assertIsDisplayed()
        next()
        compose.onNodeWithTag(QaidQuestTags.ERROR).assertIsDisplayed()
        compose.onNodeWithText("Please select an option.").assertIsDisplayed()
        compose.onNodeWithTag(QaidQuestTags.option("dev")).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(QaidQuestTags.option("dev")).assertIsSelected()
        compose.onNodeWithTag(QaidQuestTags.ERROR).assertDoesNotExist()
        compose.onNodeWithText("1 / 3").assertIsDisplayed()
        next()
        compose.onNodeWithText("Why?").assertIsDisplayed()
        compose.onNodeWithTag(QaidQuestTags.BACK).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Your role").assertExists()
        val create = JSONObject(server.requests.first { it.method == "POST" }.body!!)
        assertTrue(create.getString("pageUrl").endsWith("/home"))
        assertEquals("Home", create.getJSONObject("metadata").getString("screen"))
    }

    @Test fun textStepTypesAndSubmitsToTheThankYouScreen() {
        definition = """{"id":"q","title":"T","thankYouMessage":"Bye","questions":[
            {"id":"name","label":"Name","type":"text","required":true,"minLength":2,"placeholder":"Your name","maxLength":5}]}"""
        var done: Map<String, QuestAnswer>? = null
        show(onComplete = { done = it })
        next()
        compose.onNodeWithText("Please enter at least 2 characters.").assertIsDisplayed()
        compose.onNodeWithTag(QaidQuestTags.TEXT).performTextInput("Ann-Marie")
        compose.waitForIdle()
        compose.onNodeWithText("Submit").assertIsDisplayed()
        next()
        idleMain()
        compose.onNodeWithText("Thank you!").assertIsDisplayed()
        compose.onNodeWithText("Bye").assertIsDisplayed()
        assertEquals(mapOf("name" to QuestAnswer.Text("Ann-M")), done)
        assertTrue(server.patches().contains("""{"questionId":"name","value":"Ann-M"}"""))
    }

    @Test fun currencyRangeAndDateSteps() {
        definition = """{"id":"q","progressPosition":"bottom","questions":[
            {"id":"c","label":"Budget","type":"currency","currency":"EUR","min":1},
            {"id":"r","label":"Weight","type":"range","min":0,"max":10,"step":1,"defaultValue":5,"unit":"kg"},
            {"id":"d","label":"When","type":"date","required":true}]}"""
        show()
        compose.onNodeWithText("€").assertExists()
        compose.onNodeWithTag(QaidQuestTags.CURRENCY).performTextInput("0.5")
        next()
        compose.onNodeWithText("Please enter a valid amount.").assertIsDisplayed()
        compose.onNodeWithTag(QaidQuestTags.CURRENCY).performTextReplacement("12,5")
        compose.onNodeWithTag(QaidQuestTags.CURRENCY).performImeAction()
        compose.waitForIdle()
        compose.onNodeWithText("5").assertIsDisplayed()
        compose.onNodeWithText("0kg").assertIsDisplayed()
        compose.onNodeWithText("10kg").assertIsDisplayed()
        compose.onNodeWithTag(QaidQuestTags.RANGE).performSemanticsAction(SemanticsActions.SetProgress) { it(8f) }
        compose.waitForIdle()
        compose.onNodeWithText("8").assertIsDisplayed()
        next()
        compose.onNodeWithText("Pick a date").assertIsDisplayed()
        next()
        compose.onNodeWithText("Please pick a date.").assertIsDisplayed()
        compose.onNodeWithTag(QaidQuestTags.DATE).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(QaidQuestTags.BACK).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("8").assertIsDisplayed()
        val patches = server.patches()
        assertTrue(patches.contains("""{"questionId":"c","value":12.5}"""))
        assertTrue(patches.contains("""{"questionId":"r","value":8}"""))
    }

    @Test fun multiSelectTogglesInClickOrderWithImageTiles() {
        definition = """{"id":"q","questions":[
            {"id":"m","label":"Pick","type":"multiple-choice","multiple":true,"required":true,"imageAlignment":"vertical",
             "options":[{"value":"a","label":"Apple","image":"https://example.invalid/a.png"},{"value":"b","label":"Banana"}]},
            {"id":"t","label":"Then","type":"text"}]}"""
        show()
        next()
        compose.onNodeWithText("Please select at least one option.").assertIsDisplayed()
        compose.onNodeWithTag(QaidQuestTags.option("b")).performClick()
        compose.onNodeWithTag(QaidQuestTags.option("a")).performClick()
        compose.onNodeWithTag(QaidQuestTags.option("a")).performClick()
        compose.onNodeWithTag(QaidQuestTags.option("a")).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(QaidQuestTags.option("a")).assertIsOn()
        next()
        compose.onNodeWithText("Then").assertIsDisplayed()
        compose.onNodeWithTag(QaidQuestTags.BACK).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(QaidQuestTags.option("b")).assertIsOn()
        compose.onNodeWithTag(QaidQuestTags.option("b")).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(QaidQuestTags.option("b")).assertIsOff()
        assertTrue(server.patches().contains("""{"questionId":"m","value":["b","a"]}"""))
    }

    @Test fun loadErrorOffersRetry() {
        server.respond = { r -> if (r.url.endsWith("/definition")) HttpResponse(404, """{"error":"Quest not found"}""") else FakeTransport.defaultResponse(r) }
        val events = mutableListOf<QaidQuestErrorEvent>()
        QaidQuests.onError = { events += it }
        show()
        compose.onNodeWithText("Couldn't load form").assertIsDisplayed()
        compose.onNodeWithText("Failed to load questionnaire (404)").assertIsDisplayed()
        idleMain()
        assertEquals(1, events.size)
        server.respond = { r -> if (r.url.endsWith("/definition")) HttpResponse(200, SAMPLE) else FakeTransport.defaultResponse(r) }
        compose.onNodeWithTag(QaidQuestTags.RETRY).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Your role").assertExists()
    }

    @Test fun submitErrorOffersTryAgain() {
        definition = """{"id":"q","questions":[{"id":"a","label":"Only","type":"text"}]}"""
        server.respond = { r ->
            when {
                r.url.endsWith("/definition") -> HttpResponse(200, definition)
                r.url.endsWith("/submit") -> HttpResponse(410, """{"error":"Quest is archived"}""")
                else -> FakeTransport.defaultResponse(r)
            }
        }
        show()
        next()
        idleMain()
        compose.onNodeWithText("Couldn't send your answers").assertIsDisplayed()
        compose.onNodeWithText("Your answers are still here. Check your connection and try again.").assertIsDisplayed()
        server.respond = { r -> if (r.url.endsWith("/definition")) HttpResponse(200, definition) else FakeTransport.defaultResponse(r) }
        compose.onNodeWithText("Try again").performClick()
        idleMain()
        compose.onNodeWithText("Thank you!").assertIsDisplayed()
    }

    @Test fun offlineSubmitIsSavedAndSaysSo() {
        definition = """{"id":"q","questions":[{"id":"a","label":"Only","type":"text"}]}"""
        server.respond = { r: HttpRequest -> if (r.method == "GET") HttpResponse(200, definition) else throw IOException("offline") }
        show()
        compose.onNodeWithTag(QaidQuestTags.TEXT).performTextInput("hi")
        next()
        // Create and save retries back off 1 s then 3 s before giving up.
        idleMain(5_000)
        idleMain(5_000)
        idleMain(5_000)
        compose.onNodeWithText("Thank you!").assertIsDisplayed()
        compose.onNodeWithText("Your answers are saved on this device and will be sent when you're back online.").assertIsDisplayed()
        val dir = File(compose.activity.noBackupFilesDir, "qaid-quests-queue")
        assertEquals(1, dir.listFiles().orEmpty().size)

        // Back online: configuring again (as the next launch does) sends it, create → save → submit.
        server.respond = { r: HttpRequest -> if (r.method == "GET") HttpResponse(200, definition) else FakeTransport.defaultResponse(r) }
        QaidQuests.configure(compose.activity, QaidQuestsConfig(apiKey = "key", appName = "App"))
        compose.waitUntil(10_000) { dir.listFiles().orEmpty().isEmpty() }
        val sent = server.requests.filter { it.method != "GET" }.takeLast(3).map { it.method + " " + it.url.substringAfter("/api/quests") }
        assertEquals(listOf("POST /responses", "PATCH /responses/r1", "POST /responses/r1/submit"), sent)
    }

    @Test fun unsupportedQuestionShowsACardAndCloseCallsBack() {
        definition = """{"id":"q","questions":[{"id":"s","label":"Sign here","type":"signature","required":true},{"id":"t","label":"After","type":"text"}]}"""
        var closed = 0
        show(onClose = { closed++ })
        compose.onNodeWithText("This question can't be shown in this version of the app.").assertIsDisplayed()
        next()
        compose.onNodeWithText("After").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close form").performClick()
        assertEquals(1, closed)
    }

    @Test fun progressBarSpeaksItsPosition() {
        show()
        compose.onNodeWithTag(QaidQuestTags.PROGRESS).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Question 1 of 2"),
        )
        compose.onNodeWithContentDescription("Your role, required").assertExists()
    }

    @Test fun themedAndDark() {
        QaidQuests.configure(
            compose.activity,
            QaidQuestsConfig(
                apiKey = "key", appName = "App",
                theme = QaidQuestTheme(accent = 0xFF3B82F6.toInt(), mode = QaidQuestThemeMode.DARK, fontFamily = "serif", preset = "pill"),
            ),
        )
        show()
        compose.onNodeWithText("Your role").assertExists()
        assertEquals(androidx.compose.ui.text.font.FontFamily.Serif, fontFamily(listOf("serif")))
        assertEquals(androidx.compose.ui.text.font.FontFamily.Monospace, fontFamily(listOf("ui-monospace")))
        assertEquals(androidx.compose.ui.text.font.FontFamily.Cursive, fontFamily(listOf("cursive")))
        assertEquals(androidx.compose.ui.text.font.FontFamily.Default, fontFamily(listOf("system-ui")))
        assertEquals(androidx.compose.ui.text.font.FontFamily.Default, fontFamily(emptyList()))
        fontFamily(listOf("sans-serif-condensed"))
    }

    @Test fun questViewHostsTheComposable() {
        compose.runOnUiThread {
            val view = QaidQuestView(compose.activity).apply {
                questId = "quest1"
                screen = "Settings"
            }
            compose.activity.setContentView(view)
        }
        compose.waitForIdle()
        compose.onNodeWithText("Your role").assertExists()
        compose.runOnUiThread { compose.activity.setContentView(View(compose.activity)) }
        compose.waitForIdle()
    }

    @Test fun presentShowsASheetThatClosesWithoutSubmitting() {
        var closed = 0
        compose.runOnUiThread { QaidQuests.present(compose.activity, "quest1", onClose = { closed++ }) }
        compose.waitForIdle()
        compose.onNodeWithText("Your role").assertExists()
        compose.onNodeWithTag(QaidQuestTags.option("pm")).performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Close form").performClick()
        compose.waitForIdle()
        assertEquals(1, closed)
        assertEquals(0, server.count("POST", "/submit"))
        compose.onNodeWithText("Your role").assertDoesNotExist()
    }

    @Test fun presentedThankYouHasAClose() {
        definition = """{"id":"q","questions":[{"id":"a","label":"Only","type":"text"}]}"""
        var closed = 0
        compose.runOnUiThread { QaidQuests.present(compose.activity, "q", onClose = { closed++ }) }
        compose.waitForIdle()
        next()
        idleMain()
        compose.onNodeWithText("Thank you!").assertExists()
        compose.onNode(hasText("Close")).performClick()
        compose.waitForIdle()
        assertEquals(1, closed)
    }

    private companion object {
        val SAMPLE = dev.qaid.quests.core.SAMPLE_QUEST
    }
}
