package com.molotov.clender.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class AppPreferencesTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val files = mutableListOf<File>()

    @After
    fun tearDown() {
        scope.cancel()
        files.forEach(File::delete)
    }

    @Test
    fun freshDataStoreUsesStrictCrossApiDefaults() = runBlocking {
        val repository = repository()

        assertEquals(
            AppPreferencesState(
                theme = ThemeMode.SYSTEM,
                appFontSizeSp = 13,
                widgetFontSizeSp = 13,
                activeConversationId = null,
                webDav = WebDavPreferences(
                    enabled = false,
                    url = "",
                    username = ""
                ),
                ai = AiSettings(
                    endpoint = "",
                    model = "",
                    temperature = 0.7,
                    maxOutputTokens = 4_096,
                    contextWindow = 128_000,
                    thinkingEnabled = true,
                    thinkingEffort = ThinkingEffort.HIGH,
                    systemPrompt = "",
                    personality = ""
                )
            ),
            repository.state.first()
        )
    }

    @Test
    fun roundTripPreservesUnicodeAndNeverOffersSecretFields() = runBlocking {
        val (repository, dataStore) = repositoryWithStore()
        val expected = AppPreferencesState(
            theme = ThemeMode.DARK,
            appFontSizeSp = 20,
            widgetFontSizeSp = 8,
            activeConversationId = "conversation-一",
            webDav = WebDavPreferences(
                enabled = true,
                url = "https://dav.example.invalid/计划/",
                username = "用户-🌏"
            ),
            ai = AiSettings(
                endpoint = "https://example.invalid/base",
                model = "模型-🌏",
                temperature = 1.25,
                maxOutputTokens = 8_192,
                contextWindow = 200_000,
                thinkingEnabled = false,
                thinkingEffort = ThinkingEffort.MAX,
                systemPrompt = "系统\n提示",
                personality = "耐心"
            )
        )

        repository.save(expected)

        assertEquals(expected, repository.state.first())
        val raw = dataStore.data.first().asMap()
        val rawNames = raw.keys.map { it.name.lowercase() }
        assertFalse(rawNames.any { "api_key" in it || "password" in it || "secret" in it })
        assertEquals("clender.webdav.password", SecretAlias.WEB_DAV_PASSWORD.keyAlias)
        assertTrue(
            AppPreferencesState::class.java.declaredFields.none {
                it.name.contains("key", ignoreCase = true) ||
                    it.name.contains("password", ignoreCase = true)
            }
        )
    }

    @Test
    fun corruptedOrOutOfRangeValuesFallBackWithoutCrashing() = runBlocking {
        val (repository, dataStore) = repositoryWithStore()
        dataStore.edit { values ->
            values[stringPreferencesKey(AppPreferenceKeys.THEME)] = "ultraviolet"
            values[stringPreferencesKey(AppPreferenceKeys.APP_FONT_SIZE_SP)] = "7"
            values[stringPreferencesKey(AppPreferenceKeys.WIDGET_FONT_SIZE_SP)] = "21"
            values[stringPreferencesKey(AppPreferenceKeys.ACTIVE_CONVERSATION_ID)] = " \t"
            values[stringPreferencesKey(AppPreferenceKeys.AI_TEMPERATURE)] = "NaN"
            values[stringPreferencesKey(AppPreferenceKeys.AI_MAX_OUTPUT_TOKENS)] = "0"
            values[stringPreferencesKey(AppPreferenceKeys.AI_CONTEXT_WINDOW)] = "10"
            values[stringPreferencesKey(AppPreferenceKeys.AI_THINKING_ENABLED)] = "perhaps"
            values[stringPreferencesKey(AppPreferenceKeys.AI_THINKING_EFFORT)] = "unbounded"
            values[stringPreferencesKey(AppPreferenceKeys.WEB_DAV_ENABLED)] = "perhaps"
            values[stringPreferencesKey(AppPreferenceKeys.WEB_DAV_URL)] = "not a URL"
            values[stringPreferencesKey(AppPreferenceKeys.WEB_DAV_USERNAME)] = " \t"
        }

        val state = repository.state.first()

        assertEquals(ThemeMode.SYSTEM, state.theme)
        assertEquals(13, state.appFontSizeSp)
        assertEquals(13, state.widgetFontSizeSp)
        assertNull(state.activeConversationId)
        assertEquals(0.7, state.ai.temperature, 0.0)
        assertEquals(4_096, state.ai.maxOutputTokens)
        assertEquals(128_000, state.ai.contextWindow)
        assertTrue(state.ai.thinkingEnabled)
        assertEquals(ThinkingEffort.HIGH, state.ai.thinkingEffort)
        assertFalse(state.webDav.enabled)
        assertEquals("", state.webDav.url)
        assertEquals("", state.webDav.username)
    }

    @Test
    fun saveRejectsInvalidValuesInsteadOfPersistingPartialState() = runBlocking {
        val repository = repository()
        val before = repository.state.first()

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                repository.save(
                    before.copy(
                        theme = ThemeMode.DARK,
                        appFontSizeSp = 21,
                        webDav = WebDavPreferences(
                            enabled = true,
                            url = "https://dav.example.invalid/",
                            username = "valid-but-must-not-persist"
                        ),
                        ai = before.ai.copy(contextWindow = 2_000, maxOutputTokens = 2_000)
                    )
                )
            }
        }

        assertEquals(before, repository.state.first())
    }

    @Test
    fun contradictoryOrOverflowingStoredTokenPairFallsBackTogether() = runBlocking {
        val (repository, dataStore) = repositoryWithStore()
        dataStore.edit { values ->
            values[stringPreferencesKey(AppPreferenceKeys.AI_MAX_OUTPUT_TOKENS)] =
                Int.MAX_VALUE.toString()
            values[stringPreferencesKey(AppPreferenceKeys.AI_CONTEXT_WINDOW)] = "10"
        }

        val state = repository.state.first()

        assertEquals(4_096, state.ai.maxOutputTokens)
        assertEquals(128_000, state.ai.contextWindow)
    }

    @Test
    fun persistedNetworkSettingsUseTheSameStrictValidatorsAsTransports() = runBlocking {
        val repository = repository()
        val defaults = repository.state.first()
        val unsafeWebDav = listOf(
            "https://dav.example.invalid/root//child",
            "https://dav.example.invalid/root/../child",
            "https://dav.example.invalid/root/%2Fchild"
        )
        unsafeWebDav.forEach { url ->
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    repository.save(
                        defaults.copy(
                            webDav = WebDavPreferences(true, url, "user")
                        )
                    )
                }
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                repository.save(
                    defaults.copy(
                        webDav = WebDavPreferences(
                            true,
                            "https://dav.example.invalid/root/",
                            "user:name"
                        )
                    )
                )
            }
        }
        listOf(
            "https://ai.example.invalid/base//child",
            "https://ai.example.invalid/base/%2e%2e/child",
            "https://ai.example.invalid/v1/chat/completions"
        ).forEach { endpoint ->
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    repository.save(defaults.copy(ai = defaults.ai.copy(endpoint = endpoint)))
                }
            }
        }
        assertEquals(defaults, repository.state.first())
    }

    @Test
    fun corruptedStoredAiEndpointFallsBackToUnconfigured() = runBlocking {
        val (repository, dataStore) = repositoryWithStore()
        listOf(
            "http://ai.example.invalid",
            "https://user:pass@ai.example.invalid",
            "https://ai.example.invalid/base?tenant=1",
            "https://ai.example.invalid/base/%2e%2e/child"
        ).forEach { endpoint ->
            dataStore.edit { values ->
                values[stringPreferencesKey(AppPreferenceKeys.AI_ENDPOINT)] = endpoint
            }
            assertEquals("", repository.state.first().ai.endpoint)
        }
    }

    @Test
    fun navigationStateRoundTripsAndCorruptWireValuesFallBack() = runBlocking {
        val (repository, dataStore) = repositoryWithStore()
        val expected = NavigationPreferences(
            topDestination = TopDestinationPreference.AI,
            selectedDate = LocalDate.of(2026, 8, 9),
            calendarMode = CalendarModePreference.WEEK
        )
        repository.save(repository.state.first().copy(navigation = expected))
        assertEquals(expected, repository.state.first().navigation)

        dataStore.edit { values ->
            values[stringPreferencesKey(AppPreferenceKeys.LAST_TOP_DESTINATION)] = "UNKNOWN"
            values[stringPreferencesKey(AppPreferenceKeys.SELECTED_DATE)] = "2026-02-30"
            values[stringPreferencesKey(AppPreferenceKeys.CALENDAR_MODE)] = "AGENDA"
        }

        assertEquals(NavigationPreferences(), repository.state.first().navigation)
    }

    private fun repository(): DataStoreAppPreferences = repositoryWithStore().first

    private fun repositoryWithStore(): Pair<
        DataStoreAppPreferences,
        androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>
        > {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = File(
            context.filesDir,
            "datastore/t45-${UUID.randomUUID()}.preferences_pb"
        )
        files += file
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) { file }
        return DataStoreAppPreferences(dataStore) to dataStore
    }
}
