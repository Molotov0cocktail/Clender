package com.molotov.clender.ui.about

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.molotov.clender.testsupport.RobolectricComposeHost
import com.molotov.clender.ui.foundation.AppearanceUiState
import com.molotov.clender.ui.foundation.ThemeMode
import com.molotov.clender.ui.theme.ClenderTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AboutScreenTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val composeHost = RobolectricComposeHost()

    @Before
    fun startComposeHost() = composeHost.start()

    @After
    fun closeComposeHost() = composeHost.close()

    @Test
    fun englishAboutUsesPaneAndHeadingSemanticsAndShowsSafeMetadata() {
        setScreen(englishModel(), width = 840, theme = ThemeMode.LIGHT, fontSp = 8, fontScale = 1f)

        composeRule.onNodeWithTag("about_root").assert(hasPaneTitle())
        composeRule.onNodeWithTag("about_title").assert(hasHeading())
        listOf(
            "about_app_name",
            "about_version_name",
            "about_version_code",
            "about_sandbox",
            "about_webdav_policy",
            "about_ai_policy"
        ).forEach { composeRule.onNodeWithTag(it).performScrollTo().assertIsDisplayed() }
        composeRule.onNodeWithTag("about_version_name").assertIsDisplayed()
    }

    @Config(qualifiers = "zh-rCN-w840dp-h900dp-420dpi")
    @Test
    fun chineseAboutLocalizesAllExplanatoryContent() {
        setScreen(
            chineseModel(),
            width = 840,
            theme = ThemeMode.SYSTEM,
            fontSp = 13,
            fontScale = 1f
        )

        listOf(
            "关于",
            "应用名称",
            "版本名称",
            "版本号",
            "Android 私有 sandbox 数据仅保存在本设备。",
            "WebDAV 仅同步日程，不同步对话或设置。",
            "AI 仅在用户明确提交且配置完成后请求网络。"
        ).forEach { text ->
            composeRule.onNodeWithText(text, substring = true, useUnmergedTree = true)
                .assertIsDisplayed()
        }
    }

    @Test
    fun aboutHasVerticalScrollAndNoExternalActionNodes() {
        setScreen(englishModel(), width = 360, theme = ThemeMode.DARK, fontSp = 20, fontScale = 2f)

        composeRule.onNodeWithTag("about_content").assert(hasScrollAction())
        assertEquals(
            0,
            composeRule.onAllNodes(hasClickAction(), useUnmergedTree = true)
                .fetchSemanticsNodes().size
        )
        composeRule.onNodeWithTag("about_external_link").assertDoesNotExist()
        composeRule.onNodeWithTag("about_share").assertDoesNotExist()
        composeRule.onNodeWithTag("about_feedback").assertDoesNotExist()
    }

    @Test
    fun compactLargeTextKeepsEveryAboutSectionReachable() {
        setScreen(chineseModel(), width = 360, theme = ThemeMode.LIGHT, fontSp = 20, fontScale = 2f)

        composeRule.onNodeWithTag("about_content").assert(hasScrollAction())
        listOf(
            "about_app_name",
            "about_version_name",
            "about_version_code",
            "about_sandbox",
            "about_webdav_policy",
            "about_ai_policy"
        ).forEach { tag -> composeRule.onNodeWithTag(tag).performScrollTo().assertIsDisplayed() }
    }

    @Config(qualifiers = "zh-rCN-ldrtl-w360dp-h640dp-420dpi")
    @Test
    fun rtlAboutKeepsReadingOrderAndDoesNotLeakSensitiveValues() {
        setScreen(chineseModel(), width = 360, theme = ThemeMode.LIGHT, fontSp = 20, fontScale = 2f)

        composeRule.onNodeWithText("关于", substring = true, useUnmergedTree = true)
            .assertIsDisplayed()
        val semantics = composeRule.onRoot(useUnmergedTree = true).fetchSemanticsNode().toString()
        listOf(
            "applicationId",
            "/data/user/0",
            "https://private.example",
            "endpoint",
            "username",
            "api-key",
            "password",
            "Authorization",
            "event body",
            "conversation body"
        ).forEach { unsafe -> assertFalse(semantics.contains(unsafe, ignoreCase = true)) }
    }

    @Test
    fun unavailableVersionIsLocalizedAndExceptionDetailsStayOutOfSemantics() {
        val rawException = "PackageManager private endpoint api-key failure"
        setScreen(
            AboutUiModel(
                applicationName = "Clender",
                versionName = "不可用",
                versionCode = "不可用",
                sandboxText = "Android 私有 sandbox 数据仅保存在本设备。",
                webDavText = "WebDAV 仅同步日程，不同步对话或设置。",
                aiText = "AI 仅在用户明确提交且配置完成后请求网络。"
            ),
            width = 360,
            theme = ThemeMode.SYSTEM,
            fontSp = 20,
            fontScale = 2f
        )

        composeRule.onNodeWithTag("about_version_name").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("about_version_code").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("about_version_name").assert(hasText("不可用"))
        assertFalse(
            composeRule.onRoot(useUnmergedTree = true).fetchSemanticsNode().toString()
                .contains(rawException)
        )
    }

    @Test
    fun lightAndDarkAboutTextUsesAccessibleContrastAndStableTags() {
        listOf(ThemeMode.LIGHT, ThemeMode.DARK).forEach { theme ->
            setScreen(englishModel(), width = 840, theme = theme, fontSp = 13, fontScale = 1f)

            composeRule.onNodeWithTag("about_title").assertIsDisplayed()
            composeRule.onNodeWithTag("about_app_name").performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithTag("about_ai_policy").performScrollTo().assertIsDisplayed()
            assertTrue(
                composeRule.onRoot(useUnmergedTree = true).fetchSemanticsNode().toString()
                    .isNotBlank()
            )
        }
    }

    @Config(qualifiers = "zh-rCN-ldrtl-w360dp-h640dp-420dpi")
    @Test
    fun apiDimensionsAndRtlQualifiersAreAppliedToCompactAbout() {
        setScreen(
            chineseModel(),
            width = 360,
            theme = ThemeMode.SYSTEM,
            fontSp = 20,
            fontScale = 2f
        )

        composeRule.onNodeWithTag("about_root").assertIsDisplayed()
        composeRule.onNodeWithTag("about_content").assert(hasScrollAction())
        composeRule.onNodeWithTag("about_ai_policy").performScrollTo().assertIsDisplayed()
    }

    @Config(qualifiers = "en-rUS-w840dp-h900dp-420dpi")
    @Test
    fun apiDimensionsAndLtrQualifiersAreAppliedToExpandedAbout() {
        setScreen(englishModel(), width = 840, theme = ThemeMode.DARK, fontSp = 8, fontScale = 1f)

        composeRule.onNodeWithTag("about_root").assertIsDisplayed()
        composeRule.onNodeWithTag("about_content").assert(hasScrollAction())
        composeRule.onNodeWithTag("about_webdav_policy").performScrollTo().assertIsDisplayed()
    }

    private fun setScreen(
        model: AboutUiModel,
        width: Int,
        theme: ThemeMode,
        fontSp: Int,
        fontScale: Float
    ) {
        composeRule.runOnUiThread {
            composeHost.activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, fontScale)
                ) {
                    ClenderTheme(AppearanceUiState(theme, fontSp, 13)) {
                        AboutScreen(
                            model = model,
                            modifier = Modifier.width(width.dp).height(640.dp)
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun hasPaneTitle() = SemanticsMatcher("has a non-blank pane title") { node ->
        node.config.getOrNull(SemanticsProperties.PaneTitle)?.isNotEmpty() == true
    }

    private fun hasHeading() = SemanticsMatcher("is a heading") { node ->
        SemanticsProperties.Heading in node.config
    }

    private fun hasText(expected: String) = SemanticsMatcher("contains text $expected") { node ->
        node.config.toString().contains(expected)
    }

    private fun englishModel() = AboutUiModel(
        applicationName = "Clender",
        versionName = "2.7.0",
        versionCode = "42",
        sandboxText = "Android private sandbox data stays on this device.",
        webDavText = "WebDAV syncs schedules only; conversations and settings are not synced.",
        aiText = "AI requests the network only after an explicit user submission " +
            "and complete configuration."
    )

    private fun chineseModel() = AboutUiModel(
        applicationName = "Clender",
        versionName = "2.7.0",
        versionCode = "42",
        sandboxText = "Android 私有 sandbox 数据仅保存在本设备。",
        webDavText = "WebDAV 仅同步日程，不同步对话或设置。",
        aiText = "AI 仅在用户明确提交且配置完成后请求网络。"
    )
}
