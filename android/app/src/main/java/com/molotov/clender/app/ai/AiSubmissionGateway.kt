package com.molotov.clender.app.ai

import com.molotov.clender.data.settings.AiSettings
import com.molotov.clender.data.settings.DataStoreAppPreferences
import com.molotov.clender.data.settings.SecretAlias
import com.molotov.clender.data.settings.SecretStore
import java.util.concurrent.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

enum class AiSubmissionDecision {
    ACCEPTED,
    BUSY,
    UNCONFIGURED,
    REJECTED,
    SETTINGS_FAILURE,
    SECRET_FAILURE
}

interface AiSubmissionGateway {
    val state: StateFlow<AiCoordinatorState>

    suspend fun submit(conversationId: String, message: String): AiSubmissionDecision

    fun acknowledgeTerminal()
}

class ConfiguredAiSubmissionGateway(
    private val preferences: DataStoreAppPreferences,
    private val secrets: SecretStore,
    private val coordinator: AiCoordinator,
    private val operationGate: AiOperationGate = AiOperationGate()
) : AiSubmissionGateway {
    override val state: StateFlow<AiCoordinatorState> = coordinator.state

    override suspend fun submit(conversationId: String, message: String): AiSubmissionDecision =
        when {
            conversationId.isBlank() || message.isBlank() -> AiSubmissionDecision.REJECTED
            else -> submitConfigured(conversationId, message)
        }

    private suspend fun submitConfigured(
        conversationId: String,
        message: String
    ): AiSubmissionDecision = when (val loaded = loadSettings()) {
        is GatewayLoad.Failure -> loaded.decision

        is GatewayLoad.Success -> if (!loaded.value.isConfigured()) {
            AiSubmissionDecision.UNCONFIGURED
        } else {
            submitWithGate(conversationId, message, loaded.value)
        }
    }

    private suspend fun submitWithGate(
        conversationId: String,
        message: String,
        settings: AiSettings
    ): AiSubmissionDecision {
        val lease = operationGate.tryAcquire() ?: return AiSubmissionDecision.BUSY
        var accepted = false
        return try {
            val decision = submitWithSecret(conversationId, message, settings, lease)
            accepted = decision == AiSubmissionDecision.ACCEPTED
            decision
        } finally {
            if (!accepted) lease.close()
        }
    }

    private suspend fun loadSettings(): GatewayLoad<AiSettings> = try {
        GatewayLoad.Success(preferences.state.first().ai)
    } catch (error: CancellationException) {
        throw error
    } catch (_: RuntimeException) {
        GatewayLoad.Failure(AiSubmissionDecision.SETTINGS_FAILURE)
    }

    private suspend fun submitWithSecret(
        conversationId: String,
        message: String,
        settings: AiSettings,
        lease: AiOperationLease
    ): AiSubmissionDecision = when (val loaded = loadSecret()) {
        is GatewayLoad.Failure -> loaded.decision

        is GatewayLoad.Success -> coordinate(
            conversationId,
            message,
            settings,
            loaded.value,
            lease
        )
    }

    private suspend fun loadSecret(): GatewayLoad<CharArray> = try {
        secrets.get(SecretAlias.AI_API_KEY)?.let(GatewayLoad<CharArray>::Success)
            ?: GatewayLoad.Failure(AiSubmissionDecision.UNCONFIGURED)
    } catch (error: CancellationException) {
        throw error
    } catch (_: RuntimeException) {
        GatewayLoad.Failure(AiSubmissionDecision.SECRET_FAILURE)
    }

    private suspend fun coordinate(
        conversationId: String,
        message: String,
        settings: AiSettings,
        key: CharArray,
        lease: AiOperationLease
    ): AiSubmissionDecision = try {
        when {
            key.all(Char::isWhitespace) -> AiSubmissionDecision.UNCONFIGURED

            coordinator.submit(conversationId, message, settings, key, lease) ->
                AiSubmissionDecision.ACCEPTED

            coordinator.state.value is AiCoordinatorState.Working -> AiSubmissionDecision.BUSY

            else -> AiSubmissionDecision.REJECTED
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: RuntimeException) {
        AiSubmissionDecision.REJECTED
    } finally {
        key.fill('\u0000')
    }

    override fun acknowledgeTerminal() {
        coordinator.acknowledgeTerminal()
    }
}

private sealed interface GatewayLoad<out T> {
    data class Success<T>(val value: T) : GatewayLoad<T>
    data class Failure(val decision: AiSubmissionDecision) : GatewayLoad<Nothing>
}

private fun AiSettings.isConfigured(): Boolean = endpoint.isNotBlank() && model.isNotBlank()
