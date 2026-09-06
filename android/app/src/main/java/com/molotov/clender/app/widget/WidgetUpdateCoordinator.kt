package com.molotov.clender.app.widget

import com.molotov.clender.domain.event.EventRepository
import com.molotov.clender.domain.widget.WidgetConfiguration
import com.molotov.clender.domain.widget.WidgetPresentation
import com.molotov.clender.domain.widget.WidgetPresentationPolicy
import com.molotov.clender.domain.widget.WidgetStateBuilder
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

interface WidgetConfigurationStore {
    suspend fun get(appWidgetId: Int): WidgetConfiguration?

    suspend fun upsert(configuration: WidgetConfiguration)

    suspend fun delete(appWidgetId: Int)
}

fun interface WidgetFontSizeProvider {
    suspend fun widgetFontSizeSp(): Int
}

fun interface WidgetRenderSink {
    suspend fun render(appWidgetId: Int, result: WidgetUpdateResult)
}

enum class WidgetUpdateError {
    CONFIGURATION,
    REPOSITORY,
    TIMEOUT
}

sealed interface WidgetUpdateResult {
    val widgetId: Int
    val date: LocalDate

    data class Ready(
        override val widgetId: Int,
        override val date: LocalDate,
        val configuration: WidgetConfiguration,
        val presentation: WidgetPresentation
    ) : WidgetUpdateResult

    data class Unavailable(
        override val widgetId: Int,
        override val date: LocalDate,
        val reason: WidgetUpdateError
    ) : WidgetUpdateResult
}

data class WidgetUpdateTimePolicy(
    val clock: Clock,
    val zoneId: ZoneId,
    val queryTimeoutMillis: Long = MAX_QUERY_TIMEOUT_MILLIS,
    val zoneIdProvider: (() -> ZoneId)? = null
) {
    constructor(
        clock: Clock,
        zoneIdProvider: () -> ZoneId,
        queryTimeoutMillis: Long = MAX_QUERY_TIMEOUT_MILLIS
    ) : this(clock, ZoneOffset.UTC, queryTimeoutMillis, zoneIdProvider)

    init {
        require(queryTimeoutMillis in 1..MAX_QUERY_TIMEOUT_MILLIS) {
            "Widget query timeout is out of range"
        }
    }
}

class WidgetUpdateCoordinator(
    private val configurations: WidgetConfigurationStore,
    private val appearance: WidgetFontSizeProvider,
    private val events: EventRepository,
    private val renderSink: WidgetRenderSink,
    private val timePolicy: WidgetUpdateTimePolicy
) {
    private val closed = AtomicBoolean(false)
    private val generations = ConcurrentHashMap<Int, AtomicLong>()
    private val configurationLocks = ConcurrentHashMap<Int, Mutex>()

    suspend fun update(appWidgetIds: Iterable<Int>, sizeClass: WidgetPresentationPolicy.SizeClass) {
        prepareUpdate(appWidgetIds, sizeClass).invoke()
    }

    fun prepareUpdate(
        appWidgetIds: Iterable<Int>,
        sizeClass: WidgetPresentationPolicy.SizeClass
    ): suspend () -> Unit {
        if (closed.get()) return {}
        val tickets = normalizedIds(appWidgetIds).associateWith(::nextGeneration)
        return if (tickets.isEmpty()) {
            {}
        } else {
            val instant = timePolicy.clock.instant()
            val zoneId = timePolicy.zoneIdProvider?.invoke() ?: timePolicy.zoneId
            val now = LocalDateTime.ofInstant(instant, zoneId)
            val operation: suspend () -> Unit = {
                tickets.forEach { (appWidgetId, generation) ->
                    updateOne(appWidgetId, generation, sizeClass, now)
                }
            }
            operation
        }
    }

    fun invalidate(appWidgetIds: Set<Int>) {
        normalizedIds(appWidgetIds).forEach { nextGeneration(it) }
    }

    suspend fun delete(appWidgetIds: Iterable<Int>) {
        val tickets = normalizedIds(appWidgetIds).associateWith(::nextGeneration)
        tickets.forEach { (appWidgetId, generation) ->
            try {
                configurationLocks.computeIfAbsent(appWidgetId) { Mutex() }.withLock {
                    if (isCurrent(appWidgetId, generation, allowClosed = true)) {
                        configurations.delete(appWidgetId)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Deletion is isolated per Widget instance.
            }
        }
    }

    fun close() {
        closed.set(true)
    }

    private suspend fun updateOne(
        appWidgetId: Int,
        generation: Long,
        sizeClass: WidgetPresentationPolicy.SizeClass,
        now: LocalDateTime
    ) {
        val date = now.toLocalDate()
        val configuration = when (val load = loadConfiguration(appWidgetId, generation)) {
            ConfigurationLoad.Stale -> return

            is ConfigurationLoad.Failed -> {
                renderIfCurrent(
                    appWidgetId,
                    generation,
                    WidgetUpdateResult.Unavailable(
                        widgetId = appWidgetId,
                        date = date,
                        reason = WidgetUpdateError.CONFIGURATION
                    )
                )
                null
            }

            is ConfigurationLoad.Loaded -> load.value
        }
        if (configuration == null || !isCurrent(appWidgetId, generation)) return

        val result = try {
            val rangeStart = date.atTime(configuration.startTime)
            val rangeEndExclusive = date.atTime(configuration.endTime)
            val eventSnapshot = withTimeout(timePolicy.queryTimeoutMillis) {
                events.observeRange(rangeStart, rangeEndExclusive).first()
            }
            val state = WidgetStateBuilder.build(eventSnapshot, configuration, date, now)
            WidgetUpdateResult.Ready(
                widgetId = appWidgetId,
                date = date,
                configuration = configuration,
                presentation = WidgetPresentationPolicy.present(state, sizeClass)
            )
        } catch (_: TimeoutCancellationException) {
            WidgetUpdateResult.Unavailable(
                widgetId = appWidgetId,
                date = date,
                reason = WidgetUpdateError.TIMEOUT
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            WidgetUpdateResult.Unavailable(
                widgetId = appWidgetId,
                date = date,
                reason = WidgetUpdateError.REPOSITORY
            )
        }
        renderIfCurrent(appWidgetId, generation, result)
    }

    private suspend fun loadConfiguration(appWidgetId: Int, generation: Long): ConfigurationLoad {
        return try {
            configurationLocks.computeIfAbsent(appWidgetId) { Mutex() }.withLock {
                if (!isCurrent(appWidgetId, generation)) return@withLock ConfigurationLoad.Stale
                val existing = configurations.get(appWidgetId)
                if (!isCurrent(appWidgetId, generation)) return@withLock ConfigurationLoad.Stale
                if (existing != null) return@withLock ConfigurationLoad.Loaded(existing)

                val defaultConfiguration = WidgetConfiguration.defaults(
                    appWidgetId = appWidgetId,
                    widgetFontSizeSp = appearance.widgetFontSizeSp()
                )
                if (!isCurrent(appWidgetId, generation)) return@withLock ConfigurationLoad.Stale
                configurations.upsert(defaultConfiguration)
                if (!isCurrent(appWidgetId, generation)) return@withLock ConfigurationLoad.Stale
                configurations.get(appWidgetId)
                    ?.let(ConfigurationLoad::Loaded)
                    ?: ConfigurationLoad.Failed
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            ConfigurationLoad.Failed
        }
    }

    private suspend fun renderIfCurrent(
        appWidgetId: Int,
        generation: Long,
        result: WidgetUpdateResult
    ) {
        if (!isCurrent(appWidgetId, generation)) return
        try {
            renderSink.render(appWidgetId, result)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Rendering is an instance boundary; another Widget must still be updated.
        }
    }

    private fun nextGeneration(appWidgetId: Int): Long =
        generations.computeIfAbsent(appWidgetId) { AtomicLong() }.incrementAndGet()

    private fun isCurrent(id: Int, generation: Long, allowClosed: Boolean = false): Boolean =
        (allowClosed || !closed.get()) && generations[id]?.get() == generation
}

private sealed interface ConfigurationLoad {
    data class Loaded(val value: WidgetConfiguration) : ConfigurationLoad

    data object Failed : ConfigurationLoad

    data object Stale : ConfigurationLoad
}

private fun normalizedIds(appWidgetIds: Iterable<Int>): List<Int> = appWidgetIds.asSequence()
    .filter { it > 0 }
    .distinct()
    .sorted()
    .toList()

private const val MAX_QUERY_TIMEOUT_MILLIS = 8_000L
