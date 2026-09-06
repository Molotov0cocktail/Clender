package com.molotov.clender.app

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.molotov.clender.app.sync.SyncRequestDecision
import com.molotov.clender.app.sync.SyncState
import com.molotov.clender.app.sync.WebDavAvailability
import com.molotov.clender.app.sync.WebDavOperationGate
import com.molotov.clender.app.sync.WebDavRunResult
import com.molotov.clender.app.sync.WebDavSyncRuntime
import com.molotov.clender.core.model.EventType
import com.molotov.clender.data.local.ClenderDatabase
import com.molotov.clender.data.local.RoomEventRepository
import com.molotov.clender.data.network.webdav.WebDavClient
import com.molotov.clender.data.network.webdav.WebDavSettings
import com.molotov.clender.domain.event.AddEventCommand
import com.molotov.clender.domain.event.EventService
import com.molotov.clender.domain.event.SyncUidGenerator
import com.molotov.clender.domain.sync.SyncService
import com.molotov.clender.domain.sync.WebDavDocumentCodec
import com.molotov.clender.domain.sync.syncEvent
import com.molotov.clender.domain.sync.syncUid
import com.molotov.clender.sync.RoomSyncRecordStore
import com.molotov.clender.sync.SyncTrigger
import java.time.Clock
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T46-C2b production integration: the real app/sync runtime driving the real
 * WebDavClient/SyncService/RoomSyncRecordStore against an HTTPS MockWebServer.
 * Covers local-mutation -> sync round trip, remote apply -> Room refresh with
 * zero mutation signal and zero upload loop, and disabled zero-work behavior.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class WebDavSyncProductionIntegrationTest {
    private lateinit var server: MockWebServer
    private lateinit var httpClient: OkHttpClient
    private lateinit var database: ClenderDatabase
    private lateinit var repository: RoomEventRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val signal = ScheduleMutationVersionSignal()
    private val gates = mutableListOf<WebDavOperationGate>()
    private var runtime: WebDavSyncRuntime? = null

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ClenderDatabase::class.java).build()
        repository = RoomEventRepository(database)
        val certificate = HeldCertificate.Builder()
            .commonName("localhost")
            .addSubjectAlternativeName("localhost")
            .build()
        val serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(certificate)
            .build()
        val clientCertificates = HandshakeCertificates.Builder()
            .addTrustedCertificate(certificate.certificate)
            .build()
        server = MockWebServer().apply {
            useHttps(serverCertificates.sslSocketFactory())
            start()
        }
        httpClient = OkHttpClient.Builder()
            .sslSocketFactory(
                clientCertificates.sslSocketFactory(),
                clientCertificates.trustManager
            )
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(java.time.Duration.ofSeconds(2))
            .readTimeout(java.time.Duration.ofSeconds(10))
            .build()
    }

    @After
    fun tearDown() {
        runtime?.close()
        gates.forEach { it.close() }
        scope.cancel()
        database.close()
        server.close()
    }

    @Test
    fun localMutationTriggersExactlyOneRoundTripWithoutRemoteLoop() = runBlocking {
        server.enqueue(MockResponse(code = 404))
        server.enqueue(MockResponse(code = 204))
        val fixture = runtimeFixture()
        val eventService = eventService()
        val before = signal.version.value

        eventService.add(
            AddEventCommand(
                eventType = EventType.REMINDER,
                title = "local-only-fixture",
                startTime = LocalDateTime.of(2026, 9, 1, 9, 0)
            )
        )

        waitFor { fixture.runtime.state.value is SyncState.Success }
        val success = fixture.runtime.state.value as SyncState.Success
        assertTrue(success.uploaded)
        assertFalse(success.localChanged)
        assertEquals(1, success.eventCount)
        assertEquals(before + 1, signal.version.value)

        val requests = listOfNotNull(server.takeRequest(1, TimeUnit.SECONDS))
        val requestsTotal = server.requestCount
        assertEquals(
            "PROPFIND must not be part of a sync run",
            0,
            requests.filter { it.method == "PROPFIND" }.size
        )
        assertEquals(2, requestsTotal)
        delay(300)
        assertEquals("Remote apply must not re-queue an upload run", 2, server.requestCount)
    }

    @Test
    fun remoteApplyRefreshesCalendarFlowWithoutMutationOrUploadLoop() = runBlocking {
        val remoteEvent = syncEvent(uid = syncUid(99), title = "remote-only-fixture")
        val document = WebDavDocumentCodec().encode(listOf(remoteEvent))
        server.enqueue(
            MockResponse(
                code = 200,
                headers = okhttp3.Headers.headersOf(
                    "Content-Type",
                    "application/json; charset=utf-8",
                    "ETag",
                    "\"remote-v1\""
                ),
                body = document.decodeToString()
            )
        )
        val fixture = runtimeFixture()
        val observations = mutableListOf<Int>()
        val observationJob = scope.launch {
            repository.observeRange(
                LocalDateTime.of(2026, 8, 3, 0, 0),
                LocalDateTime.of(2026, 8, 4, 0, 0)
            ).collect { events -> observations += events.size }
        }

        assertEquals(
            SyncRequestDecision.STARTED,
            fixture.runtime.requestSync(SyncTrigger.MANUAL)
        )
        val terminal = withTimeoutOrNull(5_000) {
            while (true) {
                val current = fixture.runtime.state.value
                if (
                    current !is SyncState.Running && current !is SyncState.Idle
                ) {
                    return@withTimeoutOrNull current
                }
                delay(5)
            }
        }
        assertTrue(
            "sync must reach a terminal state, got: $terminal",
            terminal is SyncState.Success
        )
        val success = terminal as SyncState.Success
        assertFalse(success.uploaded)
        assertTrue(success.localChanged)
        assertEquals(1, success.eventCount)
        assertEquals(
            "Remote apply must not increment the mutation signal",
            0L,
            signal.version.value
        )
        assertEquals(1, server.requestCount)

        waitFor { observations.lastOrNull() == 1 }
        delay(300)
        assertEquals("Remote apply must not trigger another network run", 1, server.requestCount)
        observationJob.cancel()
    }

    @Test
    fun disabledAvailabilityPerformsZeroNetworkZeroSecretWork() = runBlocking {
        val gate = WebDavOperationGate().also(gates::add)
        val availability = MutableStateFlow(
            WebDavAvailability(enabled = false, passwordConfigured = false)
        )
        var runSyncCalls = 0
        val disabledRuntime = WebDavSyncRuntime(
            scope = scope,
            lifecycle = registry(),
            gate = gate,
            availability = availability,
            mutationVersion = signal.version,
            runSync = {
                runSyncCalls += 1
                WebDavRunResult.Completed(uploaded = false, localChanged = false, eventCount = 0)
            }
        )
        runtime = disabledRuntime

        disabledRuntime.requestSync(SyncTrigger.MANUAL)
        disabledRuntime.requestSync(SyncTrigger.LOCAL_CHANGE)
        delay(150)

        assertEquals(com.molotov.clender.app.sync.SyncState.Disabled, disabledRuntime.state.value)
        assertEquals(0, runSyncCalls)
        assertEquals(0, server.requestCount)
        assertTrue("Runtime must not hold the gate while unconfigured", gate.tryAcquire() != null)
    }

    private fun runtimeFixture(): RuntimeFixture {
        val gate = WebDavOperationGate().also(gates::add)
        val availability = MutableStateFlow(
            WebDavAvailability(enabled = true, passwordConfigured = true)
        )
        val documentUrl = server.url("/calendar").toString()
        val newRuntime = WebDavSyncRuntime(
            scope = scope,
            lifecycle = registry(),
            gate = gate,
            availability = availability,
            mutationVersion = signal.version,
            runSync = { binding ->
                check(binding == null) { "integration runs have no staged settings binding" }
                val client = WebDavClient(
                    WebDavSettings.create(documentUrl, "fixture-user"),
                    httpClient
                )
                val password = "fixture-password".toCharArray()
                val session = client.openSession(password)
                try {
                    val result = SyncService(RoomSyncRecordStore(repository), session).sync()
                    WebDavRunResult.Completed(
                        uploaded = result.uploaded,
                        localChanged = result.localChanged,
                        eventCount = result.eventCount
                    )
                } finally {
                    session.close()
                    password.fill('\u0000')
                }
            }
        )
        runtime = newRuntime
        return RuntimeFixture(newRuntime)
    }

    private fun eventService(): EventService = EventService(
        repository = repository,
        clock = Clock.systemUTC(),
        uidGenerator = SyncUidGenerator { syncUid(1) },
        mutationSink = signal
    )

    private fun registry(): LifecycleRegistry {
        lateinit var registry: LifecycleRegistry
        val owner = object : LifecycleOwner {
            override val lifecycle: Lifecycle
                get() = registry
        }
        registry = LifecycleRegistry.createUnsafe(owner)
        return registry
    }

    private suspend fun waitFor(condition: () -> Boolean) {
        withTimeout(5_000) {
            while (!condition()) delay(5)
        }
    }

    private data class RuntimeFixture(val runtime: WebDavSyncRuntime)
}
