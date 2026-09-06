package com.molotov.clender.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.molotov.clender.domain.widget.WidgetConfiguration
import com.molotov.clender.domain.widget.WidgetThemeMode
import java.io.IOException
import java.time.LocalTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private const val WIDGET_REGISTRY_NAME = "widget_configuration_ids"
private const val FIRST_MINUTE_OF_DAY = 0
private const val MIN_WIDGET_ID_EXCLUSIVE = 0
private const val HOURS_PER_DAY = 24
private const val MINUTES_PER_HOUR = 60
private const val MINUTES_PER_DAY = HOURS_PER_DAY * MINUTES_PER_HOUR
private const val MIN_FONT_SIZE_SP = 8
private const val MAX_FONT_SIZE_SP = 20
private const val MIN_OPACITY_PERCENT = 0
private const val MAX_OPACITY_PERCENT = 100
private val MINUTE_OF_DAY_RANGE = FIRST_MINUTE_OF_DAY until MINUTES_PER_DAY
private val FONT_RANGE = MIN_FONT_SIZE_SP..MAX_FONT_SIZE_SP
private val OPACITY_RANGE = MIN_OPACITY_PERCENT..MAX_OPACITY_PERCENT
private val REGISTRY_KEY = stringSetPreferencesKey(WIDGET_REGISTRY_NAME)

class DataStoreWidgetConfigurationStore(private val dataStore: DataStore<Preferences>) {
    suspend fun get(appWidgetId: Int): WidgetConfiguration? {
        requirePositiveWidgetId(appWidgetId)
        return readableData().map { values -> decodeRegistered(values, appWidgetId) }.first()
    }

    fun observeAll(): Flow<List<WidgetConfiguration>> = readableData().map(::decodeAll)

    suspend fun upsert(configuration: WidgetConfiguration) {
        validate(configuration)
        val keys = widgetInstanceKeys(configuration.appWidgetId)
        val registryEntry = configuration.appWidgetId.toString()
        dataStore.edit { values ->
            val registeredIds = registeredWidgetIds(values).toMutableSet()
            registeredIds += registryEntry
            values[REGISTRY_KEY] = registeredIds
            encode(values, keys, configuration)
        }
    }

    suspend fun delete(appWidgetId: Int) {
        requirePositiveWidgetId(appWidgetId)
        val keys = widgetInstanceKeys(appWidgetId)
        val registryEntry = appWidgetId.toString()
        dataStore.edit { values ->
            val registeredIds = registeredWidgetIds(values).toMutableSet()
            registeredIds.remove(registryEntry)
            if (registeredIds.isEmpty()) {
                values.remove(REGISTRY_KEY)
            } else {
                values[REGISTRY_KEY] = registeredIds
            }
            values.remove(keys.startMinute)
            values.remove(keys.endMinute)
            values.remove(keys.opacityPercent)
            values.remove(keys.fontSizeSp)
            values.remove(keys.theme)
        }
    }

    private fun readableData(): Flow<Preferences> = dataStore.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }

    private fun decodeAll(values: Preferences): List<WidgetConfiguration> =
        registeredWidgetIds(values)
            .asSequence()
            .mapNotNull(::canonicalWidgetId)
            .mapNotNull { appWidgetId -> decodeRegistered(values, appWidgetId) }
            .sortedBy { it.appWidgetId }
            .toList()

    private fun decodeRegistered(values: Preferences, appWidgetId: Int): WidgetConfiguration? {
        return runCatching {
            if (registeredWidgetIds(values).none { it == appWidgetId.toString() }) {
                return@runCatching null
            }
            val keys = widgetInstanceKeys(appWidgetId)
            val startMinute = values[keys.startMinute] ?: return@runCatching null
            val endMinute = values[keys.endMinute] ?: return@runCatching null
            val opacityPercent = values[keys.opacityPercent] ?: return@runCatching null
            val fontSizeSp = values[keys.fontSizeSp] ?: return@runCatching null
            val theme = values[keys.theme]?.let(::parseWidgetTheme) ?: return@runCatching null
            if (startMinute !in MINUTE_OF_DAY_RANGE || endMinute !in MINUTE_OF_DAY_RANGE) {
                return@runCatching null
            }
            if (opacityPercent !in OPACITY_RANGE || fontSizeSp !in FONT_RANGE) {
                return@runCatching null
            }
            WidgetConfiguration(
                appWidgetId = appWidgetId,
                startTime = widgetLocalTimeFromMinute(startMinute),
                endTime = widgetLocalTimeFromMinute(endMinute),
                opacityPercent = opacityPercent,
                fontSizeSp = fontSizeSp,
                theme = theme
            )
        }.getOrNull()
    }

    private fun encode(
        values: MutablePreferences,
        keys: WidgetInstanceKeys,
        configuration: WidgetConfiguration
    ) {
        values[keys.startMinute] = configuration.startTime.toSecondOfDay() / MINUTES_PER_HOUR
        values[keys.endMinute] = configuration.endTime.toSecondOfDay() / MINUTES_PER_HOUR
        values[keys.opacityPercent] = configuration.opacityPercent
        values[keys.fontSizeSp] = configuration.fontSizeSp
        values[keys.theme] = configuration.theme.name
    }

    private fun validate(configuration: WidgetConfiguration) {
        requirePositiveWidgetId(configuration.appWidgetId)
        require(
            configuration.startTime.second == 0 && configuration.startTime.nano == 0
        ) { "Widget start time must have minute precision" }
        require(
            configuration.endTime.second == 0 && configuration.endTime.nano == 0
        ) { "Widget end time must have minute precision" }
        require(configuration.startTime < configuration.endTime) {
            "Widget time range must be increasing"
        }
        require(configuration.opacityPercent in OPACITY_RANGE) {
            "Widget opacity is out of range"
        }
        require(configuration.fontSizeSp in FONT_RANGE) {
            "Widget font size is out of range"
        }
    }
}

private fun registeredWidgetIds(values: Preferences): Set<String> =
    runCatching { values[REGISTRY_KEY].orEmpty() }.getOrDefault(emptySet())

private fun canonicalWidgetId(raw: String): Int? = raw.toIntOrNull()
    ?.takeIf { it > 0 && it.toString() == raw }

private fun requirePositiveWidgetId(appWidgetId: Int) {
    require(appWidgetId > MIN_WIDGET_ID_EXCLUSIVE) { "Widget ID must be positive" }
}

private fun widgetLocalTimeFromMinute(minute: Int): LocalTime =
    LocalTime.of(minute / MINUTES_PER_HOUR, minute % MINUTES_PER_HOUR)

private fun parseWidgetTheme(raw: String): WidgetThemeMode? =
    enumValues<WidgetThemeMode>().firstOrNull { it.name == raw }

private data class WidgetInstanceKeys(
    val startMinute: Preferences.Key<Int>,
    val endMinute: Preferences.Key<Int>,
    val opacityPercent: Preferences.Key<Int>,
    val fontSizeSp: Preferences.Key<Int>,
    val theme: Preferences.Key<String>
)

private fun widgetInstanceKeys(appWidgetId: Int): WidgetInstanceKeys {
    val prefix = "widget_configuration_${appWidgetId}_"
    return WidgetInstanceKeys(
        startMinute = intPreferencesKey("${prefix}start_minute"),
        endMinute = intPreferencesKey("${prefix}end_minute"),
        opacityPercent = intPreferencesKey("${prefix}opacity_percent"),
        fontSizeSp = intPreferencesKey("${prefix}font_size_sp"),
        theme = stringPreferencesKey("${prefix}theme")
    )
}
