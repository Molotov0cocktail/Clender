package com.molotov.clender.diagnostics

/** Only this finite vocabulary and bounded numeric values can reach disk or export. */
@Suppress("MagicNumber") // Fixed schema v1 column positions and bounded numeric ranges.
internal object DiagnosticCodec {
    private const val MAX_TIMESTAMP = 32_503_680_000_000L
    private const val MAX_DURATION = 86_400_000L
    private const val MAX_TOKENS = 2_147_483_647L

    fun encode(timestamp: Long, record: DiagnosticRecord): String = listOf(
        timestamp.coerceIn(0, MAX_TIMESTAMP).toString(),
        record.event.name,
        record.phase.name,
        record.result.name,
        numeric(record.elapsedMs, MAX_DURATION),
        record.httpStatus?.takeIf { it in 100..599 }?.toString() ?: "-",
        numeric(record.inputTokens, MAX_TOKENS),
        numeric(record.outputTokens, MAX_TOKENS),
        numeric(record.contextTokens, MAX_TOKENS),
        record.failure.name,
        numeric(record.crashSite, Long.MAX_VALUE),
        record.crashLine?.takeIf { it in 1..1_000_000 }?.toString() ?: "-"
    ).joinToString("|", postfix = "\n")

    @Suppress("ReturnCount") // Reject each invalid field before constructing an export record.
    fun sanitize(line: String): String? {
        val fields = line.split('|')
        if (fields.size != 12) return null
        val timestamp = fields[0].toLongOrNull()?.takeIf { it in 0..MAX_TIMESTAMP } ?: return null
        val event = DiagnosticEvent.entries.find { it.name == fields[1] } ?: return null
        val phase = DiagnosticPhase.entries.find { it.name == fields[2] } ?: return null
        val result = DiagnosticResult.entries.find { it.name == fields[3] } ?: return null
        val failure = DiagnosticFailure.entries.find { it.name == fields[9] } ?: return null
        if (!validMetrics(fields)) return null
        if (fields[5] != "-" && fields[5].toIntOrNull() !in 100..599) return null
        if (fields[11] != "-" && fields[11].toIntOrNull() !in 1..1_000_000) return null
        return encode(
            timestamp,
            DiagnosticRecord(
                event, phase, result, fields[4].toLongOrNull(), fields[5].toIntOrNull(),
                fields[6].toLongOrNull(), fields[7].toLongOrNull(), fields[8].toLongOrNull(),
                failure,
                fields[10].toLongOrNull(), fields[11].toIntOrNull()
            )
        )
    }

    private fun validMetrics(fields: List<String>): Boolean =
        validNumber(fields[4], MAX_DURATION) && validNumber(fields[10], Long.MAX_VALUE) &&
            listOf(6, 7, 8).all { validNumber(fields[it], MAX_TOKENS) }

    private fun numeric(value: Long?, maximum: Long): String =
        value?.takeIf { it in 0..maximum }?.toString() ?: "-"

    private fun validNumber(value: String, maximum: Long): Boolean =
        value == "-" || value.toLongOrNull()?.let { it in 0..maximum } == true
}
