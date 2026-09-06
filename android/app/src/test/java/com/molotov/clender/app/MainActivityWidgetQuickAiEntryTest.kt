package com.molotov.clender.app

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import com.molotov.clender.testsupport.ProductionActivityTestResources
import com.molotov.clender.ui.ai.AiSubmissionViewModel
import com.molotov.clender.ui.event.EventCrudViewModel
import com.molotov.clender.ui.navigation.AppDestination
import com.molotov.clender.ui.navigation.AppRoute
import com.molotov.clender.ui.settings.SettingsSection
import com.molotov.clender.ui.settings.SettingsViewModel
import com.molotov.clender.ui.state.AppShellViewModel
import com.molotov.clender.widget.ClenderWidgetProvider
import com.molotov.clender.widget.WidgetActionIntentContract
import com.molotov.clender.widget.WidgetQuickAiDestination
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], application = ClenderApplication::class)
class MainActivityWidgetQuickAiEntryTest {
    private val application: ClenderApplication
        get() = RuntimeEnvironment.getApplication() as ClenderApplication

    @Test
    fun coldConversationEntryOpensExistingAiRootWithoutEventMutation() {
        bindOwnedWidget(1)
        withActivity(navigationIntent(1, WidgetQuickAiDestination.CONVERSATION)) { controller ->
            assertRoot(controller.get(), AppDestination.AI)
            assertEquals(0L, application.container.scheduleMutationVersion.value)
        }
    }

    @Test
    fun coldSettingsEntryFocusesAiSectionAndRecreateKeepsTheSameRoot() {
        bindOwnedWidget(Int.MAX_VALUE)
        withActivity(navigationIntent(Int.MAX_VALUE, WidgetQuickAiDestination.SETTINGS)) {
            assertAiSettings(it.get())

            it.recreate()
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            assertAiSettings(it.get())
            assertEquals(0L, application.container.scheduleMutationVersion.value)
        }
    }

    @Test
    fun hotEntriesReplaceChildRoutesAndAcceptedIntentWithoutDuplicateRoutes() {
        bindOwnedWidget(41)
        withActivity(Intent(application, MainActivity::class.java)) { controller ->
            val activity = controller.get()
            val shell = ViewModelProvider(activity)[AppShellViewModel::class.java]
            shell.navigateTo(AppDestination.EVENTS)
            shell.pushRoute(AppRoute.EventDetail(7))

            WidgetQuickAiDestination.entries.forEach { destination ->
                val legal = navigationIntent(41, destination)
                repeat(2) { controller.newIntent(legal) }

                assertSame(legal, activity.intent)
                if (destination == WidgetQuickAiDestination.SETTINGS) {
                    assertAiSettings(activity)
                } else {
                    assertRoot(activity, AppDestination.AI)
                }
            }
            assertEquals(0L, application.container.scheduleMutationVersion.value)
        }
    }

    @Test
    fun invalidColdEnvelopeStaysAtDefaultCalendarRoot() {
        bindOwnedWidget(51)
        val invalid = navigationIntent(51, WidgetQuickAiDestination.SETTINGS).apply {
            putExtra("unexpected", "synthetic")
        }
        withActivity(invalid) { controller ->
            assertRoot(controller.get(), AppDestination.CALENDAR)
            assertEquals(0L, application.container.scheduleMutationVersion.value)
        }
    }

    @Test
    fun unownedColdEntryStaysAtDefaultCalendarRoot() {
        withActivity(navigationIntent(61, WidgetQuickAiDestination.CONVERSATION)) { controller ->
            assertRoot(controller.get(), AppDestination.CALENDAR)
            assertEquals(0L, application.container.scheduleMutationVersion.value)
        }
    }

    @Test
    fun invalidHotEntriesPreserveEventDraftDirtyFormShellAndAcceptedIntent() {
        bindOwnedWidget(71)
        withActivity(Intent(application, MainActivity::class.java)) { controller ->
            val activity = controller.get()
            val shell = ViewModelProvider(activity)[AppShellViewModel::class.java]
            val crud = ViewModelProvider(activity)[EventCrudViewModel::class.java]
            val date = LocalDate.of(2026, 9, 6)
            shell.navigateTo(AppDestination.EVENTS)
            shell.pushRoute(AppRoute.NewEvent(date))
            shell.drafts.set("0123456789abcdef0123456789abcdef")
            crud.startNew(date)
            crud.updateForm(requireNotNull(crud.state.value.form).copy(title = "unsaved event"))
            val shellBefore = shell.state.value
            val crudBefore = crud.state.value
            val intentBefore = activity.intent

            invalidEntries(71).forEach { invalid ->
                controller.newIntent(invalid)

                assertEquals(shellBefore, shell.state.value)
                assertEquals(crudBefore, crud.state.value)
                assertSame(intentBefore, activity.intent)
            }
            assertEquals(0L, application.container.scheduleMutationVersion.value)
        }
    }

    @Test
    fun invalidHotSettingsEntryKeepsDirtySettingsSectionAndDraft() {
        bindOwnedWidget(81)
        withActivity(navigationIntent(81, WidgetQuickAiDestination.SETTINGS)) { controller ->
            val activity = controller.get()
            val settings = ViewModelProvider(activity)[SettingsViewModel::class.java]
            val shell = ViewModelProvider(activity)[AppShellViewModel::class.java]
            settings.selectSection(SettingsSection.WEBDAV)
            settings.updateAi(settings.state.value.ai.copy(model = "unsaved-model"))
            val settingsBefore = settings.state.value
            val shellBefore = shell.state.value
            val intentBefore = activity.intent
            assertTrue(settingsBefore.dirty)

            invalidEntries(81).forEach { invalid ->
                controller.newIntent(invalid)

                assertEquals(settingsBefore, settings.state.value)
                assertEquals(shellBefore, shell.state.value)
                assertSame(intentBefore, activity.intent)
            }
        }
    }

    @Test
    fun invalidHotConversationEntryDoesNotClearComposerDraft() {
        bindOwnedWidget(91)
        withActivity(navigationIntent(91, WidgetQuickAiDestination.CONVERSATION)) { controller ->
            val activity = controller.get()
            val submission = ViewModelProvider(activity)[AiSubmissionViewModel::class.java]
            submission.updateDraft("  unsent 🌏\nsecond line  ")
            val before = submission.state.value
            val intentBefore = activity.intent

            invalidEntries(91).forEach { invalid ->
                controller.newIntent(invalid)

                assertEquals(before, submission.state.value)
                assertSame(intentBefore, activity.intent)
                assertRoot(activity, AppDestination.AI)
            }
            assertEquals(0L, application.container.scheduleMutationVersion.value)
        }
    }

    private fun assertRoot(activity: MainActivity, destination: AppDestination) {
        val state = ViewModelProvider(activity)[AppShellViewModel::class.java].state.value
        assertEquals(destination, state.destination)
        assertTrue(state.childRoutes.isEmpty())
    }

    private fun assertAiSettings(activity: MainActivity) {
        assertRoot(activity, AppDestination.SETTINGS)
        val settings = ViewModelProvider(activity)[SettingsViewModel::class.java]
        assertEquals(SettingsSection.AI, settings.state.value.section)
    }

    private fun navigationIntent(widgetId: Int, destination: WidgetQuickAiDestination): Intent =
        WidgetActionIntentContract.quickAiNavigationIntent(application, widgetId, destination)

    private fun invalidEntries(widgetId: Int): List<Intent> {
        val legal = navigationIntent(widgetId, WidgetQuickAiDestination.SETTINGS)
        return listOf(
            Intent(legal).apply { action = Intent.ACTION_VIEW },
            Intent(legal).apply { data = Uri.parse("https://example.invalid/quick-ai") },
            Intent(legal).apply { putExtra("unexpected", "synthetic") },
            Intent(legal).apply { addCategory(Intent.CATEGORY_BROWSABLE) },
            navigationIntent(widgetId + 1, WidgetQuickAiDestination.CONVERSATION)
        )
    }

    private fun bindOwnedWidget(widgetId: Int) {
        val info = AppWidgetProviderInfo().apply {
            provider = ComponentName(application, ClenderWidgetProvider::class.java)
        }
        Shadows.shadowOf(AppWidgetManager.getInstance(application)).putWidgetInfo(widgetId, info)
    }

    private inline fun withActivity(
        intent: Intent,
        assertion: (ActivityController<MainActivity>) -> Unit
    ) {
        val controller = Robolectric.buildActivity(MainActivity::class.java, intent)
        try {
            controller.create().start().resume().visible()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertion(controller)
        } finally {
            ProductionActivityTestResources.close(application) {
                controller.pause().stop().destroy()
            }
        }
    }
}
