package com.molotov.clender.app

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.os.Bundle
import android.util.TypedValue
import android.widget.TextView
import com.molotov.clender.R
import com.molotov.clender.domain.widget.WidgetConfiguration
import com.molotov.clender.testsupport.ProductionActivityTestResources
import com.molotov.clender.widget.ClenderWidgetProvider
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
class WidgetLocalRefreshRuntimeTest {
    private val containers = mutableListOf<AppContainer>()
    private val application: ClenderApplication
        get() = RuntimeEnvironment.getApplication() as ClenderApplication

    @After
    fun closeRuntimeBeforeRemovingOnlySandboxArtifacts() {
        ProductionActivityTestResources.close(application) {
            containers.asReversed().forEach(AppContainer::close)
        }
    }

    @Test
    fun manualLocalRefreshConsumesPolicyAndUpdatesOnlyTargetOnApi26And36() = runBlocking {
        val container = createContainer()
        val manager = AppWidgetManager.getInstance(application)
        bind(manager, TARGET_WIDGET_ID)
        bind(manager, OTHER_WIDGET_ID)
        container.widgetProviderRuntime.update(manager, intArrayOf(OTHER_WIDGET_ID))
        val managerShadow = shadowOf(manager)
        val otherBefore = dateFont(manager, OTHER_WIDGET_ID)
        container.widgetConfigurationRuntime.saveConfiguration(
            requireNotNull(container.widgetConfigurationRuntime.loadConfiguration(OTHER_WIDGET_ID))
                .copy(fontSizeSp = 8)
        )
        container.widgetConfigurationRuntime.saveConfiguration(
            WidgetConfiguration.defaults(TARGET_WIDGET_ID, 20)
        )

        container.widgetProviderRuntime.localRefresh(manager, TARGET_WIDGET_ID)

        assertNotNull(managerShadow.getViewFor(TARGET_WIDGET_ID))
        assertEquals(otherBefore, dateFont(manager, OTHER_WIDGET_ID), 0.01f)
        assertEquals(
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                20f,
                application.resources.displayMetrics
            ),
            dateFont(manager, TARGET_WIDGET_ID),
            0.01f
        )
        assertEquals(0L, container.scheduleMutationVersion.value)
        val source = appContainerSource()
        assertTrue(source.contains("WidgetRefreshTrigger.MANUAL_LOCAL_REFRESH"))
        assertTrue(runtimePolicySource().contains("WidgetRefreshPolicy.plan"))
    }

    @Test
    fun optionalMissingConfigurationUsesExistingDefaultPathWithoutEventWrites() = runBlocking {
        val container = createContainer()
        val manager = AppWidgetManager.getInstance(application)
        bind(manager, TARGET_WIDGET_ID)
        assertEquals(null, container.widgetConfigurationRuntime.loadConfiguration(TARGET_WIDGET_ID))

        container.widgetProviderRuntime.localRefresh(manager, TARGET_WIDGET_ID)

        val stored = container.widgetConfigurationRuntime.loadConfiguration(TARGET_WIDGET_ID)
        assertNotNull(stored)
        assertEquals(TARGET_WIDGET_ID, stored?.appWidgetId)
        assertEquals(WidgetConfiguration.defaults(TARGET_WIDGET_ID, 13), stored)
        assertEquals(0L, container.scheduleMutationVersion.value)
    }

    @Test
    fun rapidRepeatsAndCrossInstanceRefreshesRemainPerInstanceAndUseOneRuntime() = runBlocking {
        val container = createContainer()
        val manager = AppWidgetManager.getInstance(application)
        bind(manager, TARGET_WIDGET_ID)
        bind(manager, OTHER_WIDGET_ID)
        val runtime = container.widgetProviderRuntime

        listOf(
            async { runtime.localRefresh(manager, TARGET_WIDGET_ID) },
            async { runtime.localRefresh(manager, TARGET_WIDGET_ID) },
            async { runtime.localRefresh(manager, OTHER_WIDGET_ID) }
        ).awaitAll()

        assertSame(runtime, container.widgetProviderRuntime)
        assertNotNull(shadowOf(manager).getViewFor(TARGET_WIDGET_ID))
        assertNotNull(shadowOf(manager).getViewFor(OTHER_WIDGET_ID))
        assertEquals(0L, container.scheduleMutationVersion.value)
    }

    @Test
    fun refreshDeleteRaceReusesCoordinatorGenerationAndCannotPublishLateTarget() = runBlocking {
        val container = createContainer()
        val manager = AppWidgetManager.getInstance(application)
        bind(manager, TARGET_WIDGET_ID)
        val runtime = container.widgetProviderRuntime

        runtime.localRefresh(manager, TARGET_WIDGET_ID)
        runtime.delete(intArrayOf(TARGET_WIDGET_ID))

        assertEquals(null, container.widgetConfigurationRuntime.loadConfiguration(TARGET_WIDGET_ID))
        assertEquals(0L, container.scheduleMutationVersion.value)
        val source = appContainerSource()
        assertEquals(1, source.countToken("WidgetUpdateCoordinator("))
        assertFalse(source.contains("WidgetLocalRefreshCoordinator"))
    }

    @Test
    fun closeBeforeManualRefreshPreventsQueryRenderAndConfigurationWrite() = runBlocking {
        val container = createContainer()
        val manager = AppWidgetManager.getInstance(application)
        bind(manager, TARGET_WIDGET_ID)
        val runtime = container.widgetProviderRuntime

        container.close()
        runtime.localRefresh(manager, TARGET_WIDGET_ID)

        assertFalse(application.getDatabasePath(AppContainer.DATABASE_NAME).exists())
        assertFalse(preferenceFile().exists())
        assertEquals(null, shadowOf(manager).getViewFor(TARGET_WIDGET_ID))
    }

    @Test
    fun localRefreshAssemblyCreatesNoSecondScopeStoreCoordinatorOrForbiddenSubsystem() {
        val source = appContainerSource()
        val localRefreshBody = functionBody(source, "localRefresh")

        assertTrue(localRefreshBody.contains("runtime.request"))
        assertTrue(localRefreshBody.contains("MANUAL_LOCAL_REFRESH"))
        assertTrue(localRefreshBody.contains(".await()"))
        assertTrue(runtimePolicySource().contains("WidgetRefreshPolicy.plan"))
        assertEquals(1, source.countToken("WidgetUpdateCoordinator("))
        assertEquals(1, source.countToken("DataStoreWidgetConfigurationStore("))
        assertEquals(1, source.countToken("WidgetAutomaticRefreshRuntime("))
        listOf(
            "CoroutineScope(",
            "SupervisorJob(",
            "DataStoreWidgetConfigurationStore(",
            "WidgetUpdateCoordinator(",
            "Room.",
            "EventService",
            "ScheduleMutation",
            "WorkManager",
            "OkHttp",
            "WebDav",
            "AiCoordinator",
            "KeyStore"
        ).forEach { forbidden -> assertFalse(localRefreshBody.contains(forbidden)) }
    }

    private fun bind(manager: AppWidgetManager, appWidgetId: Int) {
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

    private fun createContainer(): AppContainer = AppContainer(application).also(containers::add)

    private fun dateFont(manager: AppWidgetManager, id: Int): Float =
        requireNotNull(shadowOf(manager).getViewFor(id))
            .findViewById<TextView>(R.id.widget_date).textSize

    private fun runtimePolicySource(): String = requiredSource(
        "app/src/main/java/com/molotov/clender/app/widget/WidgetRefreshBatch.kt"
    )

    private fun appContainerSource(): String = requiredSource(APP_CONTAINER_SOURCE_PATH)

    private fun requiredSource(path: String): String =
        generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .flatMap { root -> sequenceOf(File(root, path), File(root, "android/$path")) }
            .firstOrNull(File::isFile)
            ?.readText()
            ?: error("required source is missing: $path")

    private fun functionBody(source: String, function: String): String {
        val declaration = Regex("override\\s+suspend\\s+fun\\s+$function\\s*\\(").find(source)
            ?: error("missing function source: $function")
        val openBrace = source.indexOf('{', declaration.range.last)
        require(openBrace >= 0) { "missing function body: $function" }
        var depth = 0
        for (index in openBrace until source.length) {
            when (source[index]) {
                '{' -> depth += 1

                '}' -> {
                    depth -= 1
                    if (depth == 0) return source.substring(openBrace, index + 1)
                }
            }
        }
        error("unterminated function body: $function")
    }

    private fun String.countToken(token: String): Int = windowed(token.length).count { it == token }

    private fun preferenceFile(): File =
        File(application.filesDir, AppContainer.PREFERENCES_RELATIVE_PATH)

    private companion object {
        const val TARGET_WIDGET_ID = 741
        const val OTHER_WIDGET_ID = 742
        const val APP_CONTAINER_SOURCE_PATH =
            "app/src/main/java/com/molotov/clender/app/AppContainer.kt"
    }
}
