package com.molotov.clender.app.widget

import com.molotov.clender.core.model.Event
import com.molotov.clender.core.model.eventFixture
import com.molotov.clender.domain.event.EventRepository
import com.molotov.clender.domain.widget.WidgetConfiguration
import com.molotov.clender.domain.widget.WidgetPresentationPolicy
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetUpdateCoordinatorAutomaticRefreshTest {
    private val size = WidgetPresentationPolicy.SizeClass.LARGE
    private val clock = Clock.fixed(Instant.parse("2026-09-06T23:30:00Z"), ZoneOffset.UTC)

    @Test
    fun preparedUpdateInvalidatedBeforeInvocationDoesNotTouchAnyPorts() = runBlocking {
        val store = ControlledConfigurationStore()
        var configurationReads = 0
        store.beforeGet = { configurationReads += 1 }
        val fixture = fixture(store = store)

        val prepared = fixture.coordinator.prepareUpdate(listOf(1), size)
        fixture.coordinator.invalidate(setOf(1))
        prepared.invoke()

        assertEquals(0, configurationReads)
        assertTrue(store.operations.isEmpty())
        assertTrue(fixture.repository.ranges.isEmpty())
        assertTrue(fixture.results.isEmpty())
    }

    @Test
    fun prepareDoesNotTouchPortsAndInvocationExecutesThePreparedUpdate() = runBlocking {
        val store = ControlledConfigurationStore(1)
        var configurationReads = 0
        store.beforeGet = { configurationReads += 1 }
        val fixture = fixture(store = store)

        val prepared = fixture.coordinator.prepareUpdate(listOf(1, 1, 0, -1), size)

        assertEquals(0, configurationReads)
        assertTrue(store.operations.isEmpty())
        assertTrue(fixture.repository.ranges.isEmpty())
        assertTrue(fixture.results.isEmpty())

        prepared.invoke()

        assertEquals(1, configurationReads)
        assertEquals(1, fixture.repository.ranges.size)
        assertEquals(listOf(1), fixture.results.map { it.widgetId })
        assertTrue(fixture.results.single() is WidgetUpdateResult.Ready)
        assertTrue(store.operations.isEmpty())
    }

    @Test
    fun invalidationImmediatelySuppressesSuspendedQueryBeforeDeleteRuns() = runBlocking {
        val gate = QueryGate()
        val fixture = fixture(query = gate::query)
        val update = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.coordinator.update(listOf(1), size)
        }
        gate.entered.await()
        fixture.coordinator.invalidate(setOf(1))
        gate.release.complete(Unit)
        update.await()
        assertTrue(fixture.results.isEmpty())
        assertTrue(fixture.store.operations.isEmpty())
        fixture.coordinator.delete(listOf(1))
        assertEquals(listOf("delete:1"), fixture.store.operations)
        assertFalse(fixture.store.values.containsKey(1))
    }

    @Test
    fun invalidationAlsoSuppressesLateRepositoryFailure() = runBlocking {
        val gate = QueryGate(fail = true)
        val fixture = fixture(query = gate::query)
        val update = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.coordinator.update(listOf(1), size)
        }
        gate.entered.await()
        fixture.coordinator.invalidate(setOf(1))
        gate.release.complete(Unit)
        update.await()
        assertTrue(fixture.results.isEmpty())
    }

    @Test
    fun invalidationDuringMissingConfigurationReadPreventsDefaultWriteAndQuery() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val store = ControlledConfigurationStore()
        store.beforeGet = {
            entered.complete(Unit)
            release.await()
        }
        val fixture = fixture(store = store)
        val update = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.coordinator.update(listOf(1), size)
        }
        entered.await()
        fixture.coordinator.invalidate(setOf(1))
        release.complete(Unit)
        update.await()
        fixture.coordinator.delete(listOf(1))
        assertEquals(listOf("delete:1"), store.operations)
        assertTrue(fixture.repository.ranges.isEmpty())
        assertTrue(fixture.results.isEmpty())
    }

    @Test
    fun deletionWaitsForEnteredConfigurationWriteThenRemovesItWithoutLateRender() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val store = ControlledConfigurationStore()
        store.beforeUpsert = {
            entered.complete(Unit)
            release.await()
        }
        val fixture = fixture(store = store)
        val update = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.coordinator.update(listOf(1), size)
        }
        entered.await()
        fixture.coordinator.invalidate(setOf(1))
        val deletion = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.coordinator.delete(listOf(1))
        }
        assertFalse(deletion.isCompleted)
        release.complete(Unit)
        update.await()
        deletion.await()
        assertEquals(listOf("upsert:1", "delete:1"), store.operations)
        assertFalse(store.values.containsKey(1))
        assertTrue(fixture.repository.ranges.isEmpty())
        assertTrue(fixture.results.isEmpty())
    }

    @Test
    fun invalidationOfOneIdPreservesOtherBatchTicketsAndAllowsNewUpdate() = runBlocking {
        val gate = QueryGate()
        var calls = 0
        val fixture = fixture(query = { start, end ->
            if (calls++ == 0) gate.query(start, end) else flowOf(emptyList())
        })
        val old = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.coordinator.update(listOf(1, 2), size)
        }
        gate.entered.await()
        fixture.coordinator.invalidate(setOf(1, 0, -1))
        gate.release.complete(Unit)
        old.await()
        assertEquals(listOf(2), fixture.results.map { it.widgetId })
        fixture.coordinator.update(listOf(1), size)
        assertEquals(listOf(2, 1), fixture.results.map { it.widgetId })
    }

    @Test
    fun dynamicZoneIsCapturedOnceForWholeBatchAndRefreshedOnNextUpdate() = runBlocking {
        var zone: ZoneId = ZoneOffset.UTC
        var reads = 0
        val policy = WidgetUpdateTimePolicy(clock, zoneIdProvider = {
            reads += 1
            zone
        })
        val fixture = fixture(policy = policy, query = { _, _ ->
            zone = ZoneId.of("Asia/Tokyo")
            flowOf(emptyList())
        })
        fixture.coordinator.update(listOf(1, 2), size)
        assertEquals(1, reads)
        assertEquals(List(2) { LocalDate.of(2026, 9, 6) }, fixture.results.map { it.date })
        assertEquals(
            List(2) { LocalDate.of(2026, 9, 6) },
            fixture.repository.ranges.map { it.first.toLocalDate() }
        )
        fixture.coordinator.update(listOf(1), size)
        assertEquals(2, reads)
        assertEquals(LocalDate.of(2026, 9, 7), fixture.results.last().date)
        assertEquals(LocalDate.of(2026, 9, 7), fixture.repository.ranges.last().first.toLocalDate())
    }

    @Test
    fun zoneChangeDuringQueryDoesNotChangeCapturedNowOrHideFutureEvent() = runBlocking {
        var zone: ZoneId = ZoneOffset.UTC
        var reads = 0
        val midday = Clock.fixed(Instant.parse("2026-09-06T12:00:00Z"), ZoneOffset.UTC)
        val policy = WidgetUpdateTimePolicy(midday, zoneIdProvider = {
            reads += 1
            zone
        })
        val fixture = fixture(policy = policy, query = { _, _ ->
            zone = ZoneId.of("Asia/Tokyo")
            flowOf(listOf(eventFixture(id = 8, startTime = LocalDateTime.of(2026, 9, 6, 13, 0))))
        })
        fixture.coordinator.update(listOf(1), size)
        assertEquals(1, reads)
        val ready = fixture.results.single() as WidgetUpdateResult.Ready
        assertEquals(listOf(8L), ready.presentation.visibleItems.map { it.id })
    }

    @Test
    fun legacyNamedAndPositionalTimePolicyConstructorsKeepFixedZone() = runBlocking {
        val positional = WidgetUpdateTimePolicy(clock, ZoneId.of("Asia/Tokyo"), 8_000)
        val named = WidgetUpdateTimePolicy(clock = clock, zoneId = ZoneOffset.UTC)
        val east = fixture(policy = positional)
        val utc = fixture(policy = named)
        east.coordinator.update(listOf(1), size)
        utc.coordinator.update(listOf(1), size)
        assertEquals(LocalDate.of(2026, 9, 7), east.results.single().date)
        assertEquals(LocalDate.of(2026, 9, 6), utc.results.single().date)
    }

    @Test
    fun emptyAndInvalidIdsDoNotReadDynamicZoneOrTouchPorts() = runBlocking {
        var reads = 0
        val fixture = fixture(
            policy = WidgetUpdateTimePolicy(clock, zoneIdProvider = {
                reads += 1
                ZoneOffset.UTC
            })
        )
        fixture.coordinator.invalidate(emptySet())
        fixture.coordinator.invalidate(setOf(0, -1))
        fixture.coordinator.update(listOf(0, -1), size)
        assertEquals(0, reads)
        assertTrue(fixture.repository.ranges.isEmpty())
        assertTrue(fixture.store.operations.isEmpty())
        assertTrue(fixture.results.isEmpty())
    }

    @Test
    fun closeSuppressesInFlightQueryAndRejectsFurtherUpdatesButAllowsCleanup() = runBlocking {
        val gate = QueryGate()
        val fixture = fixture(query = gate::query)
        val update = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.coordinator.update(listOf(1), size)
        }
        gate.entered.await()
        fixture.coordinator.close()
        fixture.coordinator.close()
        fixture.coordinator.invalidate(setOf(1))
        gate.release.complete(Unit)
        update.await()
        fixture.coordinator.update(listOf(2), size)
        fixture.coordinator.delete(listOf(1))
        assertEquals(1, fixture.repository.ranges.size)
        assertEquals(listOf("delete:1"), fixture.store.operations)
        assertTrue(fixture.results.isEmpty())
    }

    @Test
    fun closeDuringEnteredConfigurationWriteAllowsExplicitDeletionToCleanUp() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val store = ControlledConfigurationStore()
        store.beforeUpsert = {
            entered.complete(Unit)
            release.await()
        }
        val fixture = fixture(store = store)
        val update = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.coordinator.update(listOf(1), size)
        }
        entered.await()
        fixture.coordinator.close()
        val deletion = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.coordinator.delete(listOf(1))
        }
        assertFalse(deletion.isCompleted)
        release.complete(Unit)
        update.await()
        deletion.await()
        assertFalse(store.values.containsKey(1))
        assertTrue(fixture.repository.ranges.isEmpty())
        assertTrue(fixture.results.isEmpty())
    }

    private fun fixture(
        store: ControlledConfigurationStore = ControlledConfigurationStore(1, 2),
        policy: WidgetUpdateTimePolicy = WidgetUpdateTimePolicy(clock, ZoneOffset.UTC),
        query: (LocalDateTime, LocalDateTime) -> Flow<List<Event>> = { _, _ -> flowOf(emptyList()) }
    ): CoordinatorFixture {
        val repository = ControlledQueryRepository(query)
        val results = mutableListOf<WidgetUpdateResult>()
        return CoordinatorFixture(
            WidgetUpdateCoordinator(
                store,
                WidgetFontSizeProvider { 13 },
                repository,
                WidgetRenderSink { _, result -> results += result },
                policy
            ),
            store,
            repository,
            results
        )
    }
}

private data class CoordinatorFixture(
    val coordinator: WidgetUpdateCoordinator,
    val store: ControlledConfigurationStore,
    val repository: ControlledQueryRepository,
    val results: MutableList<WidgetUpdateResult>
)

private class QueryGate(private val fail: Boolean = false) {
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()

    fun query(start: LocalDateTime, end: LocalDateTime): Flow<List<Event>> = flow {
        require(start < end)
        entered.complete(Unit)
        release.await()
        check(!fail) { "synthetic query failure" }
        emit(emptyList())
    }
}

private class ControlledConfigurationStore(vararg ids: Int) : WidgetConfigurationStore {
    val values = ids.associateWithTo(linkedMapOf()) { WidgetConfiguration.defaults(it, 13) }
    val operations = mutableListOf<String>()
    var beforeGet: suspend () -> Unit = {}
    var beforeUpsert: suspend () -> Unit = {}

    override suspend fun get(appWidgetId: Int): WidgetConfiguration? {
        beforeGet()
        return values[appWidgetId]
    }

    override suspend fun upsert(configuration: WidgetConfiguration) {
        beforeUpsert()
        values[configuration.appWidgetId] = configuration
        operations += "upsert:${configuration.appWidgetId}"
    }

    override suspend fun delete(appWidgetId: Int) {
        values.remove(appWidgetId)
        operations += "delete:$appWidgetId"
    }
}

private class ControlledQueryRepository(
    private val query: (LocalDateTime, LocalDateTime) -> Flow<List<Event>>
) : EventRepository {
    val ranges = mutableListOf<Pair<LocalDateTime, LocalDateTime>>()

    override fun observeRange(start: LocalDateTime, end: LocalDateTime): Flow<List<Event>> {
        ranges += start to end
        return query(start, end)
    }

    override suspend fun findById(id: Long): Event? = error("Unexpected detail read")

    override suspend fun insert(event: Event): Event = error("Unexpected event write")

    override suspend fun update(event: Event): Event = error("Unexpected event write")
}
