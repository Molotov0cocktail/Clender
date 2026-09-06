package com.molotov.clender.ui.calendar

import android.os.Looper
import com.molotov.clender.core.model.Event
import com.molotov.clender.core.model.EventType
import com.molotov.clender.core.model.eventFixture
import com.molotov.clender.domain.event.EventRepository
import com.molotov.clender.ui.state.CalendarMode
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class CalendarViewModelTest {
    @Test
    fun startsLoadingAndSubscribesToTheCompleteMonthGrid() {
        val repository = RecordingEventRepository()
        val viewModel = viewModel(repository)

        assertEquals(CalendarLoadStatus.LOADING, viewModel.state.value.loadStatus)
        idleMain()

        assertEquals(
            LocalDateTime.of(2026, 7, 27, 0, 0) to
                LocalDateTime.of(2026, 9, 7, 0, 0),
            repository.requests.single()
        )
        assertEquals(CalendarMode.MONTH, viewModel.state.value.mode)
        assertEquals(LocalDate.of(2026, 8, 15), viewModel.state.value.selectedDate)
    }

    @Test
    fun emptyEmissionProducesEmptyStateWithoutAnyWrite() {
        val repository = RecordingEventRepository()
        val viewModel = viewModel(repository)
        idleMain()

        repository.streams.single().tryEmit(emptyList())
        idleMain()

        assertEquals(CalendarLoadStatus.EMPTY, viewModel.state.value.loadStatus)
        assertTrue(viewModel.state.value.events.isEmpty())
        assertEquals(0, repository.writeCount)
    }

    @Test
    fun changingDateCancelsOldFlowAndLateEmissionCannotOverwriteNewViewport() {
        val repository = RecordingEventRepository()
        val viewModel = viewModel(repository)
        idleMain()
        val oldStream = repository.streams.single()

        viewModel.selectDate(LocalDate.of(2026, 9, 10))
        idleMain()
        val newStream = repository.streams.last()
        val stale = eventFixture(id = 1, title = "stale")
        val current = eventFixture(
            id = 2,
            title = "current",
            startTime = LocalDateTime.of(2026, 9, 10, 9, 0)
        )
        oldStream.tryEmit(listOf(stale))
        newStream.tryEmit(listOf(current))
        idleMain()

        assertTrue(repository.cancelledRequestIndexes.contains(0))
        assertEquals(listOf(current), viewModel.state.value.events)
        assertEquals(LocalDate.of(2026, 9, 10), viewModel.state.value.selectedDate)
        assertFalse(viewModel.state.value.toString().contains("stale"))
    }

    @Test
    fun changingModeCancelsOldFlowAndUsesOneSevenOrFortyTwoDayRange() {
        val repository = RecordingEventRepository()
        val viewModel = viewModel(repository)
        idleMain()

        viewModel.changeMode(CalendarMode.WEEK)
        idleMain()
        assertEquals(
            LocalDateTime.of(2026, 8, 10, 0, 0) to
                LocalDateTime.of(2026, 8, 17, 0, 0),
            repository.requests.last()
        )

        viewModel.changeMode(CalendarMode.DAY)
        idleMain()
        assertEquals(
            LocalDateTime.of(2026, 8, 15, 0, 0) to
                LocalDateTime.of(2026, 8, 16, 0, 0),
            repository.requests.last()
        )
        assertEquals(0, repository.writeCount)
        assertEquals(listOf(0, 1), repository.cancelledRequestIndexes)
    }

    @Test
    fun repositoryFailureMapsToRestrictedCodeAndNeverLeaksExceptionText() {
        val secret = "sqlite /private/path hidden-event-payload"
        val repository = RecordingEventRepository(failure = IllegalStateException(secret))
        val viewModel = viewModel(repository)
        idleMain()

        assertEquals(CalendarLoadStatus.ERROR, viewModel.state.value.loadStatus)
        assertEquals(CalendarErrorCode.LOAD_FAILED, viewModel.state.value.errorCode)
        assertFalse(viewModel.state.value.toString().contains(secret))
        assertTrue(viewModel.state.value.events.isEmpty())
        assertEquals(0, repository.writeCount)
    }

    @Test
    fun retryCreatesAFreshSubscriptionForTheSameRange() {
        val repository = RecordingEventRepository(failure = IllegalStateException("offline"))
        val viewModel = viewModel(repository)
        idleMain()
        assertEquals(CalendarLoadStatus.ERROR, viewModel.state.value.loadStatus)

        repository.failure = null
        viewModel.retry()
        idleMain()
        assertEquals(2, repository.requests.size)
        assertEquals(repository.requests.first(), repository.requests.last())
        repository.streams.single().tryEmit(emptyList())
        idleMain()

        assertEquals(CalendarLoadStatus.EMPTY, viewModel.state.value.loadStatus)
        assertEquals(0, repository.writeCount)
    }

    @Test
    fun emissionsAreStablySortedByStartEffectiveEndAndId() {
        val repository = RecordingEventRepository()
        val viewModel = viewModel(repository)
        idleMain()
        val start = LocalDateTime.of(2026, 8, 15, 9, 0)
        val later = eventFixture(id = 9, startTime = start.plusHours(1))
        val long = eventFixture(
            id = 3,
            eventType = EventType.TIMESPAN,
            startTime = start,
            endTime = start.plusHours(2)
        )
        val shortHighId = eventFixture(
            id = 7,
            eventType = EventType.TIMESPAN,
            startTime = start,
            endTime = start.plusHours(1)
        )
        val shortLowId = shortHighId.copy(id = 2)
        val reminder = eventFixture(id = 8, startTime = start)

        repository.streams.single().tryEmit(
            listOf(later, long, shortHighId, reminder, shortLowId)
        )
        idleMain()

        assertEquals(
            listOf(8L, 2L, 7L, 3L, 9L),
            viewModel.state.value.events.map(Event::id)
        )
    }

    @Test
    fun crossDayEventReturnedByRepositoryIsPreservedForSelectedDateList() {
        val repository = RecordingEventRepository()
        val viewModel = viewModel(repository)
        idleMain()
        val crossDay = eventFixture(
            id = 44,
            eventType = EventType.TIMESPAN,
            startTime = LocalDateTime.of(2026, 8, 14, 23, 0),
            endTime = LocalDateTime.of(2026, 8, 15, 1, 0)
        )

        repository.streams.single().tryEmit(listOf(crossDay))
        idleMain()

        assertEquals(listOf(crossDay), viewModel.state.value.events)
        assertEquals(0, repository.writeCount)
    }

    @Test
    fun productionConstructorDependsOnRepositoryAbstractionNotRoomOrDao() {
        val dependencyTypes = CalendarViewModel::class.java.constructors
            .flatMap { it.parameterTypes.asList() }

        assertTrue(dependencyTypes.contains(EventRepository::class.java))
        assertFalse(dependencyTypes.any { it.name.contains("RoomEventRepository") })
        assertFalse(dependencyTypes.any { it.simpleName.endsWith("Dao") })
    }

    private fun viewModel(repository: RecordingEventRepository) = CalendarViewModel(
        repository = repository,
        initialSelectedDate = LocalDate.of(2026, 8, 15),
        initialMode = CalendarMode.MONTH,
        locale = Locale.SIMPLIFIED_CHINESE
    )

    private fun idleMain() {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }
}

private class RecordingEventRepository(var failure: RuntimeException? = null) : EventRepository {
    val requests = mutableListOf<Pair<LocalDateTime, LocalDateTime>>()
    val streams = mutableListOf<MutableSharedFlow<List<Event>>>()
    val cancelledRequestIndexes = mutableListOf<Int>()
    var writeCount: Int = 0
        private set

    override suspend fun findById(id: Long): Event? = null

    override suspend fun insert(event: Event): Event {
        writeCount += 1
        return event
    }

    override suspend fun update(event: Event): Event {
        writeCount += 1
        return event
    }

    override fun observeRange(start: LocalDateTime, end: LocalDateTime): Flow<List<Event>> {
        requests += start to end
        failure?.let { error -> return flow { throw error } }
        val requestIndex = requests.lastIndex
        val stream = MutableSharedFlow<List<Event>>(replay = 1)
        streams += stream
        return flow {
            try {
                stream.collect { emit(it) }
            } finally {
                cancelledRequestIndexes += requestIndex
            }
        }
    }
}
