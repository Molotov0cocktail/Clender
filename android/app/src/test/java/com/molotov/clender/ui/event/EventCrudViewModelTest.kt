package com.molotov.clender.ui.event

import android.os.Looper
import com.molotov.clender.core.model.Event
import com.molotov.clender.core.model.eventFixture
import com.molotov.clender.domain.event.EventRepository
import com.molotov.clender.domain.event.EventService
import com.molotov.clender.domain.event.ScheduleMutation
import com.molotov.clender.domain.event.ScheduleMutationSink
import com.molotov.clender.domain.event.SyncUidGenerator
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.ArrayDeque
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class EventCrudViewModelTest {
    private val date = LocalDate.of(2026, 8, 31)

    @Test
    fun detailLoadsContentAndRetryRecoversFromRestrictedFailure() {
        val secret = "sqlite /private/path hidden-title"
        val repository = CrudRepository().apply {
            findFailure = IllegalStateException(secret)
        }
        val viewModel = viewModel(repository)

        viewModel.openDetail(7)
        assertEquals(EventCrudLoadStatus.LOADING, viewModel.state.value.loadStatus)
        idleMain()
        assertEquals(EventCrudLoadStatus.ERROR, viewModel.state.value.loadStatus)
        assertEquals(EventDetailErrorCode.LOAD_FAILED, viewModel.state.value.detailError)
        assertFalse(viewModel.state.value.toString().contains(secret))

        repository.findFailure = null
        repository.events[7] = eventFixture(id = 7, title = "可见")
        viewModel.retry()
        idleMain()
        assertEquals(EventCrudLoadStatus.CONTENT, viewModel.state.value.loadStatus)
        assertEquals(7L, viewModel.state.value.event?.id)
        assertEquals(2, repository.findIds.count { it == 7L })
    }

    @Test
    fun absentOrTombstonedDetailIsNotFoundAndInvalidIdNeverLoads() {
        val repository = CrudRepository().apply {
            events[9] = eventFixture(id = 9, deletedAt = Instant.parse("2026-08-31T00:00:00Z"))
        }
        val viewModel = viewModel(repository)

        viewModel.openDetail(8)
        idleMain()
        assertEquals(EventCrudLoadStatus.NOT_FOUND, viewModel.state.value.loadStatus)
        viewModel.openDetail(9)
        idleMain()
        assertEquals(EventCrudLoadStatus.NOT_FOUND, viewModel.state.value.loadStatus)
        viewModel.openDetail(0)
        idleMain()
        assertEquals(EventCrudLoadStatus.NOT_FOUND, viewModel.state.value.loadStatus)
        assertFalse(repository.findIds.contains(0L))
    }

    @Test
    fun newRouteAndEditModeInitializeCompleteForms() {
        val existing = eventFixture(
            id = 4,
            title = "标题",
            startTime = date.atTime(11, 15),
            description = "说明",
            estimatedDurationMinutes = 12
        )
        val repository = CrudRepository().apply { events[4] = existing }
        val viewModel = viewModel(repository)

        viewModel.startNew(date)
        assertEquals(EventCrudRoute.New(date), viewModel.state.value.route)
        assertEquals(date.atTime(9, 0), viewModel.state.value.form?.startTime)
        assertEquals("0", viewModel.state.value.form?.estimatedDurationInput)

        viewModel.openDetail(4)
        idleMain()
        viewModel.startEdit()
        assertEquals(EventCrudRoute.Edit(4), viewModel.state.value.route)
        assertEquals(EventFormState.fromEvent(existing), viewModel.state.value.form)
    }

    @Test
    fun routeSwitchCancelsOldLoadAndStaleCompletionCannotOverwriteNewDetail() {
        val repository = CrudRepository()
        val first = CompletableDeferred<Event?>()
        val second = CompletableDeferred<Event?>()
        repository.findResults += first
        repository.findResults += second
        val viewModel = viewModel(repository)

        viewModel.openDetail(1)
        idleMain()
        viewModel.openDetail(2)
        idleMain()
        first.complete(eventFixture(id = 1, title = "stale"))
        second.complete(eventFixture(id = 2, title = "current"))
        idleMain()

        assertEquals(EventCrudRoute.Detail(2), viewModel.state.value.route)
        assertEquals(2L, viewModel.state.value.event?.id)
        assertFalse(viewModel.state.value.toString().contains("stale"))
    }

    @Test
    fun addSucceedsOnceAndEmitsSingleReplaceEffect() {
        val repository = CrudRepository()
        val mutations = mutableListOf<ScheduleMutation>()
        val viewModel = viewModel(repository, mutations)
        viewModel.startNew(date)
        viewModel.updateForm(requireNotNull(viewModel.state.value.form).copy(title = "新增"))

        viewModel.save()
        viewModel.save()
        idleMain()

        assertEquals(1, repository.insertCount)
        assertEquals(1, mutations.size)
        assertEquals(EventCrudOperation.IDLE, viewModel.state.value.operation)
        assertEquals(EventCrudEffect.ReplaceWithDetail(101), nextEffect(viewModel))
        assertNoEffect(viewModel)
    }

    @Test
    fun updateSucceedsOnceAndNoOpReturnsToDetailWithoutCallingService() {
        val existing = eventFixture(id = 5, title = "旧")
        val repository = CrudRepository().apply { events[5] = existing }
        val mutations = mutableListOf<ScheduleMutation>()
        val viewModel = viewModel(repository, mutations)
        viewModel.openDetail(5)
        idleMain()
        viewModel.startEdit()

        viewModel.save()
        idleMain()
        assertEquals(0, repository.updateCount)
        assertEquals(EventCrudEffect.ShowDetail(5), nextEffect(viewModel))

        viewModel.startEdit()
        viewModel.updateForm(requireNotNull(viewModel.state.value.form).copy(title = "新"))
        viewModel.save()
        viewModel.save()
        idleMain()
        assertEquals(1, repository.updateCount)
        assertEquals(1, mutations.size)
        assertEquals("新", viewModel.state.value.event?.title)
        assertEquals(EventCrudEffect.ShowDetail(5), nextEffect(viewModel))
        assertNoEffect(viewModel)
    }

    @Test
    fun validationAndPersistenceFailuresKeepCurrentFormWithoutMutationOrNavigation() {
        val repository = CrudRepository().apply { insertFailure = IllegalStateException("secret") }
        val mutations = mutableListOf<ScheduleMutation>()
        val viewModel = viewModel(repository, mutations)
        viewModel.startNew(date)
        val invalid = requireNotNull(viewModel.state.value.form).copy(title = " ")
        viewModel.updateForm(invalid)
        viewModel.save()
        idleMain()
        assertEquals(EventSaveErrorCode.VALIDATION_FAILED, viewModel.state.value.saveError)
        assertEquals(0, repository.insertCount)

        val valid = invalid.copy(title = "保留正文")
        viewModel.updateForm(valid)
        viewModel.save()
        idleMain()
        assertEquals(EventSaveErrorCode.SAVE_FAILED, viewModel.state.value.saveError)
        assertEquals(valid, viewModel.state.value.form)
        assertTrue(mutations.isEmpty())
        assertNoEffect(viewModel)
        assertFalse(viewModel.state.value.toString().contains("secret"))
    }

    @Test
    fun updateOfEventRemovedAfterLoadingMapsToNotFoundAndKeepsEditedForm() {
        val existing = eventFixture(id = 6, title = "旧")
        val repository = CrudRepository().apply { events[6] = existing }
        val viewModel = viewModel(repository)
        viewModel.openDetail(6)
        idleMain()
        viewModel.startEdit()
        val edited = requireNotNull(viewModel.state.value.form).copy(title = "仍保留")
        viewModel.updateForm(edited)
        repository.events.remove(6)

        viewModel.save()
        idleMain()

        assertEquals(EventSaveErrorCode.NOT_FOUND, viewModel.state.value.saveError)
        assertEquals(edited, viewModel.state.value.form)
        assertEquals(0, repository.updateCount)
        assertNoEffect(viewModel)
    }

    @Test
    fun deleteFalseMapsNotFoundAndFailureUsesRestrictedRecoverableCode() {
        val repository = CrudRepository().apply { events[22] = eventFixture(id = 22) }
        val viewModel = viewModel(repository)
        viewModel.openDetail(22)
        idleMain()

        repository.events.remove(22)
        viewModel.delete()
        idleMain()
        assertEquals(EventDeleteErrorCode.NOT_FOUND, viewModel.state.value.deleteError)
        assertNoEffect(viewModel)

        repository.events[22] = eventFixture(id = 22)
        repository.updateFailure = IllegalStateException("private SQL and title")
        viewModel.retry()
        idleMain()
        viewModel.delete()
        viewModel.delete()
        idleMain()
        assertEquals(EventDeleteErrorCode.DELETE_FAILED, viewModel.state.value.deleteError)
        assertEquals(1, repository.updateCount)
        assertNoEffect(viewModel)
        assertFalse(viewModel.state.value.toString().contains("private SQL"))
    }

    @Test
    fun successfulDeleteWritesOnceAndEmitsOnePopEffect() {
        val repository = CrudRepository().apply { events[3] = eventFixture(id = 3) }
        val mutations = mutableListOf<ScheduleMutation>()
        val viewModel = viewModel(repository, mutations)
        viewModel.openDetail(3)
        idleMain()

        viewModel.delete()
        viewModel.delete()
        idleMain()

        assertEquals(1, repository.updateCount)
        assertEquals(1, mutations.size)
        assertEquals(EventCrudEffect.PopRoute, nextEffect(viewModel))
        assertNoEffect(viewModel)
    }

    @Test
    fun clearingRouteCancelsPendingLoadAndNeverWrites() {
        val repository = CrudRepository()
        repository.findResults += CompletableDeferred<Event?>()
        val viewModel = viewModel(repository)
        viewModel.openDetail(1)
        idleMain()

        viewModel.clearRoute()
        idleMain()

        assertNull(viewModel.state.value.route)
        assertEquals(0, repository.insertCount + repository.updateCount)
        assertNoEffect(viewModel)
    }

    @Test
    fun productionConstructorUsesOnlyRepositoryAbstractionAndEventService() {
        val dependencyTypes = EventCrudViewModel::class.java.constructors
            .flatMap { it.parameterTypes.asList() }

        assertTrue(dependencyTypes.contains(EventRepository::class.java))
        assertTrue(dependencyTypes.contains(EventService::class.java))
        assertFalse(dependencyTypes.any { it.name.contains("RoomEventRepository") })
        assertFalse(dependencyTypes.any { it.simpleName.endsWith("Dao") })
    }

    private fun viewModel(
        repository: CrudRepository,
        mutations: MutableList<ScheduleMutation> = mutableListOf()
    ): EventCrudViewModel = EventCrudViewModel(
        repository = repository,
        eventService = EventService(
            repository = repository,
            clock = Clock.fixed(Instant.parse("2026-08-31T12:00:00Z"), ZoneOffset.UTC),
            uidGenerator = SyncUidGenerator { "abcdef0123456789abcdef0123456789" },
            mutationSink = ScheduleMutationSink { mutations += it }
        )
    )

    private fun nextEffect(viewModel: EventCrudViewModel): EventCrudEffect = runBlocking {
        withTimeout(1_000) { viewModel.effects.first() }
    }

    private fun assertNoEffect(viewModel: EventCrudViewModel) {
        val result = runCatching {
            runBlocking { withTimeout(25) { viewModel.effects.first() } }
        }
        assertTrue("Unexpected navigation effect", result.isFailure)
    }

    private fun idleMain() {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }
}

private class CrudRepository : EventRepository {
    val events = mutableMapOf<Long, Event>()
    val findIds = mutableListOf<Long>()
    val findResults = ArrayDeque<CompletableDeferred<Event?>>()
    var findFailure: RuntimeException? = null
    var insertFailure: RuntimeException? = null
    var updateFailure: RuntimeException? = null
    var insertCount = 0
    var updateCount = 0

    override suspend fun findById(id: Long): Event? {
        findIds += id
        findFailure?.let { throw it }
        return if (findResults.isEmpty()) events[id] else findResults.removeFirst().await()
    }

    override suspend fun insert(event: Event): Event {
        insertCount += 1
        insertFailure?.let { throw it }
        val stored = event.copy(id = 101)
        events[stored.id] = stored
        return stored
    }

    override suspend fun update(event: Event): Event {
        updateCount += 1
        updateFailure?.let { throw it }
        events[event.id] = event
        return event
    }

    override fun observeRange(start: LocalDateTime, end: LocalDateTime): Flow<List<Event>> =
        emptyFlow()
}
