package com.molotov.clender.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundPolicyTest {
    @Test
    fun strengthDefaultsAndBoundariesAreSafe() {
        listOf(null, -1, 101).forEach { assertEquals(35, BackgroundPolicy.strength(it)) }
        listOf(0, 35, 100).forEach { assertEquals(it, BackgroundPolicy.strength(it)) }
    }

    @Test
    fun everyStrengthPreservesReadableLightAndDarkMasks() {
        for (strength in 0..100) {
            assertTrue(BackgroundPolicy.imageAlpha(strength, false) in 0f..0.28f)
            assertTrue(BackgroundPolicy.imageAlpha(strength, true) in 0f..0.24f)
        }
        assertEquals(0f, BackgroundPolicy.imageAlpha(0, false), 0f)
    }

    @Test
    fun sampleBoundsBothNormalAndPanoramicImages() {
        for ((width, height) in listOf(100 to 100, 8000 to 6000, 32000 to 20)) {
            val sample = BackgroundPolicy.sampleSize(width, height)
            assertTrue(sample > 0)
            assertTrue(width / sample <= 2048 && height / sample <= 2048)
        }
        for ((width, height) in listOf(0 to 10, -1 to 10, 100000 to 100000)) {
            assertEquals(0, BackgroundPolicy.sampleSize(width, height))
        }
    }
}
