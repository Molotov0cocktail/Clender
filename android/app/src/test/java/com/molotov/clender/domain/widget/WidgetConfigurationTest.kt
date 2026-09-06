package com.molotov.clender.domain.widget

import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WidgetConfigurationTest {
    @Test
    fun acceptsPositiveIdsMinutePrecisionAndSameDayRanges() {
        listOf(1, Int.MAX_VALUE).forEach { appWidgetId ->
            val configuration = configuration(
                ConfigurationSpec(
                    appWidgetId = appWidgetId,
                    startTime = LocalTime.MIDNIGHT,
                    endTime = LocalTime.of(23, 59)
                )
            )

            assertEquals(appWidgetId, configuration.appWidgetId)
            assertEquals(0, configuration.startTime.second)
            assertEquals(0, configuration.startTime.nano)
            assertEquals(0, configuration.endTime.second)
            assertEquals(0, configuration.endTime.nano)
        }
    }

    @Test
    fun rejectsNonPositiveIdsAndInvalidTimeRangesOrPrecision() {
        listOf(0, -1, Int.MIN_VALUE).forEach { appWidgetId ->
            assertThrows(IllegalArgumentException::class.java) {
                configuration(ConfigurationSpec(appWidgetId = appWidgetId))
            }
        }

        listOf(
            LocalTime.of(8, 0) to LocalTime.of(8, 0),
            LocalTime.of(9, 0) to LocalTime.of(8, 59),
            LocalTime.of(8, 0, 1) to LocalTime.of(9, 0),
            LocalTime.of(8, 0) to LocalTime.of(9, 0, 1),
            LocalTime.of(8, 0, 0, 1) to LocalTime.of(9, 0),
            LocalTime.of(8, 0) to LocalTime.of(9, 0, 0, 1)
        ).forEach { (startTime, endTime) ->
            assertThrows(IllegalArgumentException::class.java) {
                configuration(ConfigurationSpec(startTime = startTime, endTime = endTime))
            }
        }
    }

    @Test
    fun defaultsUseEightToTwentyTwoAndOnlyAcceptAValidWidgetFontFallback() {
        val requested = WidgetConfiguration.defaults(1, 20)
        assertEquals(LocalTime.of(8, 0), requested.startTime)
        assertEquals(LocalTime.of(22, 0), requested.endTime)
        assertEquals(100, requested.opacityPercent)
        assertEquals(20, requested.fontSizeSp)
        assertEquals(WidgetThemeMode.SYSTEM, requested.theme)

        assertEquals(8, WidgetConfiguration.defaults(1, 8).fontSizeSp)
        assertEquals(13, WidgetConfiguration.defaults(1, 7).fontSizeSp)
        assertEquals(13, WidgetConfiguration.defaults(1, 21).fontSizeSp)
    }

    @Test
    fun acceptsOpacityAndFontSizeBoundaries() {
        assertEquals(
            0,
            configuration(ConfigurationSpec(opacityPercent = 0, fontSizeSp = 8)).opacityPercent
        )
        assertEquals(
            100,
            configuration(ConfigurationSpec(opacityPercent = 100, fontSizeSp = 20)).opacityPercent
        )
        assertEquals(8, configuration(ConfigurationSpec(fontSizeSp = 8)).fontSizeSp)
        assertEquals(20, configuration(ConfigurationSpec(fontSizeSp = 20)).fontSizeSp)
    }

    @Test
    fun rejectsOpacityAndFontSizeOutsideInclusiveBounds() {
        listOf(-1, 101).forEach { opacityPercent ->
            assertThrows(IllegalArgumentException::class.java) {
                configuration(ConfigurationSpec(opacityPercent = opacityPercent))
            }
        }
        listOf(7, 21).forEach { fontSizeSp ->
            assertThrows(IllegalArgumentException::class.java) {
                configuration(ConfigurationSpec(fontSizeSp = fontSizeSp))
            }
        }
    }

    @Test
    fun themeIsAnIndependentWidgetDomainEnum() {
        assertEquals(
            listOf(
                WidgetThemeMode.SYSTEM,
                WidgetThemeMode.LIGHT,
                WidgetThemeMode.DARK
            ),
            WidgetThemeMode.values().toList()
        )
        assertEquals(WidgetThemeMode.SYSTEM, WidgetConfiguration.defaults(1, 13).theme)
        assertEquals(
            WidgetThemeMode.LIGHT,
            configuration(ConfigurationSpec(theme = WidgetThemeMode.LIGHT)).theme
        )
        assertEquals(
            WidgetThemeMode.DARK,
            configuration(ConfigurationSpec(theme = WidgetThemeMode.DARK)).theme
        )
    }

    private fun configuration(spec: ConfigurationSpec = ConfigurationSpec()): WidgetConfiguration =
        WidgetConfiguration(
            appWidgetId = spec.appWidgetId,
            startTime = spec.startTime,
            endTime = spec.endTime,
            opacityPercent = spec.opacityPercent,
            fontSizeSp = spec.fontSizeSp,
            theme = spec.theme
        )
}

private data class ConfigurationSpec(
    val appWidgetId: Int = 1,
    val startTime: LocalTime = LocalTime.of(8, 0),
    val endTime: LocalTime = LocalTime.of(22, 0),
    val opacityPercent: Int = 100,
    val fontSizeSp: Int = 13,
    val theme: WidgetThemeMode = WidgetThemeMode.SYSTEM
)
