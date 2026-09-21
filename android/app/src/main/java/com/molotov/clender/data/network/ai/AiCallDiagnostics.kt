package com.molotov.clender.data.network.ai

import com.molotov.clender.diagnostics.DiagnosticEvent
import com.molotov.clender.diagnostics.DiagnosticLogger
import com.molotov.clender.diagnostics.DiagnosticPhase
import com.molotov.clender.diagnostics.DiagnosticRecord
import com.molotov.clender.diagnostics.DiagnosticResult
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Request
import okhttp3.Response

internal class AiCallDiagnostics(private val logger: DiagnosticLogger) {
    private val startedAt = System.nanoTime()

    @Volatile
    private var phase = DiagnosticPhase.QUEUED

    @Volatile
    private var status: Int? = null

    val listener: EventListener = object : EventListener() {
        override fun callStart(call: Call) = stage(DiagnosticPhase.QUEUED)

        override fun dnsStart(call: Call, domainName: String) = stage(DiagnosticPhase.DNS)

        override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) =
            stage(DiagnosticPhase.CONNECT)

        override fun secureConnectStart(call: Call) = stage(DiagnosticPhase.TLS)

        override fun requestHeadersStart(call: Call) = stage(DiagnosticPhase.SEND)

        override fun requestHeadersEnd(call: Call, request: Request) {
            if (request.body == null) stage(DiagnosticPhase.WAIT_HEADERS)
        }

        override fun requestBodyEnd(call: Call, byteCount: Long) =
            stage(DiagnosticPhase.WAIT_HEADERS)

        override fun responseHeadersStart(call: Call) = stage(DiagnosticPhase.WAIT_HEADERS)

        override fun responseHeadersEnd(call: Call, response: Response) {
            status = response.code
            stage(DiagnosticPhase.READ)
        }
    }

    fun finish(failure: AiClientException?) = record(
        if (failure == null) DiagnosticPhase.COMPLETE else phase,
        when (failure) {
            null -> DiagnosticResult.SUCCESS
            is AiTimeoutException -> DiagnosticResult.TIMEOUT
            is AiRequestCancelledException -> DiagnosticResult.CANCELLED
            is AiNetworkException -> DiagnosticResult.NETWORK_ERROR
            is AiContextBudgetException -> DiagnosticResult.CONTEXT_LIMIT
            is AiOutputLimitException -> DiagnosticResult.OUTPUT_LIMIT
            is AiHttpException -> DiagnosticResult.HTTP_ERROR
            is AiResponseTooLargeException -> DiagnosticResult.LIMIT_EXCEEDED
            else -> DiagnosticResult.FAILURE
        }
    )

    private fun stage(value: DiagnosticPhase) {
        phase = value
        record(value, DiagnosticResult.NONE)
    }

    private fun record(value: DiagnosticPhase, result: DiagnosticResult) {
        runCatching {
            logger.record(
                DiagnosticRecord(
                    event = DiagnosticEvent.AI_REQUEST,
                    phase = value,
                    result = result,
                    elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt),
                    httpStatus = status
                )
            )
        }
    }
}
