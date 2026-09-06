package com.molotov.clender.ui.about

import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import com.molotov.clender.testsupport.RobolectricComposeHost
import com.molotov.clender.ui.foundation.AppearanceUiState
import com.molotov.clender.ui.foundation.ThemeMode
import com.molotov.clender.ui.theme.ClenderTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class AboutScreenFormattingRegressionTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val host = RobolectricComposeHost()

    @Before
    fun startHost() = host.start()

    @After
    fun closeHost() = host.close()

    @Test
    @Config(qualifiers = "en-rUS-w840dp-h900dp-420dpi")
    fun englishInstalledMetadataHasExactlyOneFormattedLabel() {
        verifyFields(chinese = false, unavailable = false)
    }

    @Test
    @Config(qualifiers = "zh-rCN-w840dp-h900dp-420dpi")
    fun chineseInstalledMetadataHasExactlyOneFormattedLabel() {
        verifyFields(chinese = true, unavailable = false)
    }

    @Test
    @Config(qualifiers = "en-rUS-w840dp-h900dp-420dpi")
    fun englishPackageLookupFailureHasFiniteUnavailableText() {
        verifyFields(chinese = false, unavailable = true)
    }

    @Test
    @Config(qualifiers = "zh-rCN-w840dp-h900dp-420dpi")
    fun chinesePackageLookupFailureHasFiniteUnavailableText() {
        verifyFields(chinese = true, unavailable = true)
    }

    private fun verifyFields(chinese: Boolean, unavailable: Boolean) {
        val context = host.activity
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val label = context.applicationInfo.loadLabel(context.packageManager).toString()
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            info.versionCode.toLong()
        }
        val fallback = if (chinese) "不可用" else "Unavailable"
        val values = listOf(
            label,
            if (unavailable) fallback else requireNotNull(info.versionName),
            if (unavailable) fallback else versionCode.toString()
        )
        render(if (unavailable) MissingPackageContext(context) else context)
        val labels = if (chinese) {
            listOf("应用名称：", "版本名称：", "版本号：")
        } else {
            listOf("Application name: ", "Version name: ", "Version code: ")
        }
        val tags = listOf("about_app_name", "about_version_name", "about_version_code")
        tags.indices.forEach { index -> assertField(tags[index], labels[index] + values[index]) }
    }

    private fun render(context: Context) {
        composeRule.runOnUiThread {
            host.activity.setContent {
                CompositionLocalProvider(LocalContext provides context) {
                    ClenderTheme(AppearanceUiState(ThemeMode.LIGHT, 13, 13)) {
                        AboutScreen()
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun assertField(tag: String, expected: String) {
        val node = composeRule.onNodeWithTag(tag).performScrollTo()
        val text = node.fetchSemanticsNode().config[SemanticsProperties.Text]
            .joinToString("") { it.text }
        assertFalse(text, text.contains("%1\$s"))
        assertEquals(text, 1, text.count { it == ':' || it == '：' })
        node.assertTextEquals(expected)
    }
}

private class MissingPackageContext(context: Context) : ContextWrapper(context) {
    override fun getPackageName(): String = "com.molotov.clender.about.missing"
}
