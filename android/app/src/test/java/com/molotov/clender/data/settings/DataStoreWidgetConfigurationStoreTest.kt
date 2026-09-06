package com.molotov.clender.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.molotov.clender.domain.widget.WidgetConfiguration
import com.molotov.clender.domain.widget.WidgetThemeMode
import java.io.File
import java.io.IOException
import java.time.LocalTime
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class DataStoreWidgetConfigurationStoreTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val files = mutableListOf<File>()

    @After
    fun tearDown() {
        scope.cancel()
        files.forEach(File::delete)
    }

    @Test
    fun missingInstanceIsNullAndExplicitDefaultsRemainDistinctFromStoredConfiguration() =
        runBlocking {
            val (store, _) = storeWithDataStore()

            assertNull(store.get(1))
            assertEquals(emptyList<WidgetConfiguration>(), store.observeAll().first())

            val defaults = WidgetConfiguration.defaults(1, 20)
            store.upsert(defaults)

            assertEquals(defaults, store.get(1))
            assertEquals(listOf(defaults), store.observeAll().first())
        }

    @Test
    fun observeAllReturnsCompleteValidRegisteredInstancesInAscendingIdOrder() = runBlocking {
        val (store, _) = storeWithDataStore()
        val highest = configuration(
            Int.MAX_VALUE,
            ConfigurationOverrides(theme = WidgetThemeMode.DARK)
        )
        val lowest = configuration(1, ConfigurationOverrides(theme = WidgetThemeMode.LIGHT))

        store.upsert(highest)
        store.upsert(lowest)

        assertEquals(listOf(lowest, highest), store.observeAll().first())
    }

    @Test
    fun multipleStoreInstancesAndWidgetIdsRemainIsolated() = runBlocking {
        val (firstStore, dataStore) = storeWithDataStore()
        val secondStore = DataStoreWidgetConfigurationStore(dataStore)
        val first = configuration(1, ConfigurationOverrides(theme = WidgetThemeMode.LIGHT))
        val second = configuration(
            2,
            ConfigurationOverrides(theme = WidgetThemeMode.DARK, opacityPercent = 0)
        )

        firstStore.upsert(first)
        secondStore.upsert(second)

        assertEquals(first, firstStore.get(1))
        assertEquals(second, firstStore.get(2))
        assertEquals(second, secondStore.get(2))
        assertEquals(listOf(first, second), firstStore.observeAll().first())
    }

    @Test
    fun deleteRemovesOnlyTheTargetAndIdReuseDoesNotInheritOldFields() = runBlocking {
        val (store, dataStore) = storeWithDataStore()
        val first = configuration(
            1,
            ConfigurationOverrides(
                theme = WidgetThemeMode.LIGHT,
                opacityPercent = 0,
                fontSizeSp = 8
            )
        )
        val second = configuration(
            2,
            ConfigurationOverrides(
                theme = WidgetThemeMode.DARK,
                opacityPercent = 100,
                fontSizeSp = 20
            )
        )
        store.upsert(first)
        store.upsert(second)

        store.delete(1)

        assertNull(store.get(1))
        assertEquals(second, store.get(2))
        assertEquals(listOf(second), store.observeAll().first())
        assertTrue(dataStore.data.first()[registryKey()].orEmpty().none { it == "1" })

        val replacement = configuration(
            1,
            ConfigurationOverrides(
                startTime = LocalTime.of(9, 0),
                endTime = LocalTime.of(17, 0),
                opacityPercent = 37,
                fontSizeSp = 14,
                theme = WidgetThemeMode.SYSTEM
            )
        )
        store.upsert(replacement)

        assertEquals(replacement, store.get(1))
        assertEquals(listOf(replacement, second), store.observeAll().first())
    }

    @Test
    fun upsertAndDeleteUseOneEditAndInvalidDeleteUsesNoEdit() = runBlocking {
        val fixture = countingFixture()
        val configuration = configuration(1, ConfigurationOverrides())

        fixture.store.upsert(configuration)
        assertEquals(1, fixture.dataStore.updateCalls)
        assertEquals(configuration, fixture.store.get(1))

        fixture.dataStore.updateCalls = 0
        fixture.store.delete(1)
        assertEquals(1, fixture.dataStore.updateCalls)

        fixture.dataStore.updateCalls = 0
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { fixture.store.delete(0) }
        }
        assertEquals(0, fixture.dataStore.updateCalls)
    }

    @Test
    fun invalidRegistryEntriesAndInstancesAreSkippedWithoutPoisoningValidInstances() = runBlocking {
        val (store, dataStore) = storeWithDataStore()
        val valid = configuration(1, ConfigurationOverrides(theme = WidgetThemeMode.LIGHT))
        store.upsert(valid)

        dataStore.edit { values ->
            values[registryKey()] = setOf(
                "1",
                "2",
                "3",
                "4",
                "0",
                "-7",
                "2147483648",
                "not-an-id"
            )
            writeInstance(values, 2, RawWidgetInstanceSpec(endMinute = null))
            writeInstance(
                values,
                3,
                RawWidgetInstanceSpec(startMinute = 22 * 60, endMinute = 8 * 60)
            )
            writeInstance(
                values,
                4,
                RawWidgetInstanceSpec(endMinute = 9 * 60, theme = "UNKNOWN")
            )
            writeInstance(
                values,
                5,
                RawWidgetInstanceSpec(endMinute = 9 * 60)
            )
        }

        assertEquals(listOf(valid), store.observeAll().first())
        assertNull(store.get(2))
        assertNull(store.get(3))
        assertNull(store.get(4))
        assertNull(store.get(5))
    }

    @Test
    fun ioReadFailuresFallBackToNullOrEmptyAndNonIoFailuresPropagate() = runBlocking {
        val ioFailure = IOException("disk")
        val ioStore = DataStoreWidgetConfigurationStore(WidgetFailingReadDataStore(ioFailure))
        assertNull(ioStore.get(1))
        assertEquals(emptyList<WidgetConfiguration>(), ioStore.observeAll().first())

        val nonIoFailure = IllegalStateException("programming failure")
        val nonIoStore = DataStoreWidgetConfigurationStore(
            WidgetFailingReadDataStore(nonIoFailure)
        )
        val thrown = assertThrows(IllegalStateException::class.java) {
            runBlocking { nonIoStore.get(1) }
        }
        assertEquals(nonIoFailure, thrown)
        assertThrows(IllegalStateException::class.java) {
            runBlocking { nonIoStore.observeAll().first() }
        }
        Unit
    }

    @Test
    fun upsertAndDeletePreserveExistingPreferenceAndSecretEnvelopeWireValuesExactly() =
        runBlocking {
            val (store, dataStore) = storeWithDataStore()
            seedExistingWireValues(dataStore)
            val before = unrelatedWireValues(dataStore.data.first().asMap())
            val configuration = configuration(
                appWidgetId = 7,
                overrides = ConfigurationOverrides(theme = WidgetThemeMode.DARK)
            )

            store.upsert(configuration)
            assertEquals(before, unrelatedWireValues(dataStore.data.first().asMap()))

            store.delete(configuration.appWidgetId)
            assertEquals(before, unrelatedWireValues(dataStore.data.first().asMap()))
        }

    private fun configuration(
        appWidgetId: Int,
        overrides: ConfigurationOverrides
    ): WidgetConfiguration = WidgetConfiguration(
        appWidgetId = appWidgetId,
        startTime = overrides.startTime,
        endTime = overrides.endTime,
        opacityPercent = overrides.opacityPercent,
        fontSizeSp = overrides.fontSizeSp,
        theme = overrides.theme
    )

    private fun storeWithDataStore(): Pair<
        DataStoreWidgetConfigurationStore,
        DataStore<Preferences>
        > {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(
            context.filesDir,
            "datastore/t47-widget-${UUID.randomUUID()}.preferences_pb"
        )
        files += file
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) { file }
        return DataStoreWidgetConfigurationStore(dataStore) to dataStore
    }

    private fun countingFixture(): CountingFixture {
        val (_, delegate) = storeWithDataStore()
        val counting = WidgetCountingDataStore(delegate)
        return CountingFixture(DataStoreWidgetConfigurationStore(counting), counting)
    }

    private suspend fun seedExistingWireValues(dataStore: DataStore<Preferences>) {
        dataStore.edit { values ->
            values[stringPreferencesKey(AppPreferenceKeys.THEME)] = "DARK"
            values[stringPreferencesKey(AppPreferenceKeys.APP_FONT_SIZE_SP)] = "20"
            values[stringPreferencesKey(AppPreferenceKeys.WIDGET_FONT_SIZE_SP)] = "8"
            values[stringPreferencesKey(AppPreferenceKeys.ACTIVE_CONVERSATION_ID)] = "active"
            values[stringPreferencesKey(AppPreferenceKeys.WEB_DAV_ENABLED)] = "true"
            values[stringPreferencesKey(AppPreferenceKeys.WEB_DAV_URL)] =
                "https://dav.example.invalid/root/"
            values[stringPreferencesKey(AppPreferenceKeys.WEB_DAV_USERNAME)] = "fixture-user"
            values[stringPreferencesKey(AppPreferenceKeys.AI_ENDPOINT)] =
                "https://ai.example.invalid/base"
            values[stringPreferencesKey(AppPreferenceKeys.AI_MODEL)] = "fixture-model"
            values[stringPreferencesKey(AppPreferenceKeys.AI_TEMPERATURE)] = "1.25"
            values[stringPreferencesKey(AppPreferenceKeys.AI_MAX_OUTPUT_TOKENS)] = "2048"
            values[stringPreferencesKey(AppPreferenceKeys.AI_CONTEXT_WINDOW)] = "32768"
            values[stringPreferencesKey(AppPreferenceKeys.AI_THINKING_ENABLED)] = "false"
            values[stringPreferencesKey(AppPreferenceKeys.AI_THINKING_EFFORT)] = "MEDIUM"
            values[stringPreferencesKey(AppPreferenceKeys.AI_SYSTEM_PROMPT)] = "prompt"
            values[stringPreferencesKey(AppPreferenceKeys.AI_PERSONALITY)] = "personality"
            values[stringPreferencesKey(AppPreferenceKeys.LAST_TOP_DESTINATION)] = "SETTINGS"
            values[stringPreferencesKey(AppPreferenceKeys.SELECTED_DATE)] = "2026-08-31"
            values[stringPreferencesKey(AppPreferenceKeys.CALENDAR_MODE)] = "DAY"

            listOf(
                SecretAlias.AI_API_KEY to SecretEnvelope(7, "ai-iv", "ai-ciphertext"),
                SecretAlias.WEB_DAV_PASSWORD to SecretEnvelope(9, "dav-iv", "dav-ciphertext")
            ).forEach { (alias, envelope) ->
                val keys = PreferenceWireCodec.envelope(alias)
                values[keys.version] = envelope.version
                values[keys.iv] = envelope.iv
                values[keys.ciphertext] = envelope.ciphertext
            }
        }
    }

    private fun unrelatedWireValues(
        values: Map<Preferences.Key<*>, Any>
    ): Map<Preferences.Key<*>, Any> = values.filterKeys {
        !it.name.startsWith("widget_configuration_")
    }

    private fun writeInstance(
        values: androidx.datastore.preferences.core.MutablePreferences,
        appWidgetId: Int,
        spec: RawWidgetInstanceSpec
    ) {
        values[intPreferencesKey(instanceKey(appWidgetId, "start_minute"))] = spec.startMinute
        spec.endMinute?.let { value ->
            values[intPreferencesKey(instanceKey(appWidgetId, "end_minute"))] = value
        }
        values[intPreferencesKey(instanceKey(appWidgetId, "opacity_percent"))] =
            spec.opacityPercent
        values[intPreferencesKey(instanceKey(appWidgetId, "font_size_sp"))] = spec.fontSizeSp
        values[stringPreferencesKey(instanceKey(appWidgetId, "theme"))] = spec.theme
    }

    private fun registryKey() = stringSetPreferencesKey("widget_configuration_ids")

    private fun instanceKey(appWidgetId: Int, field: String) =
        "widget_configuration_${appWidgetId}_$field"
}

private data class ConfigurationOverrides(
    val theme: WidgetThemeMode = WidgetThemeMode.SYSTEM,
    val opacityPercent: Int = 100,
    val fontSizeSp: Int = 13,
    val startTime: LocalTime = LocalTime.of(8, 0),
    val endTime: LocalTime = LocalTime.of(22, 0)
)

private data class RawWidgetInstanceSpec(
    val startMinute: Int = 8 * 60,
    val endMinute: Int? = 9 * 60,
    val opacityPercent: Int = 100,
    val fontSizeSp: Int = 13,
    val theme: String = WidgetThemeMode.SYSTEM.name
)

private data class CountingFixture(
    val store: DataStoreWidgetConfigurationStore,
    val dataStore: WidgetCountingDataStore
)

private class WidgetCountingDataStore(private val delegate: DataStore<Preferences>) :
    DataStore<Preferences> {
    override val data: Flow<Preferences> = delegate.data
    var updateCalls: Int = 0

    override suspend fun updateData(
        transform: suspend (t: Preferences) -> Preferences
    ): Preferences {
        updateCalls += 1
        return delegate.updateData(transform)
    }
}

private class WidgetFailingReadDataStore(private val failure: Throwable) : DataStore<Preferences> {
    override val data: Flow<Preferences> = flow { throw failure }

    override suspend fun updateData(
        transform: suspend (t: Preferences) -> Preferences
    ): Preferences = transform(emptyPreferences())
}
