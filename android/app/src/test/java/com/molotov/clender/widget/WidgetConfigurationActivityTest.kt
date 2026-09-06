package com.molotov.clender.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.molotov.clender.app.AppContainer
import com.molotov.clender.app.ClenderApplication
import com.molotov.clender.testsupport.ProductionActivityTestResources
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAppWidgetManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], application = ClenderApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetConfigurationActivityTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val application: ClenderApplication
        get() = RuntimeEnvironment.getApplication() as ClenderApplication

    private var controller: ActivityController<WidgetConfigurationActivity>? = null

    @After
    fun closeProductionResources() {
        ProductionActivityTestResources.close(application) {
            controller?.pause()?.stop()?.destroy()
            controller = null
        }
    }

    @Test
    fun validatorAcceptsInitialNullDataAndExactReconfigureIdentityForOwnedInstance() {
        bindOwnedWidget(71)
        val manager = AppWidgetManager.getInstance(application)

        assertEquals(
            71,
            WidgetConfigurationEntryValidator.validate(
                application,
                configureIntent(71),
                manager
            )
        )
        assertEquals(
            71,
            WidgetConfigurationEntryValidator.validate(
                application,
                configureIntent(71, canonicalData(71)),
                manager
            )
        )
    }

    @Test
    fun validatorRejectsInvalidMissingAndWrongOwnerBeforeAnyRuntimeAccess() {
        val manager = AppWidgetManager.getInstance(application)
        bindOwnedWidget(71)
        bindWrongOwnerWidget(72)
        val invalid = listOf(
            Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 71),
            configureIntent(AppWidgetManager.INVALID_APPWIDGET_ID),
            configureIntent(-1),
            configureIntent(404),
            configureIntent(72),
            configureIntent(71, Uri.parse("https://example.invalid/widget/71/configure")),
            configureIntent(71, Uri.parse("clender-internal://widget/71/configure?payload=x")),
            configureIntent(71, Uri.parse("clender-internal://widget/71/configure#fragment")),
            configureIntent(71, Uri.parse("clender-internal://widget/71/configure/extra")),
            configureIntent(71, canonicalData(72))
        )

        invalid.forEach { intent ->
            assertNull(
                "must reject action=${intent.action}, data=${intent.data}",
                WidgetConfigurationEntryValidator.validate(application, intent, manager)
            )
        }
        assertFalse(databaseFile().exists())
        assertFalse(preferenceFile().exists())
    }

    @Test
    fun invalidActivityFinishesWithCanceledInvalidIdAndNoContainerArtifacts() {
        launch(configureIntent(901, canonicalData(901)))
        val activity = requireNotNull(controller).get()
        val shadow = Shadows.shadowOf(activity)

        assertTrue(activity.isFinishing)
        assertEquals(Activity.RESULT_CANCELED, shadow.resultCode)
        assertExactResult(shadow.resultIntent, AppWidgetManager.INVALID_APPWIDGET_ID)
        assertFalse(databaseFile().exists())
        assertFalse(preferenceFile().exists())
    }

    @Test
    fun validActivitySetsCanceledOwnedIdBeforeShowingInitialOrReconfigureUi() {
        bindOwnedWidget(81)
        launch(configureIntent(81, canonicalData(81)))
        val activity = requireNotNull(controller).get()
        val shadow = Shadows.shadowOf(activity)

        assertFalse(activity.isFinishing)
        assertEquals(Activity.RESULT_CANCELED, shadow.resultCode)
        assertExactResult(shadow.resultIntent, 81)
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("widget_config_save")
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun saveSuccessReturnsOkWithOnlyOwnedIdAndFinishes() {
        bindOwnedWidget(91)
        launch(configureIntent(91))
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("widget_config_save")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("widget_config_save")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        composeRule.waitUntil(5_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            requireNotNull(controller).get().isFinishing
        }

        val shadow = Shadows.shadowOf(requireNotNull(controller).get())
        assertEquals(Activity.RESULT_OK, shadow.resultCode)
        assertExactResult(shadow.resultIntent, 91)
    }

    @Test
    fun sourceValidatesActionDataIdAndOwnershipBeforeReadingApplicationContainer() {
        val source = activitySource()
        val validation = source.indexOf("WidgetConfigurationEntryValidator.validate")
        val runtimeAccess = source.indexOf("application as WidgetConfigurationActivityOwner")

        assertTrue("Activity must invoke the frozen entry validator", validation >= 0)
        assertTrue("runtime access must follow complete validation", runtimeAccess > validation)
        listOf("callingPackage", "referrer", "creatorPackage", "sentFromPackage").forEach {
            assertFalse("caller identity is not an authorization input: $it", source.contains(it))
        }
    }

    private fun launch(intent: Intent) {
        controller = Robolectric.buildActivity(WidgetConfigurationActivity::class.java, intent)
            .create()
            .start()
            .resume()
            .visible()
        composeRule.waitForIdle()
    }

    private fun bindOwnedWidget(id: Int) = bindWidget(
        id,
        ComponentName(application, ClenderWidgetProvider::class.java)
    )

    private fun bindWrongOwnerWidget(id: Int) = bindWidget(
        id,
        ComponentName(application.packageName, "com.molotov.clender.widget.NotOurProvider")
    )

    private fun bindWidget(id: Int, provider: ComponentName) {
        val info = AppWidgetProviderInfo().apply { this.provider = provider }
        val shadow: ShadowAppWidgetManager =
            Shadows.shadowOf(AppWidgetManager.getInstance(application))
        shadow.putWidgetInfo(id, info)
    }

    private fun configureIntent(id: Int, data: Uri? = null) =
        Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE).apply {
            component = ComponentName(application, WidgetConfigurationActivity::class.java)
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            this.data = data
        }

    private fun canonicalData(id: Int): Uri = Uri.parse("clender-internal://widget/$id/configure")

    private fun assertExactResult(result: Intent?, expectedId: Int) {
        requireNotNull(result)
        assertEquals(setOf(AppWidgetManager.EXTRA_APPWIDGET_ID), result.extras?.keySet())
        assertEquals(
            expectedId,
            result.getIntExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID,
                Int.MIN_VALUE
            )
        )
        assertNull(result.action)
        assertNull(result.data)
        assertNull(result.component)
        assertNull(result.`package`)
        assertNull(result.categories)
    }

    private fun activitySource(): String =
        generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .flatMap { root ->
                sequenceOf(
                    File(root, ACTIVITY_SOURCE_PATH),
                    File(root, "android/$ACTIVITY_SOURCE_PATH")
                )
            }
            .firstOrNull(File::isFile)
            ?.readText()
            ?: error("required WidgetConfigurationActivity source is missing")

    private fun databaseFile(): File = application.getDatabasePath(AppContainer.DATABASE_NAME)

    private fun preferenceFile(): File =
        File(application.filesDir, AppContainer.PREFERENCES_RELATIVE_PATH)

    private companion object {
        const val ACTIVITY_SOURCE_PATH =
            "app/src/main/java/com/molotov/clender/widget/WidgetConfigurationActivity.kt"
    }
}
