package dev.qaid.quests.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import dev.qaid.quests.QaidQuests
import dev.qaid.quests.core.FakeTransport
import dev.qaid.quests.core.HttpResponse
import dev.qaid.quests.core.QaidQuestTheme
import dev.qaid.quests.core.QaidQuestsConfig
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Draws the README's screenshots; not a test. Skipped unless QAID_SCREENSHOT_DIR is set:
 *
 *   QAID_SCREENSHOT_DIR=../docs/screenshots \
 *     ./gradlew --no-daemon :qaidquests:testDebugUnitTest --tests '*ReadmeScreenshots*'
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w393dp-h420dp-xhdpi")
class ReadmeScreenshots {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = System.getenv("QAID_SCREENSHOT_DIR")?.let(::File)

    private fun show(theme: QaidQuestTheme? = null) {
        assumeTrue(outDir != null)
        QaidQuests.transport = FakeTransport { r ->
            if (r.url.endsWith("/definition")) HttpResponse(200, COFFEE) else FakeTransport.defaultResponse(r)
        }
        QaidQuests.configure(compose.activity, QaidQuestsConfig(apiKey = "key", appName = "Northwind", theme = theme))
        compose.setContent { QaidQuest("coffee", Modifier.fillMaxSize(), screen = "Home") }
        compose.waitForIdle()
    }

    private fun save(name: String) {
        compose.waitForIdle()
        outDir!!.mkdirs()
        // captureToImage waits on a redraw Robolectric never signals; drawing the window's
        // views into a bitmap is the same picture.
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(bitmap)) }
        File(outDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun choiceLight() {
        show(QaidQuestTheme(mode = QaidQuestTheme.Mode.LIGHT))
        compose.onNodeWithTag(QaidQuestTags.option("weekly")).performClick()
        save("choice-light")
    }

    @Test fun rangeDark() {
        show(QaidQuestTheme(mode = QaidQuestTheme.Mode.DARK, preset = QaidQuestTheme.Preset.PILL, accent = 0xFFD97706.toInt()))
        compose.onNodeWithTag(QaidQuestTags.option("daily")).performClick()
        compose.onNodeWithTag(QaidQuestTags.PRIMARY).performClick()
        save("range-dark")
    }

    private companion object {
        const val COFFEE = """{"id":"coffee","title":"Help us roast better coffee",
            "description":"Three quick questions. Takes about 30 seconds.",
            "questions":[
            {"id":"often","type":"multiple-choice","label":"How often do you brew at home?","required":true,
             "options":[{"value":"daily","label":"Every day"},{"value":"weekly","label":"A few times a week"},{"value":"rarely","label":"Now and then"}]},
            {"id":"roast","type":"range","label":"How dark do you like your roast?","min":1,"max":10,"defaultValue":6,"unit":"/10"},
            {"id":"note","type":"text","label":"Anything we should try?","multiline":true}]}"""
    }
}
