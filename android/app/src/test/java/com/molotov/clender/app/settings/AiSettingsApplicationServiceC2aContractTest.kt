package com.molotov.clender.app.settings

import com.molotov.clender.app.ai.AiOperationGate
import com.molotov.clender.data.network.ai.AiClient
import com.molotov.clender.data.network.ai.AiCompletion
import com.molotov.clender.data.network.ai.AiHttpException
import com.molotov.clender.data.network.ai.AiNetworkException
import com.molotov.clender.data.network.ai.AiProtocolException
import com.molotov.clender.data.network.ai.AiRequestCancelledException
import com.molotov.clender.data.network.ai.AiTimeoutException
import com.molotov.clender.data.settings.AiSettings
import com.molotov.clender.data.settings.ApiKeyMutation
import com.molotov.clender.data.settings.AppearanceSettings
import com.molotov.clender.data.settings.ThemeMode
import com.molotov.clender.data.settings.ThinkingEffort
import com.molotov.clender.domain.ai.AiRequestMessage
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiSettingsApplicationServiceC2aContractTest {
    @Test
    fun appearanceSaveAcceptsBothBoundariesAndMapsValidationAndStoreFailures() = runBlocking {
        val port = RecordingAppearanceSettingsPort()
        val service = AppearanceSettingsApplicationService(port)

        assertEquals(
            SettingsSaveDecision.SUCCESS,
            service.save(AppearanceSettings(ThemeMode.DARK, 8, 20))
        )
        assertEquals(AppearanceSettings(ThemeMode.DARK, 8, 20), port.lastValue)
        assertEquals(
            SettingsSaveDecision.VALIDATION_FAILED,
            service.save(AppearanceSettings(ThemeMode.LIGHT, 7, 13))
        )
        assertEquals(1, port.calls)

        port.failure = IllegalStateException("private-store-path")
        val failureDecision = service.save(AppearanceSettings(ThemeMode.SYSTEM, 20, 8))
        assertEquals(SettingsSaveDecision.SAVE_FAILED, failureDecision)
        assertFalse(failureDecision.toString().contains("private-store-path"))
    }

    @Test
    fun saveReturnsOnlyFiniteDecisionsAndValidationNeverTouchesPersistence() = runBlocking {
        val port = RecordingAiSettingsAtomicPort()
        val service = service(port)

        assertEquals(
            SettingsSaveDecision.VALIDATION_FAILED,
            service.saveAi(
                configured().copy(endpoint = "http://unsafe.invalid"),
                ApiKeyMutation.Keep
            )
        )
        assertEquals(
            SettingsSaveDecision.VALIDATION_FAILED,
            service.saveAi(configured().copy(temperature = Double.NaN), ApiKeyMutation.Keep)
        )
        assertEquals(
            SettingsSaveDecision.VALIDATION_FAILED,
            service.saveAi(
                configured().copy(maxOutputTokens = 4_096, contextWindow = 4_096),
                ApiKeyMutation.Keep
            )
        )
        assertEquals(0, port.updateCalls)

        assertEquals(
            SettingsSaveDecision.SUCCESS,
            service.saveAi(configured(), ApiKeyMutation.Keep)
        )
        port.updateFailure = IllegalStateException("private-store-detail")
        assertEquals(
            SettingsSaveDecision.SAVE_FAILED,
            service.saveAi(configured(), ApiKeyMutation.Remove)
        )
        assertEquals(
            setOf(
                SettingsSaveDecision.SUCCESS,
                SettingsSaveDecision.VALIDATION_FAILED,
                SettingsSaveDecision.SAVE_FAILED,
                SettingsSaveDecision.INTERNAL
            ),
            SettingsSaveDecision.entries.toSet()
        )
    }

    @Test
    fun modelFetchSavesBeforeKeyReadAndRequestAndUsesOnlyCommittedEndpointAndKey() = runBlocking {
        val trace = mutableListOf<String>()
        val port = RecordingAiSettingsAtomicPort(
            committed = configured().copy(endpoint = "https://new.invalid/base", model = "new"),
            events = trace
        )
        val client = RecordingModelsClient(
            models = listOf("z", "a", "z", "b", "a"),
            events = trace
        )
        val service = service(port, client)
        val replacement = "placeholder-new-value".toCharArray()

        val result = service.fetchModels(
            configured().copy(endpoint = "https://draft.invalid/base", model = "draft"),
            ApiKeyMutation.Replace(replacement)
        )

        assertEquals(ModelFetchDecision.SUCCESS, result.decision)
        assertEquals(listOf("z", "a", "b"), result.models)
        assertEquals(listOf("update", "read-key", "request"), trace)
        assertEquals("https://new.invalid/base", client.lastSettings?.endpoint)
        assertEquals("new", client.lastSettings?.model)
        assertEquals("committed-placeholder", client.observedKey)
        assertTrue(replacement.all { it == '\u0000' })
        assertTrue(requireNotNull(port.lastReturnedKey).all { it == '\u0000' })
    }

    @Test
    fun busyValidationAndSaveFailuresNeverFallbackToOldDiskConfiguration() = runBlocking {
        val gate = AiOperationGate()
        val held = requireNotNull(gate.tryAcquire())
        val busyPort = RecordingAiSettingsAtomicPort()
        val busyClient = RecordingModelsClient(listOf("must-not-run"))
        val busyService = service(busyPort, busyClient, gate)

        assertEquals(
            ModelFetchDecision.BUSY,
            busyService.fetchModels(configured(), ApiKeyMutation.Keep).decision
        )
        assertEquals(0, busyPort.updateCalls)
        assertEquals(0, busyPort.readKeyCalls)
        assertEquals(0, busyClient.fetchCalls)
        held.close()

        val invalidPort = RecordingAiSettingsAtomicPort()
        val invalidClient = RecordingModelsClient(listOf("must-not-run"))
        val invalidService = service(invalidPort, invalidClient)
        assertEquals(
            ModelFetchDecision.VALIDATION_FAILED,
            invalidService.fetchModels(
                configured().copy(contextWindow = 1),
                ApiKeyMutation.Keep
            ).decision
        )
        assertEquals(0, invalidPort.updateCalls)
        assertEquals(0, invalidPort.readKeyCalls)
        assertEquals(0, invalidClient.fetchCalls)

        val failingPort = RecordingAiSettingsAtomicPort().apply {
            updateFailure = IllegalStateException("do-not-fallback")
        }
        val failingClient = RecordingModelsClient(listOf("must-not-run"))
        assertEquals(
            ModelFetchDecision.SAVE_FAILED,
            service(failingPort, failingClient)
                .fetchModels(configured(), ApiKeyMutation.Keep).decision
        )
        assertEquals(0, failingPort.readKeyCalls)
        assertEquals(0, failingClient.fetchCalls)
    }

    @Test
    fun missingEndpointOrCommittedKeyIsUnconfiguredWithoutNetwork() = runBlocking {
        val missingEndpointPort = RecordingAiSettingsAtomicPort(
            committed = configured().copy(endpoint = "")
        )
        val endpointClient = RecordingModelsClient(emptyList())
        assertEquals(
            ModelFetchDecision.UNCONFIGURED,
            service(missingEndpointPort, endpointClient)
                .fetchModels(configured().copy(endpoint = ""), ApiKeyMutation.Keep).decision
        )
        assertEquals(1, missingEndpointPort.updateCalls)
        assertEquals(0, missingEndpointPort.readKeyCalls)
        assertEquals(0, endpointClient.fetchCalls)

        val noKeyPort = RecordingAiSettingsAtomicPort(key = null)
        val keyClient = RecordingModelsClient(emptyList())
        assertEquals(
            ModelFetchDecision.UNCONFIGURED,
            service(noKeyPort, keyClient)
                .fetchModels(configured(), ApiKeyMutation.Keep).decision
        )
        assertEquals(1, noKeyPort.updateCalls)
        assertEquals(0, noKeyPort.readKeyCalls)
        assertEquals(0, keyClient.fetchCalls)
    }

    @Test
    fun transportFailuresMapToFiniteResultsWithoutProviderDetails() = runBlocking {
        val cases = listOf(
            AiTimeoutException() to ModelFetchDecision.TIMEOUT,
            AiNetworkException() to ModelFetchDecision.NETWORK,
            AiHttpException(503, "private-provider-body") to ModelFetchDecision.PROVIDER,
            AiProtocolException() to ModelFetchDecision.INVALID_RESPONSE,
            AiRequestCancelledException() to ModelFetchDecision.CANCELLED,
            IllegalStateException("private-internal-path") to ModelFetchDecision.INTERNAL
        )

        cases.forEach { (failure, expected) ->
            val port = RecordingAiSettingsAtomicPort()
            val client = RecordingModelsClient(failure = failure)
            val result = service(port, client).fetchModels(configured(), ApiKeyMutation.Keep)

            assertEquals(expected, result.decision)
            assertTrue(result.models.isEmpty())
            assertFalse(result.toString().contains("private-provider-body"))
            assertFalse(result.toString().contains("private-internal-path"))
            assertTrue(requireNotNull(port.lastReturnedKey).all { it == '\u0000' })
        }
        assertEquals(
            setOf(
                ModelFetchDecision.SUCCESS,
                ModelFetchDecision.BUSY,
                ModelFetchDecision.UNCONFIGURED,
                ModelFetchDecision.VALIDATION_FAILED,
                ModelFetchDecision.SAVE_FAILED,
                ModelFetchDecision.TIMEOUT,
                ModelFetchDecision.NETWORK,
                ModelFetchDecision.PROVIDER,
                ModelFetchDecision.INVALID_RESPONSE,
                ModelFetchDecision.CANCELLED,
                ModelFetchDecision.INTERNAL
            ),
            ModelFetchDecision.entries.toSet()
        )
    }

    @Test
    fun cancellationCancelsClientWipesKeyAndReleasesGate() = runBlocking {
        val gate = AiOperationGate()
        val port = RecordingAiSettingsAtomicPort()
        val client = RecordingModelsClient(models = null)
        val service = service(port, client, gate)
        val running = async {
            service.fetchModels(configured(), ApiKeyMutation.Keep)
        }
        client.started.await()

        assertNull(gate.tryAcquire())
        running.cancelAndJoin()

        assertEquals(1, client.cancelCalls)
        assertTrue(requireNotNull(port.lastReturnedKey).all { it == '\u0000' })
        requireNotNull(gate.tryAcquire()).close()
    }

    @Test
    fun oneClientInstanceIsReusedAcrossSuccessfulFetches() = runBlocking {
        val client = RecordingModelsClient(listOf("one"))
        val service = service(RecordingAiSettingsAtomicPort(), client)

        assertEquals(
            ModelFetchDecision.SUCCESS,
            service.fetchModels(configured(), ApiKeyMutation.Keep).decision
        )
        assertEquals(
            ModelFetchDecision.SUCCESS,
            service.fetchModels(configured(), ApiKeyMutation.Keep).decision
        )

        assertEquals(2, client.fetchCalls)
    }

    private fun service(
        port: AiSettingsAtomicPort,
        client: AiClient = RecordingModelsClient(emptyList()),
        gate: AiOperationGate = AiOperationGate()
    ) = AiSettingsApplicationService(port, client, gate)

    private fun configured() = AiSettings(
        endpoint = "https://example.invalid/base",
        model = "model-test",
        temperature = 0.7,
        maxOutputTokens = 512,
        contextWindow = 8_192,
        thinkingEnabled = true,
        thinkingEffort = ThinkingEffort.HIGH,
        systemPrompt = "系统\n🌏",
        personality = "patient\n助手"
    )
}

private class RecordingAiSettingsAtomicPort(
    private val committed: AiSettings? = null,
    private val key: CharArray? = "committed-placeholder".toCharArray(),
    val events: MutableList<String> = mutableListOf()
) : AiSettingsAtomicPort {
    var updateCalls = 0
    var readKeyCalls = 0
    var updateFailure: RuntimeException? = null
    var lastReturnedKey: CharArray? = null
    private var lastCommitted: PersistedAiSettings? = null

    override suspend fun updateAi(
        settings: AiSettings,
        mutation: ApiKeyMutation
    ): PersistedAiSettings {
        events += "update"
        updateCalls += 1
        try {
            updateFailure?.let { throw it }
            return PersistedAiSettings(
                settings = committed ?: settings,
                keyConfigured = key != null
            ).also { lastCommitted = it }
        } finally {
            if (mutation is ApiKeyMutation.Replace) mutation.value.fill('\u0000')
        }
    }

    override suspend fun readApiKey(snapshot: PersistedAiSettings): CharArray? {
        check(snapshot === lastCommitted) { "Only the just-committed snapshot may load its key" }
        events += "read-key"
        readKeyCalls += 1
        return key?.copyOf()?.also { lastReturnedKey = it }
    }
}

private class RecordingAppearanceSettingsPort : AppearanceSettingsPort {
    var calls = 0
    var lastValue: AppearanceSettings? = null
    var failure: RuntimeException? = null

    override suspend fun updateAppearance(settings: AppearanceSettings) {
        calls += 1
        failure?.let { throw it }
        lastValue = settings
    }
}

private class RecordingModelsClient(
    private val models: List<String>? = emptyList(),
    private val failure: RuntimeException? = null,
    val events: MutableList<String> = mutableListOf()
) : AiClient {
    val started = CompletableDeferred<Unit>()
    private val finish = CompletableDeferred<List<String>>()
    var fetchCalls = 0
    var cancelCalls = 0
    var lastSettings: AiSettings? = null
    var observedKey: String? = null

    override suspend fun fetchModels(settings: AiSettings, apiKey: CharArray): List<String> {
        events += "request"
        fetchCalls += 1
        lastSettings = settings
        observedKey = String(apiKey)
        apiKey.fill('\u0000')
        started.complete(Unit)
        failure?.let { throw it }
        return models ?: finish.await()
    }

    override suspend fun complete(
        settings: AiSettings,
        apiKey: CharArray,
        messages: List<AiRequestMessage>
    ): AiCompletion = error("chat is outside this service")

    override fun cancelInFlight() {
        cancelCalls += 1
        finish.cancel()
    }
}
