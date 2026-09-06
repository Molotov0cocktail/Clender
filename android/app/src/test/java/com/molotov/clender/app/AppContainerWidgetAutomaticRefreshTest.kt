package com.molotov.clender.app

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.os.Bundle
import android.util.TypedValue
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.molotov.clender.R
import com.molotov.clender.app.widget.WidgetDateWorkPort
import com.molotov.clender.app.widget.WidgetRefreshCompletion
import com.molotov.clender.core.model.Event
import com.molotov.clender.core.model.EventType
import com.molotov.clender.domain.event.AddEventCommand
import com.molotov.clender.domain.event.EventRepository
import com.molotov.clender.domain.widget.WidgetConfiguration
import com.molotov.clender.domain.widget.WidgetDateBoundarySchedule
import com.molotov.clender.domain.widget.WidgetRefreshTrigger
import com.molotov.clender.testsupport.ProductionActivityTestResources
import com.molotov.clender.widget.ClenderWidgetProvider
import com.molotov.clender.widget.WidgetAutomaticRefreshOwner
import java.io.File
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], application = ClenderApplication::class)
class AppContainerWidgetAutomaticRefreshTest {
    private val application: ClenderApplication
        get() = RuntimeEnvironment.getApplication() as ClenderApplication

    private val containers = mutableListOf<AppContainer>()
    private val dates = RecordingDates()
    private val manager: AppWidgetManager
        get() = AppWidgetManager.getInstance(application)

    @After
    fun closeIsolatedResources() {
        ProductionActivityTestResources.close(application) {
            containers.asReversed().forEach(AppContainer::close)
        }
    }

    @Test
    fun emptyRestoreDoesNotInitializeRoomOrPreferences() = runBlocking {
        val container = container()
        assertEquals(
            WidgetRefreshCompletion.NO_WIDGETS,
            container.widgetAutomaticRefreshRuntime.restore().await()
        )
        assertFalse(application.getDatabasePath(AppContainer.DATABASE_NAME).exists())
        assertFalse(preferences().exists())
        assertEquals(0, dates.replacements.get())
    }

    @Test
    fun coldDeleteClearsStoredConfigurationWithoutOpeningRoom() = runBlocking {
        val container = container()
        container.widgetConfigurationRuntime.saveConfiguration(
            com.molotov.clender.domain.widget.WidgetConfiguration.defaults(711, 13)
        )
        assertNotNull(container.widgetConfigurationRuntime.loadConfiguration(711))
        container.widgetAutomaticRefreshRuntime.instancesChanged(setOf(711)).await()
        assertNull(container.widgetConfigurationRuntime.loadConfiguration(711))
        assertFalse(application.getDatabasePath(AppContainer.DATABASE_NAME).exists())
        assertEquals(0, dates.replacements.get())
    }

    @Test
    fun optionalOwnedInstanceIsDiscoveredWithoutConfigurationRegistry() = runBlocking {
        val container = container()
        bind(712)
        assertFalse(preferences().exists())
        assertEquals(
            WidgetRefreshCompletion.COMPLETED,
            container.widgetAutomaticRefreshRuntime.restore().await()
        )
        assertNotNull(shadowOf(manager).getViewFor(712))
        assertNotNull(container.widgetConfigurationRuntime.loadConfiguration(712))
        assertEquals(1, dates.replacements.get())
        assertEquals(0L, container.scheduleMutationVersion.value)
    }

    @Test
    fun positiveButUnownedTargetCannotOpenRoomOrCreateConfiguration() = runBlocking {
        val container = container()
        container.widgetProviderRuntime.localRefresh(manager, 713)
        assertFalse(application.getDatabasePath(AppContainer.DATABASE_NAME).exists())
        assertFalse(preferences().exists())
        assertNull(manager.getAppWidgetInfo(713))
        assertEquals(0, dates.replacements.get())
    }

    @Test
    fun localMutationActuallyRepaintsOwnedHostWithoutAdditionalManualRequest() = runBlocking {
        val container = container()
        bind(714)
        container.widgetConfigurationRuntime.saveConfiguration(
            WidgetConfiguration.defaults(714, 13).copy(
                startTime = LocalTime.MIN,
                endTime = LocalTime.of(23, 59)
            )
        )
        container.widgetAutomaticRefreshRuntime.restore().await()
        val start = LocalDateTime.now().toLocalDate().atStartOfDay()
        container.eventService.add(
            AddEventCommand(
                EventType.TIMESPAN,
                "assembly mutation",
                start,
                start.plusDays(1)
            )
        )
        withTimeout(5_000) {
            while (eventTitle(714) != "assembly mutation") yield()
        }
        assertEquals("assembly mutation", eventTitle(714))
        assertEquals(1L, container.scheduleMutationVersion.value)
        assertEquals(1, dates.replacements.get())
    }

    @Test
    fun configurationRefreshUsesSameRuntimeAndPreservesOtherInstanceView() = runBlocking {
        val container = container()
        bind(715)
        bind(716)
        container.widgetAutomaticRefreshRuntime.restore().await()
        val otherFont = dateFont(716)
        container.widgetConfigurationRuntime.saveConfiguration(
            requireNotNull(container.widgetConfigurationRuntime.loadConfiguration(715))
                .copy(fontSizeSp = 20)
        )
        container.widgetConfigurationRuntime.saveConfiguration(
            requireNotNull(container.widgetConfigurationRuntime.loadConfiguration(716))
                .copy(fontSizeSp = 8)
        )
        container.widgetConfigurationRuntime.requestWidgetUpdate(715)
        withTimeout(5_000) {
            while (kotlin.math.abs(dateFont(715) - fontPixels(20)) > 0.01f) yield()
        }
        assertEquals(fontPixels(20), dateFont(715), 0.01f)
        assertEquals(otherFont, dateFont(716), 0.01f)
        assertEquals(0L, container.scheduleMutationVersion.value)
        assertEquals(1, dates.replacements.get())
    }

    @Test
    fun applicationExposesOneAutomaticRuntimeAndExistingScope() {
        val owner = application as WidgetAutomaticRefreshOwner
        assertSame(application.container.widgetProviderScope, owner.widgetAutomaticRefreshScope)
        assertSame(
            application.container.widgetAutomaticRefreshRuntime,
            owner.widgetAutomaticRefreshRuntime
        )
        assertSame(owner.widgetAutomaticRefreshRuntime, owner.widgetAutomaticRefreshRuntime)
    }

    @Test
    fun mainThreadCloseJoinsWidgetIoAndRejectsLaterWork() = runBlocking {
        val container = container()
        val runtime = container.widgetAutomaticRefreshRuntime
        runtime.restore().await()
        val job = requireNotNull(container.widgetProviderScope.coroutineContext[Job])
        val cancellations = dates.cancellations.get()
        container.close()
        container.close()
        assertTrue(job.isCompleted)
        val receipt = runtime.request(WidgetRefreshTrigger.FOREGROUND)
        assertFalse(receipt.accepted)
        assertEquals(WidgetRefreshCompletion.CLOSED, receipt.await())
        assertEquals(cancellations, dates.cancellations.get())
        assertFalse(application.getDatabasePath(AppContainer.DATABASE_NAME).exists())
    }

    @Test
    fun repeatedContainerBindingUsesOneObserverAndCloseRemovesIt() {
        val container = container()
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle = registry
        }
        container.bindWidgetLifecycle(owner.lifecycle)
        container.bindWidgetLifecycle(owner.lifecycle)
        assertEquals(1, owner.registry.observerCount)
        assertFalse(preferences().exists())
        assertFalse(application.getDatabasePath(AppContainer.DATABASE_NAME).exists())
        container.close()
        assertEquals(0, owner.registry.observerCount)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        assertEquals(0, dates.replacements.get())
        assertFalse(preferences().exists())
    }

    @Test
    fun realCoordinatorPausedReadAndQueuedOldTargetCannotRecreateDeletedInstance() = runBlocking {
        val repository = PausedRepository()
        val container = AppContainer(application, widgetDateWork = dates, widgetEvents = repository)
            .also(containers::add)
        bind(717)
        val runtime = container.widgetAutomaticRefreshRuntime
        val first = runtime.restore()
        withTimeout(5_000) { repository.entered.await() }
        val queued = runtime.request(WidgetRefreshTrigger.MANUAL_LOCAL_REFRESH, 717)
        val deleted = runtime.instancesChanged(setOf(717))
        repository.release.complete(Unit)
        withTimeout(5_000) {
            first.await()
            queued.await()
            deleted.await()
        }
        // Platform may still report ownership during onDeleted; tombstone must win anyway.
        assertNotNull(manager.getAppWidgetInfo(717))
        assertNull(shadowOf(manager).getViewFor(717))
        assertNull(container.widgetConfigurationRuntime.loadConfiguration(717))
        assertEquals(1, repository.queries.get())
        assertFalse(application.getDatabasePath(AppContainer.DATABASE_NAME).exists())
    }

    @Test
    fun preparedProductionTicketCannotQueryOrPublishAfterDeletionBeforeInvocation() = runBlocking {
        val repository = PausedRepository()
        repository.release.complete(Unit)
        val container = AppContainer(application, widgetDateWork = dates, widgetEvents = repository)
            .also(containers::add)
        bind(718)
        val local = container.widgetLocalUpdatePort
        val prepared = local.prepareUpdate(718)
        assertEquals(0, repository.queries.get())
        assertFalse(preferences().exists())
        assertFalse(application.getDatabasePath(AppContainer.DATABASE_NAME).exists())
        assertNull(shadowOf(manager).getViewFor(718))

        local.invalidate(setOf(718))
        local.delete(setOf(718))
        prepared()

        assertEquals(0, repository.queries.get())
        assertNull(shadowOf(manager).getViewFor(718))
        assertNull(container.widgetConfigurationRuntime.loadConfiguration(718))
        assertFalse(application.getDatabasePath(AppContainer.DATABASE_NAME).exists())
    }

    private fun container(): AppContainer = AppContainer(application, widgetDateWork = dates)
        .also(containers::add)

    private fun preferences(): File =
        File(application.filesDir, AppContainer.PREFERENCES_RELATIVE_PATH)

    private fun eventTitle(id: Int): String? = shadowOf(manager).getViewFor(id)
        ?.findViewById<TextView>(R.id.widget_event_title)?.text?.toString()

    private fun dateFont(id: Int): Float = requireNotNull(shadowOf(manager).getViewFor(id))
        .findViewById<TextView>(R.id.widget_date).textSize

    private fun fontPixels(sp: Int): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        sp.toFloat(),
        application.resources.displayMetrics
    )

    private fun bind(id: Int) {
        shadowOf(manager).addBoundWidget(
            id,
            AppWidgetProviderInfo().apply {
                provider = ComponentName(application, ClenderWidgetProvider::class.java)
            }
        )
        manager.updateAppWidgetOptions(
            id,
            Bundle().apply {
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 250)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 250)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 250)
            }
        )
    }

    private class RecordingDates : WidgetDateWorkPort {
        val replacements = AtomicInteger()
        val cancellations = AtomicInteger()

        override suspend fun replace(schedule: WidgetDateBoundarySchedule) {
            replacements.incrementAndGet()
        }

        override suspend fun cancel() {
            cancellations.incrementAndGet()
        }
    }

    private class PausedRepository : EventRepository {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val queries = AtomicInteger()

        override fun observeRange(start: LocalDateTime, end: LocalDateTime): Flow<List<Event>> =
            flow {
                queries.incrementAndGet()
                entered.complete(Unit)
                release.await()
                emit(emptyList())
            }

        override suspend fun findById(id: Long): Event? = error("No direct lookup")
        override suspend fun insert(event: Event): Event = error("No local write")
        override suspend fun update(event: Event): Event = error("No local write")
    }
}
