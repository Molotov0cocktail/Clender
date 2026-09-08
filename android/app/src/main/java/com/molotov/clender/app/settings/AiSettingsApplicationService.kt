package com.molotov.clender.app.settings

import com.molotov.clender.app.ai.AiOperationGate
import com.molotov.clender.data.network.ai.AiClient
import com.molotov.clender.data.network.ai.AiEndpointValidator
import com.molotov.clender.data.network.ai.AiHttpException
import com.molotov.clender.data.network.ai.AiModelCapabilities
import com.molotov.clender.data.network.ai.AiNetworkException
import com.molotov.clender.data.network.ai.AiProtocolException
import com.molotov.clender.data.network.ai.AiRequestCancelledException
import com.molotov.clender.data.network.ai.AiResponseTooLargeException
import com.molotov.clender.data.network.ai.AiTimeoutException
import com.molotov.clender.data.settings.AiSettings
import com.molotov.clender.data.settings.ApiKeyMutation
import com.molotov.clender.data.settings.AppearanceSettings
import java.util.concurrent.CancellationException

enum class SettingsSaveDecision {
    SUCCESS,
    VALIDATION_FAILED,
    SAVE_FAILED,
    INTERNAL
}

enum class ModelFetchDecision {
    SUCCESS,
    BUSY,
    UNCONFIGURED,
    VALIDATION_FAILED,
    SAVE_FAILED,
    TIMEOUT,
    NETWORK,
    PROVIDER,
    INVALID_RESPONSE,
    CANCELLED,
    INTERNAL
}

data class ModelFetchResult(
    val decision: ModelFetchDecision,
    val models: List<String> = emptyList(),
    val capabilities: Map<String, AiModelCapabilities> = emptyMap()
)

data class PersistedAiSettings(val settings: AiSettings, val keyConfigured: Boolean)

interface AppearanceSettingsPort {
    suspend fun updateAppearance(settings: AppearanceSettings)
}

interface AiSettingsAtomicPort {
    suspend fun updateAi(settings: AiSettings, mutation: ApiKeyMutation): PersistedAiSettings

    suspend fun readApiKey(snapshot: PersistedAiSettings): CharArray?
}

class AppearanceSettingsApplicationService(private val port: AppearanceSettingsPort) {
    suspend fun save(settings: AppearanceSettings): SettingsSaveDecision {
        if (settings.appFontSizeSp !in FONT_RANGE || settings.widgetFontSizeSp !in FONT_RANGE) {
            return SettingsSaveDecision.VALIDATION_FAILED
        }
        return try {
            port.updateAppearance(settings)
            SettingsSaveDecision.SUCCESS
        } catch (error: CancellationException) {
            throw error
        } catch (_: RuntimeException) {
            SettingsSaveDecision.SAVE_FAILED
        }
    }
}

class AiSettingsApplicationService(
    private val port: AiSettingsAtomicPort,
    private val client: AiClient,
    private val operationGate: AiOperationGate
) {
    suspend fun saveAi(settings: AiSettings, mutation: ApiKeyMutation): SettingsSaveDecision = try {
        if (!settings.isValid()) return SettingsSaveDecision.VALIDATION_FAILED
        port.updateAi(settings, mutation)
        SettingsSaveDecision.SUCCESS
    } catch (error: CancellationException) {
        throw error
    } catch (_: IllegalArgumentException) {
        SettingsSaveDecision.VALIDATION_FAILED
    } catch (_: RuntimeException) {
        SettingsSaveDecision.SAVE_FAILED
    } finally {
        mutation.wipeReplacement()
    }

    suspend fun fetchModels(settings: AiSettings, mutation: ApiKeyMutation): ModelFetchResult =
        try {
            when {
                !settings.isValid() -> ModelFetchResult(ModelFetchDecision.VALIDATION_FAILED)

                else -> operationGate.tryAcquire()?.let { lease ->
                    fetchModelsWithLease(settings, mutation, lease)
                } ?: ModelFetchResult(ModelFetchDecision.BUSY)
            }
        } finally {
            mutation.wipeReplacement()
        }

    private suspend fun fetchModelsWithLease(
        settings: AiSettings,
        mutation: ApiKeyMutation,
        lease: AutoCloseable
    ): ModelFetchResult = try {
        when (val saved = persistSettings(settings, mutation)) {
            is PersistResult.Failed -> ModelFetchResult(saved.decision)
            is PersistResult.Saved -> fetchSavedSettings(saved.snapshot)
        }
    } finally {
        lease.close()
    }

    private suspend fun persistSettings(
        settings: AiSettings,
        mutation: ApiKeyMutation
    ): PersistResult = try {
        PersistResult.Saved(port.updateAi(settings, mutation))
    } catch (_: IllegalArgumentException) {
        PersistResult.Failed(ModelFetchDecision.VALIDATION_FAILED)
    } catch (_: CancellationException) {
        PersistResult.Failed(ModelFetchDecision.CANCELLED)
    } catch (_: RuntimeException) {
        PersistResult.Failed(ModelFetchDecision.SAVE_FAILED)
    }

    private suspend fun fetchSavedSettings(snapshot: PersistedAiSettings): ModelFetchResult {
        val keyResult = when {
            snapshot.settings.endpoint.isBlank() || !snapshot.keyConfigured ->
                KeyReadResult.Failed(ModelFetchDecision.UNCONFIGURED)

            else -> readKey(snapshot)
        }
        return when (keyResult) {
            is KeyReadResult.Failed -> ModelFetchResult(keyResult.decision)
            is KeyReadResult.Read -> fetchFromProvider(snapshot.settings, keyResult.key)
        }
    }

    private suspend fun readKey(snapshot: PersistedAiSettings): KeyReadResult = try {
        val key = port.readApiKey(snapshot)
        if (key == null || key.all(Char::isWhitespace)) {
            key?.fill(NULL_CHAR)
            KeyReadResult.Failed(ModelFetchDecision.UNCONFIGURED)
        } else {
            KeyReadResult.Read(key)
        }
    } catch (_: CancellationException) {
        KeyReadResult.Failed(ModelFetchDecision.CANCELLED)
    } catch (_: RuntimeException) {
        KeyReadResult.Failed(ModelFetchDecision.INTERNAL)
    }

    private suspend fun fetchFromProvider(settings: AiSettings, key: CharArray): ModelFetchResult =
        try {
            val catalog = client.fetchModelCatalog(settings, key).distinctBy { it.id }
            ModelFetchResult(
                ModelFetchDecision.SUCCESS,
                catalog.map { it.id },
                catalog.associate { it.id to it.capabilities }
            )
        } catch (_: AiTimeoutException) {
            ModelFetchResult(ModelFetchDecision.TIMEOUT)
        } catch (_: AiNetworkException) {
            ModelFetchResult(ModelFetchDecision.NETWORK)
        } catch (_: AiHttpException) {
            ModelFetchResult(ModelFetchDecision.PROVIDER)
        } catch (_: AiProtocolException) {
            ModelFetchResult(ModelFetchDecision.INVALID_RESPONSE)
        } catch (_: AiResponseTooLargeException) {
            ModelFetchResult(ModelFetchDecision.INVALID_RESPONSE)
        } catch (_: AiRequestCancelledException) {
            ModelFetchResult(ModelFetchDecision.CANCELLED)
        } catch (_: CancellationException) {
            client.cancelInFlight()
            ModelFetchResult(ModelFetchDecision.CANCELLED)
        } catch (_: RuntimeException) {
            ModelFetchResult(ModelFetchDecision.INTERNAL)
        } finally {
            key.fill(NULL_CHAR)
        }
}

private sealed interface PersistResult {
    data class Saved(val snapshot: PersistedAiSettings) : PersistResult
    data class Failed(val decision: ModelFetchDecision) : PersistResult
}

private sealed interface KeyReadResult {
    data class Read(val key: CharArray) : KeyReadResult
    data class Failed(val decision: ModelFetchDecision) : KeyReadResult
}

private fun AiSettings.isValid(): Boolean {
    val invalidEndpoint = endpoint.isNotBlank() &&
        runCatching {
            AiEndpointValidator.modelsUrl(endpoint)
        }.isFailure
    if (invalidEndpoint) {
        return false
    }
    return temperature.isFinite() &&
        temperature in 0.0..MAX_AI_TEMPERATURE &&
        maxOutputTokens > 0 &&
        contextWindow > maxOutputTokens
}

private fun ApiKeyMutation.wipeReplacement() {
    if (this is ApiKeyMutation.Replace) value.fill('\u0000')
}

private val FONT_RANGE = MIN_FONT_SIZE..MAX_FONT_SIZE
private const val MIN_FONT_SIZE = 8
private const val MAX_FONT_SIZE = 20
private const val MAX_AI_TEMPERATURE = 2.0
private const val NULL_CHAR = '\u0000'
