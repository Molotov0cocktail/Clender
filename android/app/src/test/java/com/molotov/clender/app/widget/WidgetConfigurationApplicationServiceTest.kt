package com.molotov.clender.app.widget

import com.molotov.clender.domain.widget.WidgetConfiguration
import com.molotov.clender.domain.widget.WidgetThemeMode
import java.time.LocalTime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetConfigurationApplicationServiceTest {
    @Test
    fun existingConfigurationLoadsExactlyWithoutReadingDefaults() = runBlocking {
        val existing = configuration(
            appWidgetId = 41,
            times = LocalTime.of(0, 0) to LocalTime.of(23, 59),
            opacityPercent = 0,
            fontSizeSp = 20,
            theme = WidgetThemeMode.DARK
        )
        val runtime = RecordingConfigurationRuntime(existing = existing, fontSizeSp = 17)
        val service = WidgetConfigurationApplicationService(runtime)

        assertEquals(existing, service.load(41))
        assertEquals(listOf(41), runtime.loadCalls)
        assertEquals(0, runtime.fontReads)
        assertTrue(runtime.saved.isEmpty())
    }

    @Test
    fun missingConfigurationBuildsMemoryOnlyDefaultsFromValidAppearance() = runBlocking {
        val runtime = RecordingConfigurationRuntime(fontSizeSp = 8)
        val service = WidgetConfigurationApplicationService(runtime)

        val loaded = service.load(7)

        assertEquals(WidgetConfiguration.defaults(7, 8), loaded)
        assertEquals(1, runtime.fontReads)
        assertTrue(runtime.saved.isEmpty())
        assertTrue(runtime.updateCalls.isEmpty())
    }

    @Test
    fun missingConfigurationFallsBackToThirteenForInvalidAppearance() = runBlocking {
        val runtime = RecordingConfigurationRuntime(fontSizeSp = 99)

        val loaded = WidgetConfigurationApplicationService(runtime).load(9)

        assertEquals(LocalTime.of(8, 0), loaded.startTime)
        assertEquals(LocalTime.of(22, 0), loaded.endTime)
        assertEquals(100, loaded.opacityPercent)
        assertEquals(13, loaded.fontSizeSp)
        assertEquals(WidgetThemeMode.SYSTEM, loaded.theme)
        assertTrue(runtime.saved.isEmpty())
    }

    @Test
    fun savePersistsOnceBeforeRequestingOnlyTargetUpdate() = runBlocking {
        val runtime = RecordingConfigurationRuntime()
        val service = WidgetConfigurationApplicationService(runtime)
        val requested = configuration(appWidgetId = 214, opacityPercent = 100, fontSizeSp = 8)

        service.save(requested)

        assertEquals(listOf(requested), runtime.saved)
        assertEquals(listOf(214), runtime.updateCalls)
        assertEquals(listOf("save:214", "update:214"), runtime.order)
    }

    @Test
    fun saveFailureDoesNotRequestUpdateAndPropagatesWithoutRewritingIt() {
        val secret = "private datastore path and event title"
        val runtime = RecordingConfigurationRuntime(
            saveFailure = IllegalStateException(secret)
        )
        val service = WidgetConfigurationApplicationService(runtime)

        val failure = assertThrows(IllegalStateException::class.java) {
            runBlocking { service.save(configuration(3)) }
        }

        assertEquals(secret, failure.message)
        assertTrue(runtime.updateCalls.isEmpty())
        assertEquals(1, runtime.saveAttempts)
    }

    @Test
    fun finiteUpdateFailureDoesNotRollBackPersistedConfiguration() = runBlocking {
        val runtime = RecordingConfigurationRuntime(
            updateFailure = IllegalStateException("launcher internals and private path")
        )
        val service = WidgetConfigurationApplicationService(runtime)
        val requested = configuration(5, opacityPercent = 37, fontSizeSp = 20)

        service.save(requested)

        assertEquals(listOf(requested), runtime.saved)
        assertEquals(listOf(5), runtime.updateCalls)
        assertFalse(service.toString().contains("launcher internals"))
    }

    @Test
    fun invalidWidgetIdIsRejectedBeforeRuntimeAccess() {
        val runtime = RecordingConfigurationRuntime()
        val service = WidgetConfigurationApplicationService(runtime)

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { service.load(0) }
        }
        assertTrue(runtime.loadCalls.isEmpty())
        assertEquals(0, runtime.fontReads)
    }
}

private class RecordingConfigurationRuntime(
    private val existing: WidgetConfiguration? = null,
    private val fontSizeSp: Int = 13,
    private val loadFailure: RuntimeException? = null,
    private val saveFailure: RuntimeException? = null,
    private val updateFailure: RuntimeException? = null
) : WidgetConfigurationRuntimePort {
    val loadCalls = mutableListOf<Int>()
    val saved = mutableListOf<WidgetConfiguration>()
    val updateCalls = mutableListOf<Int>()
    val order = mutableListOf<String>()
    var fontReads = 0
    var saveAttempts = 0

    override suspend fun loadConfiguration(appWidgetId: Int): WidgetConfiguration? {
        loadCalls += appWidgetId
        loadFailure?.let { throw it }
        return existing
    }

    override suspend fun widgetFontSizeSp(): Int {
        fontReads += 1
        return fontSizeSp
    }

    override suspend fun saveConfiguration(configuration: WidgetConfiguration) {
        saveAttempts += 1
        order += "save:${configuration.appWidgetId}"
        saveFailure?.let { throw it }
        saved += configuration
    }

    override suspend fun requestWidgetUpdate(appWidgetId: Int) {
        updateCalls += appWidgetId
        order += "update:$appWidgetId"
        updateFailure?.let { throw it }
    }
}

private fun configuration(
    appWidgetId: Int,
    times: Pair<LocalTime, LocalTime> = LocalTime.of(8, 0) to LocalTime.of(22, 0),
    opacityPercent: Int = 100,
    fontSizeSp: Int = 13,
    theme: WidgetThemeMode = WidgetThemeMode.SYSTEM
): WidgetConfiguration = WidgetConfiguration(
    appWidgetId = appWidgetId,
    startTime = times.first,
    endTime = times.second,
    opacityPercent = opacityPercent,
    fontSizeSp = fontSizeSp,
    theme = theme
)
