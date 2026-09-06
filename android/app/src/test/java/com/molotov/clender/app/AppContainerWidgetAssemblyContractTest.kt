package com.molotov.clender.app

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import com.molotov.clender.R
import com.molotov.clender.app.widget.WidgetUpdateCoordinator
import com.molotov.clender.core.model.EventType
import com.molotov.clender.data.local.RoomEventRepository
import com.molotov.clender.data.settings.DataStoreWidgetConfigurationStore
import com.molotov.clender.domain.event.AddEventCommand
import com.molotov.clender.domain.widget.WidgetConfiguration
import com.molotov.clender.domain.widget.WidgetThemeMode
import com.molotov.clender.testsupport.ProductionActivityTestResources
import com.molotov.clender.ui.navigation.AppDestination
import com.molotov.clender.ui.navigation.AppRoute
import com.molotov.clender.ui.state.AppShellViewModel
import com.molotov.clender.widget.ClenderWidgetProvider
import com.molotov.clender.widget.WidgetActionIntentContract
import com.molotov.clender.widget.WidgetConfigurationActivity
import com.molotov.clender.widget.WidgetConfigurationActivityOwner
import com.molotov.clender.widget.WidgetLocalRefreshReceiver
import com.molotov.clender.widget.WidgetProviderRuntimeOwner
import java.io.File
import java.lang.reflect.Modifier
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.ArrayDeque
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], application = ClenderApplication::class)
class AppContainerWidgetAssemblyContractTest {
    private val containers = mutableListOf<AppContainer>()

    private val application: ClenderApplication
        get() = RuntimeEnvironment.getApplication() as ClenderApplication

    @After
    fun removeOnlyIsolatedAndroidSandboxArtifacts() {
        ProductionActivityTestResources.close(application) {
            containers.asReversed().forEach(AppContainer::close)
        }
    }

    @Test
    fun ordinaryApplicationColdStartKeepsWidgetRuntimeDataStoreAndRoomLazy() {
        val databaseFile = application.getDatabasePath(AppContainer.DATABASE_NAME)
        val preferenceFile = preferenceFile()

        assertFalse(databaseFile.exists())
        assertFalse(preferenceFile.exists())
        application.container.eventRepository

        assertFalse(databaseFile.exists())
        assertFalse(preferenceFile.exists())
        assertTrue(initializedObjectGraph(application.container).none(::isWidgetRuntimeObject))
    }

    @Test
    fun applicationOwnsOneLazyWidgetScopeAndProductionRuntime() {
        val owner = application as WidgetProviderRuntimeOwner
        val container = application.container

        assertSame(container.widgetProviderScope, owner.widgetProviderScope)
        assertSame(container.widgetProviderRuntime, owner.widgetProviderRuntime)
        val graph = initializedObjectGraph(container.widgetProviderRuntime)
        assertTrue(graph.none { it is WidgetUpdateCoordinator })
        assertTrue(graph.none { it is DataStoreWidgetConfigurationStore })
        assertTrue(graph.none { it is RoomEventRepository })
        assertTrue(graph.none(::isForbiddenFirstWidgetRuntimeObject))
        assertFalse(application.getDatabasePath(AppContainer.DATABASE_NAME).exists())
        assertFalse(preferenceFile().exists())
    }

    @Test
    fun configurationOwnerReusesContainerPortAndStaysDataStoreOnlyUntilExplicitUpdate() =
        runBlocking {
            val owner = application as WidgetConfigurationActivityOwner
            val container = application.container
            bindLargeWidget(AppWidgetManager.getInstance(application), 471)
            assertSame(container.widgetConfigurationRuntime, owner.widgetConfigurationRuntime)
            assertTrue(owner.widgetConfigurationRuntime.loadConfiguration(471) == null)
            assertTrue(owner.widgetConfigurationRuntime.widgetFontSizeSp() == 13)
            assertFalse(application.getDatabasePath(AppContainer.DATABASE_NAME).exists())

            owner.widgetConfigurationRuntime.saveConfiguration(
                WidgetConfiguration(
                    appWidgetId = 471,
                    startTime = LocalTime.of(8, 0),
                    endTime = LocalTime.of(22, 0),
                    opacityPercent = 100,
                    fontSizeSp = 13,
                    theme = WidgetThemeMode.SYSTEM
                )
            )
            assertTrue(preferenceFile().exists())
            assertFalse(application.getDatabasePath(AppContainer.DATABASE_NAME).exists())
            assertTrue(
                initializedObjectGraph(container).none(::isForbiddenFirstWidgetRuntimeObject)
            )

            owner.widgetConfigurationRuntime.requestWidgetUpdate(471)
            withTimeout(5_000) {
                while (!application.getDatabasePath(AppContainer.DATABASE_NAME).exists()) yield()
            }
            assertTrue(application.getDatabasePath(AppContainer.DATABASE_NAME).exists())
            assertTrue(container.scheduleMutationVersion.value == 0L)
        }

    @Test
    fun firstWidgetUpdateUsesLocalDataOnlyAndDoesNotInitializeAiWebDavOrKeystore() = runBlocking {
        val container = createContainer()
        val manager = AppWidgetManager.getInstance(application)
        val appWidgetId = 47
        bindLargeWidget(manager, appWidgetId)
        manager.updateAppWidgetOptions(
            appWidgetId,
            Bundle().apply {
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 110)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 250)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 110)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 250)
            }
        )

        container.widgetProviderRuntime.update(manager, intArrayOf(appWidgetId))

        assertTrue(application.getDatabasePath(AppContainer.DATABASE_NAME).exists())
        assertTrue(preferenceFile().exists())
        assertTrue(initializedObjectGraph(container).none(::isForbiddenFirstWidgetRuntimeObject))
        assertTrue(container.scheduleMutationVersion.value == 0L)
    }

    @Test
    fun productionRuntimePublishesExactConfigureActionsForTwoBoundWidgetInstances() = runBlocking {
        val container = createContainer()
        val manager = AppWidgetManager.getInstance(application)
        val managerShadow = shadowOf(manager)
        val provider = ComponentName(application, ClenderWidgetProvider::class.java)
        val ids = listOf(401, 402)
        ids.forEach { appWidgetId ->
            managerShadow.addBoundWidget(
                appWidgetId,
                AppWidgetProviderInfo().apply { this.provider = provider }
            )
            manager.updateAppWidgetOptions(
                appWidgetId,
                Bundle().apply {
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 110)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 250)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 110)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 250)
                }
            )
        }

        container.widgetProviderRuntime.update(manager, ids.toIntArray())

        val startedData = ids.map { appWidgetId ->
            val root = requireNotNull(managerShadow.getViewFor(appWidgetId))
            val configure = requireNotNull(root.findViewById<View>(R.id.widget_configure))
            assertTrue(
                "production Configure must be clickable for $appWidgetId",
                configure.hasOnClickListeners()
            )
            shadowOf(application).clearNextStartedActivities()
            assertTrue(configure.performClick())
            val started = requireNotNull(shadowOf(application).nextStartedActivity)
            assertEquals(
                ComponentName(application, WidgetConfigurationActivity::class.java),
                started.component
            )
            assertEquals(application.packageName, started.`package`)
            assertEquals(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE, started.action)
            assertEquals(
                "clender-internal://widget/$appWidgetId/configure",
                started.dataString
            )
            assertEquals(
                appWidgetId,
                started.getIntExtra(
                    AppWidgetManager.EXTRA_APPWIDGET_ID,
                    AppWidgetManager.INVALID_APPWIDGET_ID
                )
            )
            assertEquals(setOf(AppWidgetManager.EXTRA_APPWIDGET_ID), started.extras?.keySet())
            assertNotNull(manager.getAppWidgetInfo(appWidgetId))
            started.dataString
        }
        assertNotEquals(startedData[0], startedData[1])
    }

    @Test
    fun productionEventRowOpensColdMainActivityAtExactDetailWithoutWidgetWrites() = runBlocking {
        val container = application.container
        val manager = AppWidgetManager.getInstance(application)
        val appWidgetId = 501
        bindLargeWidget(manager, appWidgetId)
        val now = LocalDateTime.now(ZoneId.systemDefault()).withSecond(0).withNano(0)
        val event = container.eventService.add(
            AddEventCommand(
                eventType = EventType.TIMESPAN,
                title = "production widget edit",
                startTime = now.minusMinutes(30),
                endTime = now.plusMinutes(30)
            )
        )
        container.widgetConfigurationRuntime.saveConfiguration(
            fullDayConfiguration(appWidgetId)
        )
        val mutationBeforeClick = container.scheduleMutationVersion.value

        container.widgetProviderRuntime.update(manager, intArrayOf(appWidgetId))

        val root = requireNotNull(shadowOf(manager).getViewFor(appWidgetId))
        val eventRow = requireNotNull(root.findViewById<View>(R.id.widget_event_row))
        shadowOf(application).clearNextStartedActivities()
        assertTrue(eventRow.performClick())
        val editIntent = requireNotNull(shadowOf(application).nextStartedActivity)
        assertEquals(
            "clender-internal://widget/$appWidgetId/edit/${event.id}",
            editIntent.dataString
        )

        val controller = Robolectric.buildActivity(MainActivity::class.java, editIntent)
            .create()
            .start()
            .resume()
            .visible()
        try {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            val shell = ViewModelProvider(controller.get())[AppShellViewModel::class.java]
            assertEquals(AppDestination.EVENTS, shell.state.value.destination)
            assertEquals(
                listOf(AppRoute.EventDetail(event.id.toInt())),
                shell.state.value.childRoutes
            )
            assertEquals(mutationBeforeClick, container.scheduleMutationVersion.value)
        } finally {
            controller.pause().stop().destroy()
            shadowOf(android.os.Looper.getMainLooper()).idle()
        }
    }

    @Test
    @Config(sdk = [26])
    fun productionLargeRefreshBroadcastUpdatesOnlyTargetThroughApplicationRuntime() = runBlocking {
        val container = application.container
        val manager = AppWidgetManager.getInstance(application)
        val managerShadow = shadowOf(manager)
        val targetWidgetId = 511
        val otherWidgetId = 512
        bindLargeWidget(manager, targetWidgetId)
        bindLargeWidget(manager, otherWidgetId)
        container.widgetProviderRuntime.update(manager, intArrayOf(targetWidgetId, otherWidgetId))
        val targetBefore = requireNotNull(managerShadow.getViewFor(targetWidgetId))
        val otherBefore = widgetDateFont(manager, otherWidgetId)
        val refresh = requireNotNull(targetBefore.findViewById<View>(R.id.widget_refresh))
        val expectedFont = prepareRefreshFonts(container, targetWidgetId, otherWidgetId)
        shadowOf(application).broadcastIntents.clear()
        val receiver = WidgetLocalRefreshReceiver()
        val pendingResult = ReflectionHelpers.callConstructor(
            BroadcastReceiver.PendingResult::class.java
        )
        ReflectionHelpers.callInstanceMethod<Unit>(
            receiver,
            "setPendingResult",
            ClassParameter.from(BroadcastReceiver.PendingResult::class.java, pendingResult)
        )

        assertTrue(refresh.performClick())
        val broadcast = requireNotNull(shadowOf(application).broadcastIntents.lastOrNull())
        assertEquals(
            ComponentName(application, WidgetLocalRefreshReceiver::class.java),
            broadcast.component
        )
        assertEquals(targetWidgetId, broadcast.widgetIdExtra())
        assertNotNull(WidgetActionIntentContract.validateLocalRefresh(application, broadcast))
        receiver.onReceive(application, broadcast)
        withTimeout(5_000) {
            while (!shadowOf(pendingResult).future.isDone ||
                kotlin.math.abs(
                    widgetDateFont(manager, targetWidgetId) - expectedFont
                ) > 0.01f
            ) {
                shadowOf(android.os.Looper.getMainLooper()).idle()
                yield()
            }
        }

        assertTrue(shadowOf(pendingResult).future.isDone)
        assertNotNull(container.widgetConfigurationRuntime.loadConfiguration(targetWidgetId))
        assertEquals(expectedFont, widgetDateFont(manager, targetWidgetId), 0.01f)
        assertEquals(otherBefore, widgetDateFont(manager, otherWidgetId), 0.01f)
        assertEquals(0L, container.scheduleMutationVersion.value)
        assertTrue(initializedObjectGraph(container).none(::isForbiddenFirstWidgetRuntimeObject))
    }

    @Test
    fun closeCancelsWidgetScopeBeforeDataStoreAndRoomAndPreventsLaterWork() = runBlocking {
        val container = createContainer()
        val scope = container.widgetProviderScope
        val runtime = container.widgetProviderRuntime
        val job = checkNotNull(scope.coroutineContext[Job])
        assertTrue(job.isActive)

        container.close()
        assertFalse(job.isActive)

        runtime.update(AppWidgetManager.getInstance(application), intArrayOf(91))
        assertFalse(application.getDatabasePath(AppContainer.DATABASE_NAME).exists())
        assertFalse(preferenceFile().exists())
    }

    private fun createContainer(): AppContainer = AppContainer(application).also(containers::add)

    private suspend fun prepareRefreshFonts(
        container: AppContainer,
        targetId: Int,
        otherId: Int
    ): Float {
        listOf(targetId to 20, otherId to 8).forEach { (id, size) ->
            container.widgetConfigurationRuntime.saveConfiguration(
                requireNotNull(container.widgetConfigurationRuntime.loadConfiguration(id))
                    .copy(fontSizeSp = size)
            )
        }
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            20f,
            application.resources.displayMetrics
        )
    }

    private fun widgetDateFont(manager: AppWidgetManager, id: Int): Float =
        requireNotNull(shadowOf(manager).getViewFor(id))
            .findViewById<TextView>(R.id.widget_date).textSize

    private fun bindLargeWidget(manager: AppWidgetManager, appWidgetId: Int) {
        shadowOf(manager).addBoundWidget(
            appWidgetId,
            AppWidgetProviderInfo().apply {
                provider = ComponentName(application, ClenderWidgetProvider::class.java)
            }
        )
        manager.updateAppWidgetOptions(
            appWidgetId,
            Bundle().apply {
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 250)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 250)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 250)
            }
        )
    }

    private fun fullDayConfiguration(appWidgetId: Int): WidgetConfiguration = WidgetConfiguration(
        appWidgetId = appWidgetId,
        startTime = LocalTime.MIN,
        endTime = LocalTime.of(23, 59),
        opacityPercent = 100,
        fontSizeSp = 13,
        theme = WidgetThemeMode.SYSTEM
    )

    private fun Intent.widgetIdExtra(): Int = getIntExtra(
        AppWidgetManager.EXTRA_APPWIDGET_ID,
        AppWidgetManager.INVALID_APPWIDGET_ID
    )

    private fun preferenceFile(): File =
        File(application.filesDir, AppContainer.PREFERENCES_RELATIVE_PATH)

    private fun isWidgetRuntimeObject(value: Any): Boolean {
        val name = value::class.java.name
        return name.endsWith(".WidgetUpdateCoordinator") ||
            name.endsWith(".DataStoreWidgetConfigurationStore") ||
            name.endsWith(".WidgetProviderRuntime")
    }

    private fun isForbiddenFirstWidgetRuntimeObject(value: Any): Boolean {
        val name = value::class.java.name
        return name.endsWith(".AiCoordinator") ||
            name.endsWith(".OkHttpAiClient") ||
            name.endsWith(".EnvelopeSecretStore") ||
            name.endsWith(".AndroidKeyStoreSecretKeyProvider") ||
            name.endsWith(".WebDavClient") ||
            name.endsWith(".WebDavSyncRuntime") ||
            name.endsWith(".WebDavConnectionProbe") ||
            name.endsWith(".OkHttpClient")
    }

    private fun initializedObjectGraph(root: Any): Set<Any> {
        val visited = java.util.Collections.newSetFromMap(
            java.util.IdentityHashMap<Any, Boolean>()
        )
        val queue = ArrayDeque<Any>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!visited.add(current)) continue
            enqueueReachableFields(current, queue)
        }
        return visited
    }

    private fun enqueueReachableFields(current: Any, queue: ArrayDeque<Any>) {
        allFields(current.javaClass).forEach { field ->
            if (Modifier.isStatic(field.modifiers)) return@forEach
            if (!runCatching { field.isAccessible = true }.isSuccess) return@forEach
            val value = runCatching { field.get(current) }.getOrNull() ?: return@forEach
            when (value) {
                is Lazy<*> -> if (value.isInitialized()) value.value?.let(queue::add)
                else -> if (shouldTraverse(value)) queue.add(value)
            }
        }
    }

    private fun allFields(type: Class<*>): Sequence<java.lang.reflect.Field> = sequence {
        var current: Class<*>? = type
        while (current != null && current != Any::class.java) {
            yieldAll(current.declaredFields.asSequence())
            current = current.superclass
        }
    }

    private fun shouldTraverse(value: Any): Boolean {
        val name = value::class.java.name
        return name.startsWith("com.molotov.clender.") || value is CoroutineScope
    }
}
