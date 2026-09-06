package com.molotov.clender.data.settings

data class BackgroundSelection(val fileName: String? = null, val strength: Int = 35)

/** Local appearance policy; no event, network or UI dependencies. */
object BackgroundPolicy {
    const val MAX_INPUT_BYTES = 20 * 1024 * 1024
    private const val MAX_EDGE = 2048
    private const val MAX_SOURCE_EDGE = 32768
    private const val MAX_SOURCE_PIXELS = 100_000_000L
    private const val DEFAULT_STRENGTH = 35
    private const val MAX_STRENGTH = 100
    private const val LIGHT_IMAGE_ALPHA = 0.28f
    private const val DARK_IMAGE_ALPHA = 0.24f

    fun strength(value: Any?): Int = (value as? Number)?.toDouble()
        ?.takeIf { it in 0.0..MAX_STRENGTH.toDouble() && it % 1.0 == 0.0 }
        ?.toInt() ?: DEFAULT_STRENGTH

    fun imageAlpha(strength: Int, dark: Boolean): Float =
        strength(strength) / MAX_STRENGTH.toFloat() *
            if (dark) DARK_IMAGE_ALPHA else LIGHT_IMAGE_ALPHA

    fun sampleSize(width: Int, height: Int): Int {
        if (width !in 1..MAX_SOURCE_EDGE || height !in 1..MAX_SOURCE_EDGE ||
            width.toLong() * height > MAX_SOURCE_PIXELS
        ) {
            return 0
        }
        var sample = 1
        while ((width + sample - 1) / sample > MAX_EDGE ||
            (height + sample - 1) / sample > MAX_EDGE
        ) {
            sample *= 2
        }
        return sample
    }
}
