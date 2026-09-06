package com.molotov.clender.app.widget

import com.molotov.clender.core.model.Event
import com.molotov.clender.core.model.eventFixture
import com.molotov.clender.domain.event.EventRepository
import com.molotov.clender.domain.widget.WidgetConfiguration
import com.molotov.clender.domain.widget.WidgetPresentation
import com.molotov.clender.domain.widget.WidgetPresentationPolicy
import com.molotov.clender.domain.widget.WidgetThemeMode
import java.time.Clock
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetUpdateCoordinatorTest {
    private val now = LocalDateTime.of(2026, 8, 7, 12, 0)
    private val clock = Clock.fixed(now.toInstant(ZoneOffset.UTC), ZoneOffset.UTC)
    private val zoneId = ZoneOffset.UTC

    @Test
    fun updateFiltersInvalidIdsDeduplicatesAndUsesStableAscendingOrder() = runBlocking {
        val configurations = RecordingWidgetConfigurationStore(config(1), config(4), config(9))
        val repository = RecordingEventRepository(flowOf(emptyList()))
        val sink = RecordingWidgetRenderSink()
        val coordinator = coordinator(repository, sink, configurations = configurations)

        coordinator.update(listOf(9, 0, 4, 9, -2, 1), WidgetPresentationPolicy.SizeClass.SMALL)

        assertEquals(listOf(1, 4, 9), sink.results.map { it.first })
        assertEquals(listOf(1, 4, 9), configurations.getCalls)
        assertEquals(3, repository.ranges.size)
    }

    @Test
    fun missingConfigurationUsesAppearanceDefaultsWritesOnceAndRereads() = runBlocking {
        val configurations = RecordingWidgetConfigurationStore()
        val appearance = RecordingWidgetFontSizeProvider(17)
        val sink = RecordingWidgetRenderSink()
        val coordinator = coordinator(
            repository = RecordingEventRepository(flowOf(emptyList())),
            sink = sink,
            configurations = configurations,
            appearance = appearance
        )

        coordinator.update(listOf(7), WidgetPresentationPolicy.SizeClass.MEDIUM)
        coordinator.update(listOf(7), WidgetPresentationPolicy.SizeClass.MEDIUM)

        assertEquals(3, configurations.getCalls.count { it == 7 })
        assertEquals(1, configurations.upsertCalls.size)
        assertEquals(1, appearance.reads)
        assertEquals(17, configurations.upsertCalls.single().fontSizeSp)
        assertEquals(
            WidgetUpdateResult.Ready::class.java,
            sink.results.first().second::class.java
        )
    }

    @Test
    fun missingConfigurationFallsBackToP1DefaultForInvalidAppearanceFontSize() = runBlocking {
        val configurations = RecordingWidgetConfigurationStore()
        val coordinator = coordinator(
            repository = RecordingEventRepository(flowOf(emptyList())),
            sink = RecordingWidgetRenderSink(),
            configurations = configurations,
            appearance = RecordingWidgetFontSizeProvider(99)
        )

        coordinator.update(listOf(3), WidgetPresentationPolicy.SizeClass.SMALL)

        assertEquals(13, configurations.upsertCalls.single().fontSizeSp)
    }

    @Test
    fun eachInstanceUsesItsOwnConfigurationWindowAndFirstRepositoryEmission() = runBlocking {
        val configurations = RecordingWidgetConfigurationStore(
            config(1, start = LocalTime.of(12, 0), end = LocalTime.of(14, 0)),
            config(2, start = LocalTime.of(14, 0), end = LocalTime.of(16, 0))
        )
        val firstEvent = eventFixture(id = 11, startTime = LocalDateTime.of(2026, 8, 7, 13, 0))
        val lateEvent = eventFixture(id = 12, startTime = LocalDateTime.of(2026, 8, 7, 15, 0))
        val repository = RecordingEventRepository { start, _ ->
            if (start.hour == 12) {
                flowOf(listOf(firstEvent), listOf(lateEvent))
            } else {
                flowOf(listOf(lateEvent))
            }
        }
        val sink = RecordingWidgetRenderSink()
        val coordinator = coordinator(repository, sink, configurations = configurations)

        coordinator.update(listOf(2, 1), WidgetPresentationPolicy.SizeClass.LARGE)

        assertEquals(
            listOf(
                LocalDateTime.of(2026, 8, 7, 12, 0) to LocalDateTime.of(2026, 8, 7, 14, 0),
                LocalDateTime.of(2026, 8, 7, 14, 0) to LocalDateTime.of(2026, 8, 7, 16, 0)
            ),
            repository.ranges
        )
        assertEquals(
            listOf(11L, 12L),
            sink.readyResults().flatMap { ready -> ready.presentation.visibleItems.map { it.id } }
        )
    }

    @Test
    fun repositoryFirstEmissionIsConvertedThroughP1Presentation() = runBlocking {
        val event = eventFixture(
            id = 21,
            title = "第一 emission 🌏\n纯文本",
            startTime = LocalDateTime.of(2026, 8, 7, 13, 0),
            description = "must not be rendered by coordinator"
        )
        val late = eventFixture(id = 22, startTime = LocalDateTime.of(2026, 8, 7, 14, 0))
        val sink = RecordingWidgetRenderSink()
        val coordinator = coordinator(
            repository = RecordingEventRepository(flowOf(listOf(event), listOf(late))),
            sink = sink
        )

        coordinator.update(listOf(1), WidgetPresentationPolicy.SizeClass.SMALL)

        val ready = sink.readyResults().single()
        assertEquals(listOf(21L), ready.presentation.visibleItems.map { it.id })
        assertEquals("第一 emission 🌏\n纯文本", ready.presentation.visibleItems.single().title)
        assertEquals(0, ready.presentation.remainingCount)
    }

    @Test
    fun p1StateBuilderEnforcesHalfOpenConfigurationWindow() = runBlocking {
        val configurations = RecordingWidgetConfigurationStore(
            config(1, start = LocalTime.of(12, 0), end = LocalTime.of(14, 0))
        )
        val atStart = eventFixture(id = 23, startTime = LocalDateTime.of(2026, 8, 7, 12, 0))
        val beforeEnd = eventFixture(id = 24, startTime = LocalDateTime.of(2026, 8, 7, 13, 59))
        val atEnd = eventFixture(id = 25, startTime = LocalDateTime.of(2026, 8, 7, 14, 0))
        val sink = RecordingWidgetRenderSink()
        val coordinator = coordinator(
            repository = RecordingEventRepository(flowOf(listOf(atEnd, beforeEnd, atStart))),
            sink = sink,
            configurations = configurations
        )

        coordinator.update(listOf(1), WidgetPresentationPolicy.SizeClass.LARGE)

        assertEquals(
            listOf(23L, 24L),
            sink.readyResults().single().presentation.visibleItems.map { it.id }
        )
    }

    @Test
    fun configurationFailureProducesFiniteUnavailableAndOtherInstancesContinue() = runBlocking {
        val configurations = RecordingWidgetConfigurationStore(
            config(2),
            failGetIds = setOf(1)
        )
        val sink = RecordingWidgetRenderSink()
        val coordinator = coordinator(
            repository = RecordingEventRepository(flowOf(emptyList())),
            sink = sink,
            configurations = configurations
        )

        coordinator.update(listOf(1, 2), WidgetPresentationPolicy.SizeClass.SMALL)

        assertEquals(WidgetUpdateError.CONFIGURATION, sink.unavailableResults().single().reason)
        assertEquals(
            listOf(2),
            sink.results.mapNotNull { (id, result) ->
                id.takeIf { result is WidgetUpdateResult.Ready }
            }
        )
    }

    @Test
    fun repositoryFailureProducesFiniteUnavailableWithoutExceptionDetails() = runBlocking {
        val sink = RecordingWidgetRenderSink()
        val coordinator = coordinator(
            repository = RecordingEventRepository { _, _ ->
                flow { throw IllegalStateException("secret repository details") }
            },
            sink = sink
        )

        coordinator.update(listOf(1), WidgetPresentationPolicy.SizeClass.SMALL)

        val unavailable = sink.unavailableResults().single()
        assertEquals(WidgetUpdateError.REPOSITORY, unavailable.reason)
        assertFalse(unavailable.toString().contains("secret repository details"))
    }

    @Test
    fun neverEmittingRepositoryIsBoundedByConfiguredEightSecondMaximum() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val sink = RecordingWidgetRenderSink()
        val coordinator = coordinator(
            repository = RecordingEventRepository { _, _ ->
                flow {
                    entered.complete(Unit)
                    awaitCancellation()
                }
            },
            sink = sink,
            queryTimeoutMillis = 25
        )

        val startedAt = System.nanoTime()
        coordinator.update(listOf(1), WidgetPresentationPolicy.SizeClass.SMALL)
        val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000

        assertTrue(entered.isCompleted)
        assertTrue(elapsedMillis < 8_000)
        assertEquals(WidgetUpdateError.TIMEOUT, sink.unavailableResults().single().reason)
    }

    @Test
    fun cancellationDoesNotBecomeAnUnavailableError() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val sink = RecordingWidgetRenderSink()
        val coordinator = coordinator(
            repository = RecordingEventRepository { _, _ ->
                flow {
                    entered.complete(Unit)
                    awaitCancellation()
                }
            },
            sink = sink
        )

        val update = launch(start = CoroutineStart.UNDISPATCHED) {
            coordinator.update(listOf(1), WidgetPresentationPolicy.SizeClass.SMALL)
        }
        entered.await()
        update.cancelAndJoin()

        assertTrue(sink.results.isEmpty())
    }

    @Test
    fun oneInstanceFailureDoesNotPreventAnotherInstanceFromRendering() = runBlocking {
        val configurations = RecordingWidgetConfigurationStore(
            config(1, start = LocalTime.of(8, 0), end = LocalTime.of(10, 0)),
            config(2, start = LocalTime.of(14, 0), end = LocalTime.of(16, 0))
        )
        val healthyEvent = eventFixture(id = 32, startTime = LocalDateTime.of(2026, 8, 7, 15, 0))
        val repository = RecordingEventRepository { start, _ ->
            if (start.hour == 8) {
                flow { throw IllegalStateException("one instance secret") }
            } else {
                flowOf(listOf(healthyEvent))
            }
        }
        val sink = RecordingWidgetRenderSink()
        val coordinator = coordinator(repository, sink, configurations = configurations)

        coordinator.update(listOf(1, 2), WidgetPresentationPolicy.SizeClass.MEDIUM)

        assertEquals(listOf(1), sink.unavailableResults().map { it.widgetId })
        assertEquals(
            listOf(32L),
            sink.readyResults().single().presentation.visibleItems.map {
                it.id
            }
        )
    }

    @Test
    fun renderFailureForOneInstanceDoesNotPreventLaterInstance() = runBlocking {
        val sink = RecordingWidgetRenderSink(failIds = setOf(1))
        val coordinator = coordinator(
            repository = RecordingEventRepository(flowOf(emptyList())),
            sink = sink,
            configurations = RecordingWidgetConfigurationStore(config(1), config(2))
        )

        coordinator.update(listOf(1, 2), WidgetPresentationPolicy.SizeClass.SMALL)

        assertEquals(listOf(2), sink.results.map { it.first })
    }

    @Test
    fun staleSuccessCannotOverwriteNewerGeneration() = runBlocking {
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<List<Event>>()
        val secondStarted = CompletableDeferred<Unit>()
        val configurations = RecordingWidgetConfigurationStore(config(1))
        var calls = 0
        val repository = RecordingEventRepository { _, _ ->
            calls += 1
            if (calls == 1) {
                flow {
                    firstStarted.complete(Unit)
                    emit(releaseFirst.await())
                }
            } else {
                flow {
                    secondStarted.complete(Unit)
                    emit(listOf(eventFixture(id = 42, startTime = now.plusHours(2))))
                }
            }
        }
        val sink = RecordingWidgetRenderSink()
        val coordinator = coordinator(repository, sink, configurations = configurations)
        val old = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.update(listOf(1), WidgetPresentationPolicy.SizeClass.SMALL)
        }
        firstStarted.await()
        val current = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.update(listOf(1), WidgetPresentationPolicy.SizeClass.SMALL)
        }
        secondStarted.await()
        current.await()
        releaseFirst.complete(listOf(eventFixture(id = 41, startTime = now.plusHours(1))))
        old.await()

        assertEquals(
            listOf(42L),
            sink.readyResults().single().presentation.visibleItems.map {
                it.id
            }
        )
    }

    @Test
    fun staleErrorCannotOverwriteNewerSuccess() = runBlocking {
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        var calls = 0
        val repository = RecordingEventRepository { _, _ ->
            calls += 1
            if (calls == 1) {
                flow {
                    firstStarted.complete(Unit)
                    releaseFirst.await()
                    error("stale secret")
                }
            } else {
                flowOf(listOf(eventFixture(id = 52, startTime = now.plusHours(2))))
            }
        }
        val sink = RecordingWidgetRenderSink()
        val coordinator = coordinator(repository, sink)
        val old = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.update(listOf(1), WidgetPresentationPolicy.SizeClass.SMALL)
        }
        firstStarted.await()
        coordinator.update(listOf(1), WidgetPresentationPolicy.SizeClass.SMALL)
        releaseFirst.complete(Unit)
        old.await()

        assertEquals(
            listOf(52L),
            sink.readyResults().single().presentation.visibleItems.map {
                it.id
            }
        )
        assertTrue(sink.unavailableResults().isEmpty())
    }

    @Test
    fun deleteInvalidatesInFlightUpdateBeforeDeletingAndCannotRecreateConfiguration() =
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val configurations = RecordingWidgetConfigurationStore(config(1))
            val repository = RecordingEventRepository { _, _ ->
                flow {
                    entered.complete(Unit)
                    release.await()
                    emit(listOf(eventFixture(id = 61, startTime = now.plusHours(1))))
                }
            }
            val sink = RecordingWidgetRenderSink()
            val coordinator = coordinator(repository, sink, configurations = configurations)
            val update = async(start = CoroutineStart.UNDISPATCHED) {
                coordinator.update(listOf(1), WidgetPresentationPolicy.SizeClass.SMALL)
            }
            entered.await()

            coordinator.delete(listOf(1, 1, 0, -3))
            release.complete(Unit)
            update.await()

            assertEquals(listOf(1), configurations.deleteCalls)
            assertTrue(sink.results.isEmpty())
            assertTrue(configurations.upsertCalls.isEmpty())
        }

    @Test
    fun invalidOrEmptyRequestsDoNotTouchConfigurationRepositoryOrEvents() = runBlocking {
        val configurations = RecordingWidgetConfigurationStore()
        val repository = RecordingEventRepository(flowOf(emptyList()))
        val sink = RecordingWidgetRenderSink()
        val coordinator = coordinator(repository, sink, configurations = configurations)

        coordinator.update(listOf(0, -1, 0), WidgetPresentationPolicy.SizeClass.SMALL)
        coordinator.delete(listOf(0, -1))

        assertTrue(configurations.getCalls.isEmpty())
        assertTrue(configurations.deleteCalls.isEmpty())
        assertTrue(repository.ranges.isEmpty())
        assertTrue(sink.results.isEmpty())
    }

    @Test
    fun coordinatorNeverCallsEventWritesAndDoesNotExposeConfigurationOrEventMetadata() =
        runBlocking {
            val event = eventFixture(
                id = 71,
                title = "可见标题",
                startTime = now.plusHours(1),
                description = "private description",
                syncUid = "abcdefabcdefabcdefabcdefabcdefab"
            )
            val repository = RecordingEventRepository(flowOf(listOf(event)))
            val configurations = RecordingWidgetConfigurationStore(config(1))
            val sink = RecordingWidgetRenderSink()
            val coordinator = coordinator(repository, sink, configurations = configurations)

            coordinator.update(listOf(1), WidgetPresentationPolicy.SizeClass.SMALL)

            assertEquals(0, repository.insertCalls)
            assertEquals(0, repository.updateCalls)
            val rendered = sink.readyResults().single().presentation.visibleItems.single()
            assertEquals("可见标题", rendered.title)
            assertFalse(rendered.toString().contains("private description"))
            assertFalse(rendered.toString().contains(event.syncUid))
        }

    private fun coordinator(
        repository: EventRepository,
        sink: WidgetRenderSink,
        configurations: RecordingWidgetConfigurationStore =
            RecordingWidgetConfigurationStore(config(1)),
        appearance: RecordingWidgetFontSizeProvider = RecordingWidgetFontSizeProvider(13),
        queryTimeoutMillis: Long = 8_000
    ): WidgetUpdateCoordinator = WidgetUpdateCoordinator(
        configurations = configurations,
        appearance = appearance,
        events = repository,
        renderSink = sink,
        timePolicy = WidgetUpdateTimePolicy(
            clock = clock,
            zoneId = zoneId,
            queryTimeoutMillis = queryTimeoutMillis
        )
    )

    private fun config(
        id: Int,
        start: LocalTime = LocalTime.of(8, 0),
        end: LocalTime = LocalTime.of(22, 0)
    ): WidgetConfiguration = WidgetConfiguration(
        appWidgetId = id,
        startTime = start,
        endTime = end,
        opacityPercent = 100,
        fontSizeSp = 13,
        theme = WidgetThemeMode.SYSTEM
    )
}

private class RecordingWidgetConfigurationStore(
    vararg initial: WidgetConfiguration,
    private val failGetIds: Set<Int> = emptySet()
) : WidgetConfigurationStore {
    private val values = initial.associateByTo(linkedMapOf()) { it.appWidgetId }
    val getCalls = mutableListOf<Int>()
    val upsertCalls = mutableListOf<WidgetConfiguration>()
    val deleteCalls = mutableListOf<Int>()

    override suspend fun get(appWidgetId: Int): WidgetConfiguration? {
        getCalls += appWidgetId
        if (appWidgetId in failGetIds) error("private configuration failure")
        return values[appWidgetId]
    }

    override suspend fun upsert(configuration: WidgetConfiguration) {
        upsertCalls += configuration
        values[configuration.appWidgetId] = configuration
    }

    override suspend fun delete(appWidgetId: Int) {
        deleteCalls += appWidgetId
        values.remove(appWidgetId)
    }
}

private class RecordingWidgetFontSizeProvider(private val sizeSp: Int) : WidgetFontSizeProvider {
    var reads: Int = 0

    override suspend fun widgetFontSizeSp(): Int {
        reads += 1
        return sizeSp
    }
}

private class RecordingEventRepository(
    private val handler: (LocalDateTime, LocalDateTime) -> Flow<List<Event>>
) : EventRepository {
    constructor(flow: Flow<List<Event>>) : this({ _, _ -> flow })

    val ranges = mutableListOf<Pair<LocalDateTime, LocalDateTime>>()
    var insertCalls: Int = 0
    var updateCalls: Int = 0

    override suspend fun findById(id: Long): Event? = error("findById is not a Widget operation")

    override suspend fun insert(event: Event): Event {
        insertCalls += 1
        error("Widget must not insert events")
    }

    override suspend fun update(event: Event): Event {
        updateCalls += 1
        error("Widget must not update events")
    }

    override fun observeRange(start: LocalDateTime, end: LocalDateTime): Flow<List<Event>> {
        ranges += start to end
        return handler(start, end)
    }
}

private class RecordingWidgetRenderSink(private val failIds: Set<Int> = emptySet()) :
    WidgetRenderSink {
    val results = mutableListOf<Pair<Int, WidgetUpdateResult>>()

    override suspend fun render(appWidgetId: Int, result: WidgetUpdateResult) {
        if (appWidgetId in failIds) error("private render failure")
        results += appWidgetId to result
    }

    fun readyResults(): List<WidgetUpdateResult.Ready> = results.mapNotNull { (_, result) ->
        result as? WidgetUpdateResult.Ready
    }

    fun unavailableResults(): List<WidgetUpdateResult.Unavailable> =
        results.mapNotNull { (_, result) ->
            result as? WidgetUpdateResult.Unavailable
        }
}
