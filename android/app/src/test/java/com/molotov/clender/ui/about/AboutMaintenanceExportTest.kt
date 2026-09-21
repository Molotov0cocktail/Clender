package com.molotov.clender.ui.about

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ProviderInfo
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.molotov.clender.diagnostics.MaintenanceResult
import com.molotov.clender.testsupport.RobolectricComposeHost
import com.molotov.clender.ui.foundation.AppearanceUiState
import com.molotov.clender.ui.foundation.ThemeMode
import com.molotov.clender.ui.theme.ClenderTheme
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en-rUS-w360dp-h640dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AboutMaintenanceExportTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @get:Rule
    val temporary = TemporaryFolder()

    private val host = RobolectricComposeHost()
    private val maintenance = UiMaintenance()
    private lateinit var registry: MaintenanceResultRegistry

    @Before
    fun start() {
        host.start()
        registry = MaintenanceResultRegistry(host.activity)
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry = registry
        }
        host.activity.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                ClenderTheme(AppearanceUiState(ThemeMode.LIGHT, 13, 13)) {
                    AboutScreen(AboutUiModel("Clender", "1.3.1", "3"), maintenance = maintenance)
                }
            }
        }
        composeRule.waitForIdle()
    }

    @After
    fun close() = host.close()

    @Test
    fun pickerUsesCreateDocumentAndCancellationDoesNotCallMaintenance() {
        click("about_export_logs")
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, registry.intent?.action)
        assertEquals("text/plain", registry.intent?.type)
        assertEquals("clender-diagnostics.txt", registry.intent?.getStringExtra(Intent.EXTRA_TITLE))
        composeRule.onNodeWithTag("about_export_logs").assertIsNotEnabled()
        composeRule.onNodeWithTag("about_clear_logs").assertIsNotEnabled()
        composeRule.onNodeWithTag("about_clear_cache").assertIsNotEnabled()
        composeRule.runOnUiThread { registry.complete(null) }
        composeRule.waitForIdle()
        assertEquals(0, maintenance.calls.get())
        composeRule.onNodeWithTag("about_export_logs").assertIsEnabled()
        composeRule.onNodeWithTag("about_maintenance_status").assertDoesNotExist()
    }

    @Test
    fun unavailablePickerShowsGenericFailureAndAllowsRetry() {
        registry.failure = ActivityNotFoundException("secret endpoint and body")
        click("about_export_logs")
        assertFailed()
        registry.failure = SecurityException("private credential")
        click("about_export_logs")
        assertFailed()
        registry.failure = null
        click("about_export_logs")
        assertEquals(3, registry.launches)
        composeRule.runOnUiThread { registry.complete(null) }
        assertEquals(0, maintenance.calls.get())
    }

    @Test
    fun selectedDocumentIsWrittenOnIoAndOnlyThenReportsExportSuccess() {
        val file = temporary.newFile("synthetic-log.txt")
        val openedOnMain = AtomicBoolean(false)
        ShadowContentResolver.registerProviderInternal(
            "diagnostic.test",
            SyntheticExportProvider(file, openedOnMain).apply {
                attachInfo(host.activity, ProviderInfo().apply { authority = "diagnostic.test" })
            }
        )
        click("about_export_logs")
        composeRule.runOnUiThread {
            registry.complete(Uri.parse("content://diagnostic.test/export"))
        }
        awaitCalls(1)
        awaitStatus("Diagnostic logs exported.")
        assertEquals("synthetic diagnostic codes", file.readText())
        assertFalse(openedOnMain.get())
        composeRule.onNodeWithTag("about_export_logs").assertIsEnabled()
    }

    @Test
    fun unsupportedDestinationIsRejectedWithoutInvokingMaintenance() {
        click("about_export_logs")
        composeRule.runOnUiThread { registry.complete(Uri.parse("file:///synthetic.txt")) }
        awaitStatus("Could not complete the action. Please try again.")
        assertEquals(0, maintenance.calls.get())
    }

    @Test
    fun clearingNeedsConfirmationAndFailureCanBeRetried() {
        click("about_clear_logs")
        assertEquals(0, maintenance.calls.get())
        composeRule.onNodeWithTag("about_maintenance_cancel").performClick()
        assertEquals(0, maintenance.calls.get())
        maintenance.result = MaintenanceResult.FAILURE
        click("about_clear_logs")
        composeRule.onNodeWithTag("about_maintenance_confirm").performClick()
        awaitCalls(1)
        awaitStatus("Could not complete the action. Please try again.")
        maintenance.result = MaintenanceResult.SUCCESS
        click("about_clear_logs")
        composeRule.onNodeWithTag("about_maintenance_confirm").performClick()
        awaitCalls(2)
        awaitStatus("Diagnostic logs cleared.")
        click("about_clear_cache")
        composeRule.onNodeWithTag("about_maintenance_confirm").performClick()
        awaitCalls(3)
        awaitStatus("Temporary log export files cleared.")
        assertEquals(2, maintenance.logClears.get())
        assertEquals(1, maintenance.cacheClears.get())
    }

    private fun click(tag: String) {
        composeRule.onNodeWithTag(tag).performScrollTo().performClick()
        composeRule.waitForIdle()
    }

    private fun assertFailed() {
        composeRule.onNodeWithTag("about_maintenance_status").performScrollTo()
            .assertTextEquals("Could not complete the action. Please try again.")
    }

    private fun awaitCalls(count: Int) {
        composeRule.waitUntil(5_000) { maintenance.calls.get() == count }
    }

    private fun awaitStatus(text: String) {
        composeRule.waitUntil(5_000) {
            runCatching {
                composeRule.onNodeWithTag("about_maintenance_status").assertTextEquals(text)
            }.isSuccess
        }
        composeRule.onNodeWithTag("about_maintenance_status").performScrollTo()
            .assertTextEquals(text)
    }
}
