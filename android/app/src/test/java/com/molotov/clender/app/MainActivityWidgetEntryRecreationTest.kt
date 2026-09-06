package com.molotov.clender.app

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Intent
import android.os.Looper
import androidx.core.net.toUri
import androidx.lifecycle.ViewModelProvider
import com.molotov.clender.domain.widget.WidgetActionSpec
import com.molotov.clender.testsupport.ProductionActivityTestResources
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
class MainActivityWidgetEntryRecreationTest {
    @Test
    fun oldEditEntryDoesNotOverrideLaterDraftOnRecreate() =
        withRecreationEntry(RecreationEntry.EDIT) { it.assertRecreatePreservesDraft() }

    @Test
    fun oldConversationEntryDoesNotOverrideLaterDraftOnRecreate() =
        withRecreationEntry(RecreationEntry.CONVERSATION) { it.assertRecreatePreservesDraft() }

    @Test
    fun oldSettingsEntryDoesNotOverrideLaterDraftOnRecreate() =
        withRecreationEntry(RecreationEntry.SETTINGS) { it.assertRecreatePreservesDraft() }

    @Test
    fun firstColdEditEntryStillNavigates() =
        withRecreationEntry(RecreationEntry.EDIT) { it.assertEntry() }

    @Test
    fun firstColdConversationEntryStillNavigates() =
        withRecreationEntry(RecreationEntry.CONVERSATION) { it.assertEntry() }

    @Test
    fun firstColdSettingsEntryStillNavigates() =
        withRecreationEntry(RecreationEntry.SETTINGS) { it.assertEntry() }

    @Test
    fun newEditIntentAfterRecreateStillNavigates() =
        withRecreationEntry(RecreationEntry.EDIT) { it.assertFreshIntentAfterRecreate() }

    @Test
    fun newConversationIntentAfterRecreateStillNavigates() =
        withRecreationEntry(RecreationEntry.CONVERSATION) { it.assertFreshIntentAfterRecreate() }

    @Test
    fun newSettingsIntentAfterRecreateStillNavigates() =
        withRecreationEntry(RecreationEntry.SETTINGS) { it.assertFreshIntentAfterRecreate() }
}

private enum class RecreationEntry { EDIT, CONVERSATION, SETTINGS }

private class EntryRecreationFixture(
    private val application: ClenderApplication,
    private val controller: ActivityController<MainActivity>,
    private val kind: RecreationEntry
) {
    private val shell: AppShellViewModel
        get() = ViewModelProvider(controller.get())[AppShellViewModel::class.java]
    private val crud: EventCrudViewModel
        get() = ViewModelProvider(controller.get())[EventCrudViewModel::class.java]

    fun assertEntry() {
        val state = shell.state.value
        when (kind) {
            RecreationEntry.EDIT -> {
                assertEquals(AppDestination.EVENTS, state.destination)
                assertEquals(listOf(AppRoute.EventDetail(RECREATION_EVENT_ID)), state.childRoutes)
            }

            RecreationEntry.CONVERSATION -> {
                assertEquals(AppDestination.AI, state.destination)
                assertTrue(state.childRoutes.isEmpty())
            }

            RecreationEntry.SETTINGS -> {
                assertEquals(AppDestination.SETTINGS, state.destination)
                assertTrue(state.childRoutes.isEmpty())
                val settings = ViewModelProvider(controller.get())[SettingsViewModel::class.java]
                assertEquals(SettingsSection.AI, settings.state.value.section)
            }
        }
        assertEquals(0L, application.container.scheduleMutationVersion.value)
    }

    fun assertRecreatePreservesDraft() {
        moveToDirtyDraft()
        val shellBefore = shell.state.value
        val crudBefore = crud.state.value
        controller.recreate()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(shellBefore, shell.state.value)
        assertEquals(crudBefore, crud.state.value)
        assertEquals(0L, application.container.scheduleMutationVersion.value)
    }

    fun assertFreshIntentAfterRecreate() {
        controller.recreate()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        moveToDirtyDraft()
        val fresh = recreationIntent(application, kind)
        controller.newIntent(fresh)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertSame(fresh, controller.get().intent)
        assertEntry()
    }

    private fun moveToDirtyDraft() {
        val date = LocalDate.of(2028, 2, 29)
        shell.navigateTo(AppDestination.EVENTS)
        shell.selectDate(date)
        shell.pushRoute(AppRoute.NewEvent(date))
        shell.drafts.ensure()
        crud.startNew(date)
        crud.updateForm(
            requireNotNull(crud.state.value.form).copy(
                title = "later draft 🌏",
                description = "a\nb"
            )
        )
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(AppRoute.NewEvent(date)), shell.state.value.childRoutes)
        assertEquals("later draft 🌏", crud.state.value.form?.title)
    }
}

private fun withRecreationEntry(
    kind: RecreationEntry,
    assertion: (EntryRecreationFixture) -> Unit
) {
    val application = RuntimeEnvironment.getApplication() as ClenderApplication
    val info = AppWidgetProviderInfo().apply {
        provider = ComponentName(application, ClenderWidgetProvider::class.java)
    }
    Shadows.shadowOf(AppWidgetManager.getInstance(application))
        .putWidgetInfo(RECREATION_WIDGET_ID, info)
    val controller = Robolectric.buildActivity(
        MainActivity::class.java,
        recreationIntent(application, kind)
    )
    try {
        controller.create().start().resume().visible()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertion(EntryRecreationFixture(application, controller, kind))
    } finally {
        ProductionActivityTestResources.close(application) {
            controller.pause().stop().destroy()
        }
    }
}

private fun recreationIntent(application: ClenderApplication, kind: RecreationEntry): Intent =
    if (kind == RecreationEntry.EDIT) {
        Intent(WidgetActionIntentContract.ACTION_EDIT_EVENT).apply {
            component = ComponentName(application, MainActivity::class.java)
            setPackage(application.packageName)
            data = WidgetActionSpec.EditEvent(RECREATION_WIDGET_ID, RECREATION_EVENT_ID)
                .canonicalIdentity.toUri()
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, RECREATION_WIDGET_ID)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
    } else {
        WidgetActionIntentContract.quickAiNavigationIntent(
            application,
            RECREATION_WIDGET_ID,
            if (kind == RecreationEntry.CONVERSATION) {
                WidgetQuickAiDestination.CONVERSATION
            } else {
                WidgetQuickAiDestination.SETTINGS
            }
        )
    }

private const val RECREATION_WIDGET_ID = 831
private const val RECREATION_EVENT_ID = 17
