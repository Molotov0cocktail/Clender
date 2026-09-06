package com.molotov.clender.app

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModelProvider
import com.molotov.clender.testsupport.ProductionActivityTestResources
import com.molotov.clender.ui.event.EventCrudLoadStatus
import com.molotov.clender.ui.event.EventCrudRoute
import com.molotov.clender.ui.event.EventCrudViewModel
import com.molotov.clender.ui.navigation.AppDestination
import com.molotov.clender.ui.navigation.AppRoute
import com.molotov.clender.ui.state.AppShellViewModel
import com.molotov.clender.widget.ClenderWidgetProvider
import com.molotov.clender.widget.WidgetActionIntentContract
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAppWidgetManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], application = ClenderApplication::class)
class MainActivityWidgetEditEntryTest {
    private val application: ClenderApplication
        get() = RuntimeEnvironment.getApplication() as ClenderApplication

    @Test
    fun coldOwnedEditEntryNavigatesOnceToEventsDetailWithoutWritesOrMutation() {
        bindOwnedWidget(41)
        withActivity(editIntent(41, 1)) { controller ->
            val activity = controller.get()
            assertExactRoute(activity, 1)

            val eventCrud = ViewModelProvider(activity)[EventCrudViewModel::class.java]
            val terminal = runBlocking {
                withTimeout(5_000) {
                    eventCrud.state.first {
                        it.loadStatus == EventCrudLoadStatus.NOT_FOUND
                    }
                }
            }
            assertEquals(EventCrudRoute.Detail(1), terminal.route)
            assertNull(terminal.event)
            assertEquals(0L, application.container.scheduleMutationVersion.value)
            assertNull(runBlocking { application.container.eventRepository.findById(1) })
        }
    }

    @Test
    fun systemNewTaskColdEntryRoutesToDetailWithoutMutation() {
        bindOwnedWidget(41)
        val delivered = editIntent(41, 1).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        assertEquals(0x34000000, delivered.flags)
        withActivity(delivered) { controller ->
            assertExactRoute(controller.get(), 1)
            assertEquals(0L, application.container.scheduleMutationVersion.value)
            assertNull(runBlocking { application.container.eventRepository.findById(1) })
        }
    }

    @Test
    fun systemNewTaskHotEntryReplacesAboutWithOneDetailWithoutMutation() {
        bindOwnedWidget(51)
        withActivity(Intent(application, MainActivity::class.java)) { controller ->
            val activity = controller.get()
            ViewModelProvider(activity)[AppShellViewModel::class.java]
                .navigateTo(AppDestination.ABOUT)
            val delivered = editIntent(51, 81).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            controller.newIntent(delivered)
            controller.newIntent(delivered)

            assertExactRoute(activity, 81)
            assertEquals(0x34000000, activity.intent.flags)
            assertEquals(delivered.data, activity.intent.data)
            assertEquals(0L, application.container.scheduleMutationVersion.value)
        }
    }

    @Test
    fun warmLegalEntryReplacesExistingRouteUpdatesIntentAndRepeatDoesNotDuplicate() {
        bindOwnedWidget(51)
        withActivity(Intent(application, MainActivity::class.java)) { controller ->
            val activity = controller.get()
            val shell = ViewModelProvider(activity)[AppShellViewModel::class.java]
            shell.navigateTo(AppDestination.AI)
            shell.pushRoute(AppRoute.QuickAi)
            val legal = editIntent(51, Int.MAX_VALUE)

            controller.newIntent(legal)
            controller.newIntent(legal)

            assertExactRoute(activity, Int.MAX_VALUE)
            assertEquals(legal.action, activity.intent.action)
            assertEquals(legal.data, activity.intent.data)
            assertEquals(0L, application.container.scheduleMutationVersion.value)
        }
    }

    @Test
    fun recreateReestablishesOnlyTheCurrentLegalWidgetRoute() {
        bindOwnedWidget(61)
        withActivity(editIntent(61, 71)) { controller ->
            assertExactRoute(controller.get(), 71)

            controller.recreate()

            assertExactRoute(controller.get(), 71)
            assertEquals(0L, application.container.scheduleMutationVersion.value)
        }
    }

    @Test
    fun illegalWarmIntentPreservesDestinationRouteDraftDirtyFormAndAcceptedIntent() {
        bindOwnedWidget(81)
        withActivity(editIntent(81, 91)) { controller ->
            val activity = controller.get()
            val shell = ViewModelProvider(activity)[AppShellViewModel::class.java]
            val eventCrud = ViewModelProvider(activity)[EventCrudViewModel::class.java]
            val originalIntent = activity.intent
            val draftId = "0123456789abcdef0123456789abcdef"
            shell.navigateTo(AppDestination.EVENTS)
            shell.pushRoute(AppRoute.NewEvent(java.time.LocalDate.of(2026, 9, 2)))
            shell.drafts.set(draftId)
            eventCrud.startNew(java.time.LocalDate.of(2026, 9, 2))
            val dirtyForm = requireNotNull(eventCrud.state.value.form).copy(title = "dirty title")
            eventCrud.updateForm(dirtyForm)
            val stateBefore = shell.state.value
            val crudBefore = eventCrud.state.value
            val invalid = editIntent(81, 92).apply {
                action = Intent.ACTION_VIEW
                data = Uri.parse("https://example.invalid/event/92")
                addCategory(Intent.CATEGORY_BROWSABLE)
            }

            controller.newIntent(invalid)

            assertEquals(stateBefore, shell.state.value)
            assertEquals(crudBefore, eventCrud.state.value)
            assertEquals(draftId, shell.state.value.draftId)
            assertEquals(originalIntent.action, activity.intent.action)
            assertEquals(originalIntent.data, activity.intent.data)
            assertEquals(0L, application.container.scheduleMutationVersion.value)
        }
    }

    @Test
    fun coldCrossActionEntryRemainsAtDefaultRoot() {
        bindOwnedWidget(101)
        val invalid = editIntent(101, 111).apply {
            action = WidgetActionIntentContract.ACTION_LOCAL_REFRESH
        }

        withActivity(invalid) { controller ->
            val shell = ViewModelProvider(controller.get())[AppShellViewModel::class.java]
            assertEquals(AppDestination.CALENDAR, shell.state.value.destination)
            assertTrue(shell.state.value.childRoutes.isEmpty())
            assertEquals(0L, application.container.scheduleMutationVersion.value)
        }
    }

    @Test
    fun coldUnownedEntryRemainsAtDefaultRoot() {
        withActivity(editIntent(102, 111)) { controller ->
            val shell = ViewModelProvider(controller.get())[AppShellViewModel::class.java]
            assertEquals(AppDestination.CALENDAR, shell.state.value.destination)
            assertTrue(shell.state.value.childRoutes.isEmpty())
            assertEquals(0L, application.container.scheduleMutationVersion.value)
        }
    }

    private fun assertExactRoute(activity: MainActivity, eventId: Int) {
        val state = ViewModelProvider(activity)[AppShellViewModel::class.java].state.value
        assertEquals(AppDestination.EVENTS, state.destination)
        assertEquals(listOf(AppRoute.EventDetail(eventId)), state.childRoutes)
    }

    private fun editIntent(widgetId: Int, eventId: Int): Intent =
        Intent(WidgetActionIntentContract.ACTION_EDIT_EVENT).apply {
            component = ComponentName(application, MainActivity::class.java)
            setPackage(application.packageName)
            data = Uri.parse("clender-internal://widget/$widgetId/edit/$eventId")
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

    private fun bindOwnedWidget(id: Int) {
        val info = AppWidgetProviderInfo().apply {
            provider = ComponentName(application, ClenderWidgetProvider::class.java)
        }
        val shadow: ShadowAppWidgetManager =
            Shadows.shadowOf(AppWidgetManager.getInstance(application))
        shadow.putWidgetInfo(id, info)
    }

    private inline fun withActivity(
        intent: Intent,
        assertion: (ActivityController<MainActivity>) -> Unit
    ) {
        val controller = Robolectric.buildActivity(MainActivity::class.java, intent)
            .create()
            .start()
            .resume()
            .visible()
        try {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertion(controller)
        } finally {
            ProductionActivityTestResources.close(application) {
                controller.pause().stop().destroy()
            }
        }
    }
}
