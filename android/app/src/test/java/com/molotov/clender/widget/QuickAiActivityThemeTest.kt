package com.molotov.clender.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelProvider
import com.molotov.clender.ui.ai.AiSubmissionViewModel
import com.molotov.clender.ui.foundation.ThemeMode
import com.molotov.clender.ui.theme.clenderColorScheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], application = QuickAiTestApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class QuickAiActivityThemeTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val application: QuickAiTestApplication
        get() = RuntimeEnvironment.getApplication() as QuickAiTestApplication

    private var controller: ActivityController<QuickAiActivity>? = null

    @After
    fun releaseActivityObservers() {
        controller?.pause()?.stop()?.destroy()
        controller = null
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, application.repository.collectors)
        assertEquals(0, application.appearance.subscriptionCount.value)
    }

    @Test
    fun readyDarkActivityPaintsThemeBackgroundAcrossBlankContentEdges() {
        launch(ThemeMode.DARK)
        assertContentBackground(dark = true)
        assertTrue(application.gateway.submissions.isEmpty())
    }

    @Test
    fun appearanceFlowSwitchesActualPixelsBothDirectionsWithoutReopening() {
        launch(ThemeMode.LIGHT)
        assertContentBackground(dark = false)
        changeTheme(ThemeMode.DARK)
        assertContentBackground(dark = true)
        changeTheme(ThemeMode.LIGHT)
        assertContentBackground(dark = false)
        assertEquals(1, application.modelCreations)
    }

    @Test
    fun loadingAndReadyShareDarkBackgroundWithoutWindowColorLeak() {
        val gate = CompletableDeferred<Unit>()
        application.repository.readGate = gate
        launch(ThemeMode.DARK)
        composeRule.onNodeWithTag("quick_ai_activity_loading").assertExists()
        assertContentBackground(dark = true)
        composeRule.runOnIdle { gate.complete(Unit) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("quick_ai_activity_loading").assertDoesNotExist()
        assertContentBackground(dark = true)
    }

    @Test
    fun loadErrorAndRetryKeepDarkBackground() {
        application.repository.failReads = true
        launch(ThemeMode.DARK)
        composeRule.onNodeWithTag("quick_ai_activity_error").assertExists()
        assertContentBackground(dark = true)
        application.repository.failReads = false
        composeRule.onNodeWithTag("quick_ai_activity_retry").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("quick_ai_activity_error").assertDoesNotExist()
        assertContentBackground(dark = true)
    }

    @Config(qualifiers = "en-rUS-w360dp-h640dp-night-420dpi")
    @Test
    fun systemNightUsesDarkPixelsAndExplicitLightOverridesSystem() {
        launch(ThemeMode.SYSTEM)
        assertContentBackground(dark = true)
        changeTheme(ThemeMode.LIGHT)
        assertContentBackground(dark = false)
    }

    @Config(qualifiers = "zh-rCN-w640dp-h360dp-land-420dpi")
    @Test
    fun recreateRetainsDarkPixelsAndDraftAtDoubleFontScaleAndFontBoundaries() {
        RuntimeEnvironment.setFontScale(2f)
        application.appearance.value = application.appearance.value.copy(appFontSizeSp = 20)
        launch(ThemeMode.DARK)
        composeRule.onNodeWithTag("quick_ai_input").performScrollTo()
            .performTextInput(QUICK_AI_TEST_DRAFT)
        assertContentBackground(dark = true)
        requireNotNull(controller).recreate()
        composeRule.waitForIdle()
        assertContentBackground(dark = true)
        val submission = ViewModelProvider(requireNotNull(controller).get())[
            AiSubmissionViewModel::class.java
        ]
        assertEquals(QUICK_AI_TEST_DRAFT, submission.state.value.draft)
        composeRule.runOnIdle {
            application.appearance.value = application.appearance.value.copy(appFontSizeSp = 8)
        }
        assertContentBackground(dark = true)
        assertTrue(application.gateway.submissions.isEmpty())
    }

    private fun launch(theme: ThemeMode) {
        application.appearance.value = application.appearance.value.copy(themeMode = theme)
        bindQuickAiTestWidget(application)
        controller = Robolectric.buildActivity(
            QuickAiActivity::class.java,
            quickAiTestIntent(application)
        ).create().start().resume().visible()
        composeRule.waitForIdle()
    }

    private fun changeTheme(theme: ThemeMode) {
        composeRule.runOnIdle {
            application.appearance.value = application.appearance.value.copy(themeMode = theme)
        }
    }

    private fun assertContentBackground(dark: Boolean) {
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            val activity = requireNotNull(controller).get()
            val decor = activity.window.decorView
            val content = activity.findViewById<View>(android.R.id.content)
            assertTrue(decor.width > 0 && decor.height > 0)
            assertTrue(content.width > 2 && content.height > 4)
            val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
            try {
                decor.draw(Canvas(bitmap))
                val decorLocation = IntArray(2).also(decor::getLocationInWindow)
                val contentLocation = IntArray(2).also(content::getLocationInWindow)
                val left = contentLocation[0] - decorLocation[0]
                val top = contentLocation[1] - decorLocation[1]
                val expected = clenderColorScheme(dark).background.toArgb()
                listOf(1, content.width - 2).forEach { x ->
                    listOf(1, 2, 3).forEach { quarter ->
                        val y = content.height * quarter / 4
                        assertEquals(
                            "Quick AI blank content pixel ($x,$y), dark=$dark",
                            expected,
                            bitmap.getPixel(left + x, top + y)
                        )
                    }
                }
            } finally {
                bitmap.recycle()
            }
        }
    }
}
