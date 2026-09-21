package com.molotov.clender.diagnostics

enum class DiagnosticEvent {
    APP_START,
    APP_CRASH,
    AI_REQUEST,
    AI_BUDGET,
    AI_EXECUTION,
    SYNC,
    ALERT
}

enum class DiagnosticPhase {
    NONE,
    QUEUED,
    DNS,
    CONNECT,
    TLS,
    SEND,
    WAIT_HEADERS,
    READ,
    VALIDATE,
    EXECUTE,
    COMPLETE,
    SCHEDULE,
    DELIVER,
    PLAYBACK
}

enum class DiagnosticResult {
    NONE,
    SUCCESS,
    FAILURE,
    TIMEOUT,
    CANCELLED,
    NETWORK_ERROR,
    HTTP_ERROR,
    INVALID_RESPONSE,
    LIMIT_EXCEEDED,
    PERMISSION_DENIED,
    CONFIGURATION_ERROR,
    CONTEXT_LIMIT,
    OUTPUT_LIMIT
}

enum class DiagnosticFailure {
    NONE,
    STATE_ERROR,
    ARGUMENT_ERROR,
    SECURITY_ERROR,
    IO_ERROR,
    MEMORY_ERROR,
    STACK_ERROR,
    UNKNOWN
}

data class DiagnosticRecord(
    val event: DiagnosticEvent,
    val phase: DiagnosticPhase = DiagnosticPhase.NONE,
    val result: DiagnosticResult = DiagnosticResult.NONE,
    val elapsedMs: Long? = null,
    val httpStatus: Int? = null,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val contextTokens: Long? = null,
    val failure: DiagnosticFailure = DiagnosticFailure.NONE,
    val crashSite: Long? = null,
    val crashLine: Int? = null
)

fun interface DiagnosticLogger {
    fun record(record: DiagnosticRecord)

    companion object {
        val NONE = DiagnosticLogger {}
    }
}
