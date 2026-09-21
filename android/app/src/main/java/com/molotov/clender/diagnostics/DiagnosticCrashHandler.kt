package com.molotov.clender.diagnostics

import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest

class DiagnosticCrashHandler(
    private val logger: DiagnosticLogger,
    private val previous: Thread.UncaughtExceptionHandler
) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(thread: Thread, error: Throwable) {
        runCatching {
            val site = crashLocation(error)
            logger.record(
                DiagnosticRecord(
                    DiagnosticEvent.APP_CRASH,
                    result = DiagnosticResult.FAILURE,
                    failure = classify(error),
                    crashSite = site?.first,
                    crashLine = site?.second
                )
            )
        }
        previous.uncaughtException(thread, error)
    }

    private fun classify(error: Throwable): DiagnosticFailure = when (error) {
        is IllegalStateException -> DiagnosticFailure.STATE_ERROR
        is IllegalArgumentException -> DiagnosticFailure.ARGUMENT_ERROR
        is SecurityException -> DiagnosticFailure.SECURITY_ERROR
        is IOException -> DiagnosticFailure.IO_ERROR
        is OutOfMemoryError -> DiagnosticFailure.MEMORY_ERROR
        is StackOverflowError -> DiagnosticFailure.STACK_ERROR
        else -> DiagnosticFailure.UNKNOWN
    }

    private fun crashLocation(error: Throwable): Pair<Long, Int>? {
        val candidates = error.stackTrace.take(MAX_FRAMES).filter {
            it.className.length <= MAX_CLASS_LENGTH && FRAME_CLASS.matches(it.className) &&
                METHOD.matches(it.methodName) && it.lineNumber in 1..MAX_LINE
        }
        val frame = candidates.firstOrNull { it.className.startsWith("com.molotov.clender.") }
            ?: candidates.firstOrNull { candidate ->
                PLATFORM_PREFIXES.none { candidate.className.startsWith(it) }
            }
            ?: candidates.firstOrNull()
            ?: return null
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("${frame.className}#${frame.methodName}".toByteArray(Charsets.UTF_8))
        return (ByteBuffer.wrap(digest).long and Long.MAX_VALUE) to frame.lineNumber
    }

    companion object {
        private const val MAX_FRAMES = 32
        private const val MAX_CLASS_LENGTH = 256
        private const val MAX_LINE = 1_000_000
        private val FRAME_CLASS = Regex("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*")
        private val METHOD = Regex("[A-Za-z_$][A-Za-z0-9_$]{0,120}|<init>|<clinit>")
        private val PLATFORM_PREFIXES = listOf(
            "java.", "javax.", "android.", "androidx.", "kotlin.", "kotlinx.",
            "sun.", "dalvik.", "com.android."
        )
    }
}
