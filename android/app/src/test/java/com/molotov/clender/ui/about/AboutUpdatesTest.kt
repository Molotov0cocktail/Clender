package com.molotov.clender.ui.about

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.molotov.clender.testsupport.RobolectricComposeHost
import com.molotov.clender.ui.foundation.AppearanceUiState
import com.molotov.clender.ui.foundation.ThemeMode
import com.molotov.clender.ui.theme.ClenderTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en-rUS-w360dp-h640dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AboutUpdatesTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val host = RobolectricComposeHost()
    private lateinit var context: ReleaseContext

    @Before
    fun start() {
        host.start()
        context = ReleaseContext(host.activity)
    }

    @After
    fun close() = host.close()

    @Test
    fun enteringAboutDoesNotOpenBrowserAndBothExplicitButtonsUseFixedOfficialPages() {
        render()
        assertTrue(context.attempts.isEmpty())
        composeRule.onNodeWithTag("about_updates_title").performScrollTo()
            .assertTextEquals("App updates")
        composeRule.onNodeWithTag("about_update_gitee").performScrollTo()
            .assertTextEquals("Gitee releases")
        click("about_update_github")
        click("about_update_gitee")
        assertEquals(
            listOf(
                "https://github.com/Molotov0cocktail/Clender/releases/latest",
                "https://gitee.com/Molotov0coaktail/clender/releases"
            ),
            context.attempts.map { it.dataString }
        )
        context.attempts.forEach { intent ->
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertTrue(intent.hasCategory(Intent.CATEGORY_BROWSABLE))
            assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
            assertNull(intent.extras)
            assertNull(intent.component)
        }
    }

    @Test
    fun missingBrowserShowsSafeErrorAndSuccessfulRetryClearsIt() {
        render()
        context.failure = ActivityNotFoundException("private endpoint api-key exception")
        click("about_update_github")
        composeRule.onNodeWithTag("about_update_error").performScrollTo()
            .assertTextEquals("Could not open the release page. Please try again.")
        context.failure = null
        click("about_update_gitee")
        composeRule.onNodeWithTag("about_update_error").assertDoesNotExist()
        assertEquals(2, context.attempts.size)
    }

    @Test
    fun securityFailureRemainsRetryableAndDoesNotClaimAnUpdateWasInstalled() {
        render()
        context.failure = SecurityException("private provider credentials")
        repeat(2) { click("about_update_github") }
        composeRule.onNodeWithTag("about_update_error").performScrollTo()
            .assertTextEquals("Could not open the release page. Please try again.")
        assertEquals(2, context.attempts.size)
    }

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h640dp-420dpi")
    fun unknownMetadataStillHasChineseUpdatesAndLargeDarkTextButtonsRemainReachable() {
        render(large = true, withModel = true)
        composeRule.onNodeWithTag("about_version_name").performScrollTo()
            .assertTextEquals("版本名称：不可用")
        composeRule.onNodeWithTag("about_updates_title").performScrollTo()
            .assertTextEquals("应用更新")
        composeRule.onNodeWithTag("about_update_github").performScrollTo().assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
        click("about_update_github")
        composeRule.onNodeWithTag("about_update_gitee").performScrollTo().assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .assertTextEquals("Gitee 发布页")
        click("about_update_gitee")
        assertEquals(2, context.attempts.size)
        composeRule.onNodeWithTag("about_ai_policy").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun bothReleaseButtonsPreserveTheSameBackgroundInLightAndDarkThemes() {
        val theme = mutableStateOf(ThemeMode.LIGHT)
        val backdrop = Color(0xFF41A383)
        host.activity.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                ClenderTheme(AppearanceUiState(theme.value, 13, 13)) {
                    Box(Modifier.size(360.dp, 640.dp).background(backdrop)) {
                        AboutUpdatesSection { true }
                    }
                }
            }
        }
        for (mode in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
            composeRule.runOnIdle { theme.value = mode }
            listOf("about_update_github", "about_update_gitee").forEach { tag ->
                val bounds = composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
                val actual = composeRule.runOnUiThread {
                    val view = host.activity.window.decorView
                    val bitmap = Bitmap.createBitmap(
                        view.width,
                        view.height,
                        Bitmap.Config.ARGB_8888
                    )
                    try {
                        view.draw(Canvas(bitmap))
                        bitmap.getPixel(bounds.center.x.toInt(), bounds.top.toInt() + 6)
                    } finally {
                        bitmap.recycle()
                    }
                }
                assertEquals(
                    "Both release buttons must preserve background: $tag/$mode",
                    backdrop.toArgb(),
                    actual
                )
            }
        }
    }

    @Test
    fun releaseButtonsHaveAtLeastTwelveDpSeparation() {
        host.activity.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                ClenderTheme(AppearanceUiState(ThemeMode.LIGHT, 13, 13)) {
                    Box(Modifier.size(360.dp, 640.dp)) {
                        AboutUpdatesSection { true }
                    }
                }
            }
        }
        val github = composeRule.onNodeWithTag("about_update_github")
            .fetchSemanticsNode().boundsInRoot
        val gitee = composeRule.onNodeWithTag("about_update_gitee")
            .fetchSemanticsNode().boundsInRoot
        assertTrue(
            "Release actions need at least 12dp separation",
            gitee.top - github.bottom >= 12f
        )
    }

    private fun click(tag: String) {
        composeRule.onNodeWithTag(tag).performScrollTo().performClick()
        composeRule.waitForIdle()
    }

    private fun render(large: Boolean = false, withModel: Boolean = false) {
        composeRule.runOnUiThread {
            host.activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalContext provides context,
                    LocalDensity provides Density(density.density, if (large) 2f else 1f)
                ) {
                    ClenderTheme(
                        AppearanceUiState(
                            if (large) ThemeMode.DARK else ThemeMode.LIGHT,
                            if (large) 20 else 13,
                            13
                        )
                    ) {
                        if (withModel) {
                            AboutScreen(
                                AboutUiModel("Clender", null, null),
                                Modifier.width(320.dp).height(640.dp)
                            )
                        } else {
                            AboutScreen()
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }
}

private class ReleaseContext(base: Context) : ContextWrapper(base) {
    val attempts = mutableListOf<Intent>()
    var failure: RuntimeException? = null

    override fun startActivity(intent: Intent) {
        attempts += intent
        failure?.let { throw it }
    }
}
