package com.molotov.clender.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import com.molotov.clender.app.ClenderApplication
import com.molotov.clender.app.MainActivity
import com.molotov.clender.domain.widget.WidgetActionSpec
import com.molotov.clender.ui.navigation.AppDestination
import com.molotov.clender.ui.navigation.AppRoute
import com.molotov.clender.ui.state.AppShellViewModel
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAppWidgetManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], application = ClenderApplication::class)
class WidgetEditEntryContractTest {
    private val application: ClenderApplication
        get() = RuntimeEnvironment.getApplication() as ClenderApplication

    @Test
    fun validatorAcceptsOnlyExactOwnedEditIdentityIncludingIntMaxBoundaries() {
        bindOwnedWidget(1)
        bindOwnedWidget(Int.MAX_VALUE)

        assertEquals(
            WidgetActionSpec.EditEvent(1, 1),
            WidgetActionIntentContract.validateEditEvent(application, editIntent(1, 1))
        )
        assertEquals(
            WidgetActionSpec.EditEvent(Int.MAX_VALUE, Int.MAX_VALUE),
            WidgetActionIntentContract.validateEditEvent(
                application,
                editIntent(Int.MAX_VALUE, Int.MAX_VALUE)
            )
        )
        assertFalse(databaseFile().exists())
        assertFalse(preferenceFile().exists())
    }

    @Test
    fun systemNewTaskEditEntryAcceptsOwnedTokenAndRejectsEveryOtherFlagBit() {
        bindOwnedWidget(71)
        val allowedFlags = listOf(0x24000000, 0x34000000, 0x34400000)
        allowedFlags.forEach { flags ->
            val canonical = editIntent(71, 81).apply { this.flags = flags }
            assertEquals(
                WidgetActionSpec.EditEvent(71, 81),
                WidgetActionIntentContract.validateEditEvent(application, canonical)
            )
            (0 until Int.SIZE_BITS).map { 1 shl it }
                .filter { (flags xor it) !in allowedFlags }
                .forEach { bit ->
                    assertNull(
                        "base flags $flags, toggled flag $bit",
                        WidgetActionIntentContract.validateEditEvent(
                            application,
                            Intent(canonical).apply { this.flags = flags xor bit }
                        )
                    )
                }
        }
        assertFalse(databaseFile().exists())
        assertFalse(preferenceFile().exists())
    }

    @Test
    fun systemNewTaskDoesNotRelaxEditEnvelopeOrOwnership() {
        bindOwnedWidget(71)
        bindWrongOwnerWidget(72)
        val canonical = editIntent(71, 81)
        val invalid = invalidRoutingIntents(canonical) +
            invalidPayloadIntents(canonical) + invalidSurfaceIntents(canonical)
        invalid.forEach { intent ->
            assertNull(
                WidgetActionIntentContract.validateEditEvent(
                    application,
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            )
        }
        assertFalse(databaseFile().exists())
        assertFalse(preferenceFile().exists())
    }

    @Test
    fun validatorRejectsWrongActionComponentPackageDataExtraAndProviderBeforeRuntimeAccess() {
        bindOwnedWidget(71)
        bindWrongOwnerWidget(72)
        val canonical = editIntent(71, 81)
        val invalid = invalidRoutingIntents(canonical) +
            invalidPayloadIntents(canonical) +
            invalidSurfaceIntents(canonical)

        invalid.forEach { intent ->
            assertNull(
                "must reject action=${intent.action}, data=${intent.data}",
                WidgetActionIntentContract.validateEditEvent(application, intent)
            )
        }
        assertFalse(databaseFile().exists())
        assertFalse(preferenceFile().exists())
    }

    private fun invalidRoutingIntents(canonical: Intent): List<Intent> = listOf(
        Intent(canonical).apply { action = WidgetActionIntentContract.ACTION_LOCAL_REFRESH },
        Intent(canonical).apply { action = AppWidgetManager.ACTION_APPWIDGET_CONFIGURE },
        Intent(canonical).apply { action = "com.molotov.clender.action.UNKNOWN" },
        Intent(canonical).apply { component = null },
        Intent(canonical).apply {
            component = ComponentName(application, ClenderWidgetProvider::class.java)
        },
        Intent(canonical).apply { `package` = null },
        Intent(canonical).apply { setPackage("com.example.other") }
    )

    private fun invalidPayloadIntents(canonical: Intent): List<Intent> = listOf(
        Intent(canonical).apply { data = null },
        editIntent(71, 81, "clender-internal://widget/72/edit/81"),
        editIntent(72, 81),
        Intent(canonical).apply {
            removeExtra(AppWidgetManager.EXTRA_APPWIDGET_ID)
        },
        Intent(canonical).apply { putExtra("unexpected", "value") },
        Intent(canonical).apply {
            removeExtra(AppWidgetManager.EXTRA_APPWIDGET_ID)
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 71L)
        }
    )

    private fun invalidSurfaceIntents(canonical: Intent): List<Intent> = listOf(
        Intent(canonical).apply { addCategory(Intent.CATEGORY_BROWSABLE) },
        Intent(canonical).apply {
            clipData = ClipData.newPlainText("payload", "secret")
        },
        Intent(canonical).apply {
            data = null
            component = null
            `package` = null
            selector = Intent(Intent.ACTION_VIEW)
        }
    )

    @Test
    fun validatorRejectsNonCanonicalIdsAliasesUrisQueryFragmentAndCrossActions() {
        bindOwnedWidget(71)
        val malformedData = listOf(
            "clender-internal://widget/0/edit/1",
            "clender-internal://widget/-1/edit/1",
            "clender-internal://widget/2147483648/edit/1",
            "clender-internal://widget/071/edit/1",
            "clender-internal://widget/%37%31/edit/1",
            "clender-internal://widget/71/edit/0",
            "clender-internal://widget/71/edit/-1",
            "clender-internal://widget/71/edit/2147483648",
            "clender-internal://widget/71/edit/081",
            "clender-internal://widget/71/edit/%38%31",
            "clender-internal://widget/71/edit/81?payload=x",
            "clender-internal://widget/71/edit/81#fragment",
            "clender-internal://widget/71/edit/81/extra",
            "clender-internal://widget/71/refresh",
            "clender-internal://widget/71/configure",
            "clender-internal://widget/71/quick-ai",
            "https://example.invalid/widget/71/edit/81",
            "http://example.invalid/widget/71/edit/81"
        )

        malformedData.forEach { raw ->
            assertNull(
                "must reject non-canonical identity $raw",
                WidgetActionIntentContract.validateEditEvent(
                    application,
                    editIntent(71, 81, raw)
                )
            )
        }
    }

    @Test
    fun routeAdapterSelectsEventsAndOneDetailWithoutStackingRepeats() {
        val shell = AppShellViewModel(SavedStateHandle())
        shell.navigateTo(AppDestination.SETTINGS)
        shell.pushRoute(AppRoute.QuickAi)
        val entry = WidgetActionSpec.EditEvent(71, 81)

        WidgetEditEntryRouter.route(shell, entry)
        WidgetEditEntryRouter.route(shell, entry)

        assertEquals(AppDestination.EVENTS, shell.state.value.destination)
        assertEquals(listOf(AppRoute.EventDetail(81)), shell.state.value.childRoutes)
    }

    @Test
    fun routeAdapterSupportsMaxEventAndDoesNotPersistRuntimeChildRoute() {
        val handle = SavedStateHandle()
        val shell = AppShellViewModel(handle)

        WidgetEditEntryRouter.route(
            shell,
            WidgetActionSpec.EditEvent(Int.MAX_VALUE, Int.MAX_VALUE)
        )

        assertEquals(AppDestination.EVENTS, shell.state.value.destination)
        assertEquals(
            listOf(AppRoute.EventDetail(Int.MAX_VALUE)),
            shell.state.value.childRoutes
        )
        assertTrue(handle.keys().all { it in ALLOWED_SAVED_STATE_KEYS })
        assertFalse(handle.keys().any { "route" in it })
    }

    private fun editIntent(
        widgetId: Int,
        eventId: Int,
        rawData: String = "clender-internal://widget/$widgetId/edit/$eventId"
    ): Intent = Intent(WidgetActionIntentContract.ACTION_EDIT_EVENT).apply {
        component = ComponentName(application, MainActivity::class.java)
        setPackage(application.packageName)
        data = Uri.parse(rawData)
        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
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

    private fun databaseFile(): File =
        application.getDatabasePath(com.molotov.clender.app.AppContainer.DATABASE_NAME)

    private fun preferenceFile(): File = File(
        application.filesDir,
        com.molotov.clender.app.AppContainer.PREFERENCES_RELATIVE_PATH
    )

    private companion object {
        val ALLOWED_SAVED_STATE_KEYS = setOf(
            "destination",
            "date",
            "calendar_mode",
            "draft_id"
        )
    }
}
