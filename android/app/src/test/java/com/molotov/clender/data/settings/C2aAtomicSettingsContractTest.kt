package com.molotov.clender.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
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
@Config(sdk = [26, 36])
class C2aAtomicSettingsContractTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val files = mutableListOf<File>()

    @After
    fun tearDown() {
        scope.cancel()
        files.forEach(File::delete)
    }

    @Test
    fun appearanceUpdateWritesOnlyAppearanceAndAcceptsBothFontBoundaries() = runBlocking {
        val fixture = fixture()
        seedEveryUnrelatedSection(fixture.dataStore)
        val before = fixture.dataStore.data.first().asMap()

        fixture.preferences.updateAppearance(
            AppearanceSettings(ThemeMode.DARK, appFontSizeSp = 8, widgetFontSizeSp = 20)
        )

        assertEquals(
            AppearanceSettings(ThemeMode.DARK, 8, 20),
            fixture.preferences.appearance.first()
        )
        val after = fixture.dataStore.data.first().asMap()
        assertOnlyKeysChanged(
            before,
            after,
            setOf(
                AppPreferenceKeys.THEME,
                AppPreferenceKeys.APP_FONT_SIZE_SP,
                AppPreferenceKeys.WIDGET_FONT_SIZE_SP
            )
        )
    }

    @Test
    fun invalidAppearanceStartsNoEditAndPreservesEverySection() = runBlocking {
        val fixture = fixture()
        seedEveryUnrelatedSection(fixture.dataStore)
        val before = fixture.dataStore.data.first().asMap()
        fixture.counting.reset()

        listOf(7 to 13, 13 to 21).forEach { (appSize, widgetSize) ->
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    fixture.preferences.updateAppearance(
                        AppearanceSettings(ThemeMode.LIGHT, appSize, widgetSize)
                    )
                }
            }
        }

        assertEquals(0, fixture.counting.updateCalls.get())
        assertEquals(before, fixture.dataStore.data.first().asMap())
    }

    @Test
    fun appearanceAndActiveConversationConcurrentEditsDoNotLoseEitherWrite() = runBlocking {
        val fixture = fixture()
        val active = DataStoreActiveConversationStore(fixture.counting)

        val appearanceWrite = async {
            fixture.preferences.updateAppearance(
                AppearanceSettings(ThemeMode.LIGHT, 20, 8)
            )
        }
        val activeWrite = async { active.setActiveConversationId("conversation-fixture") }
        appearanceWrite.await()
        activeWrite.await()

        assertEquals("conversation-fixture", active.activeConversationId.first())
        assertEquals(
            AppearanceSettings(ThemeMode.LIGHT, 20, 8),
            fixture.preferences.appearance.first()
        )
    }

    @Test
    fun aiKeepUsesOneEditAndPreservesEnvelopeByteForByteAndEveryOtherSection() = runBlocking {
        val fixture = fixture()
        seedEveryUnrelatedSection(fixture.dataStore)
        val original = SecretEnvelope(7, "fixture-iv", "fixture-ciphertext")
        fixture.envelopes.write(SecretAlias.AI_API_KEY, original)
        val before = fixture.dataStore.data.first().asMap()
        fixture.counting.reset()

        fixture.preferences.updateAi(changedAi(), ApiKeyMutation.Keep, NoDecryptCipher())

        assertEquals(1, fixture.counting.updateCalls.get())
        assertEquals(original, fixture.envelopes.read(SecretAlias.AI_API_KEY))
        val after = fixture.dataStore.data.first().asMap()
        assertOnlyKeysChanged(before, after, AI_NON_SECRET_KEYS)
    }

    @Test
    fun aiReplaceEncryptsBeforeOneAtomicEditWipesInputAndPreservesWebDavEnvelope() = runBlocking {
        val fixture = fixture()
        seedEveryUnrelatedSection(fixture.dataStore)
        val webDavEnvelope = SecretEnvelope(1, "webdav-iv", "webdav-ciphertext")
        fixture.envelopes.write(SecretAlias.WEB_DAV_PASSWORD, webDavEnvelope)
        val cipher = RecordingAtomicCipher()
        val input = charArrayOf('f', 'i', 'x', 't', 'u', 'r', 'e')
        fixture.counting.reset()

        fixture.preferences.updateAi(changedAi(), ApiKeyMutation.Replace(input), cipher)

        assertEquals(1, fixture.counting.updateCalls.get())
        assertTrue(input.all { it == '\u0000' })
        assertEquals(listOf(SecretAlias.AI_API_KEY), cipher.encryptedAliases)
        assertEquals(cipher.envelope, fixture.envelopes.read(SecretAlias.AI_API_KEY))
        assertEquals(webDavEnvelope, fixture.envelopes.read(SecretAlias.WEB_DAV_PASSWORD))
        assertEquals(changedAi(), fixture.preferences.state.first().ai)
    }

    @Test
    fun aiRemoveUsesOneEditAndDeletesOnlyAiEnvelope() = runBlocking {
        val fixture = fixture()
        val aiEnvelope = SecretEnvelope(1, "ai-iv", "ai-ciphertext")
        val webDavEnvelope = SecretEnvelope(1, "dav-iv", "dav-ciphertext")
        fixture.envelopes.write(SecretAlias.AI_API_KEY, aiEnvelope)
        fixture.envelopes.write(SecretAlias.WEB_DAV_PASSWORD, webDavEnvelope)
        seedEveryUnrelatedSection(fixture.dataStore)
        fixture.counting.reset()

        fixture.preferences.updateAi(changedAi(), ApiKeyMutation.Remove, NoDecryptCipher())

        assertEquals(1, fixture.counting.updateCalls.get())
        assertNull(fixture.envelopes.read(SecretAlias.AI_API_KEY))
        assertEquals(webDavEnvelope, fixture.envelopes.read(SecretAlias.WEB_DAV_PASSWORD))
        assertUnrelatedSeedSurvived(fixture.dataStore)
    }

    @Test
    fun invalidAiAndEncryptionFailureStartNoEditAndWipeReplacementInput() = runBlocking {
        val fixture = fixture()
        seedEveryUnrelatedSection(fixture.dataStore)
        val oldEnvelope = SecretEnvelope(1, "old-iv", "old-ciphertext")
        fixture.envelopes.write(SecretAlias.AI_API_KEY, oldEnvelope)
        val before = fixture.dataStore.data.first().asMap()
        fixture.counting.reset()
        val invalidInput = charArrayOf('b', 'a', 'd')

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                fixture.preferences.updateAi(
                    changedAi().copy(contextWindow = changedAi().maxOutputTokens),
                    ApiKeyMutation.Replace(invalidInput),
                    RecordingAtomicCipher()
                )
            }
        }
        assertTrue(invalidInput.all { it == '\u0000' })
        assertEquals(0, fixture.counting.updateCalls.get())

        val encryptionInput = charArrayOf('e', 'r', 'r', 'o', 'r')
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                fixture.preferences.updateAi(
                    changedAi(),
                    ApiKeyMutation.Replace(encryptionInput),
                    RecordingAtomicCipher(failEncryption = true)
                )
            }
        }
        assertTrue(encryptionInput.all { it == '\u0000' })
        assertEquals(0, fixture.counting.updateCalls.get())
        assertEquals(before, fixture.dataStore.data.first().asMap())
        assertEquals(oldEnvelope, fixture.envelopes.read(SecretAlias.AI_API_KEY))
    }

    @Test
    fun editFailureLeavesOldAiAndEnvelopeTogetherAndWipesInput() = runBlocking {
        val fixture = fixture()
        seedEveryUnrelatedSection(fixture.dataStore)
        val oldAi = fixture.preferences.state.first().ai
        val oldEnvelope = SecretEnvelope(1, "old-iv", "old-ciphertext")
        fixture.envelopes.write(SecretAlias.AI_API_KEY, oldEnvelope)
        val input = charArrayOf('n', 'e', 'w')
        fixture.counting.failNextUpdate = true

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                fixture.preferences.updateAi(
                    changedAi(),
                    ApiKeyMutation.Replace(input),
                    RecordingAtomicCipher()
                )
            }
        }

        assertTrue(input.all { it == '\u0000' })
        assertEquals(oldAi, fixture.preferences.state.first().ai)
        assertEquals(oldEnvelope, fixture.envelopes.read(SecretAlias.AI_API_KEY))
    }

    @Test
    fun configuredPresenceRequiresCompleteEnvelopeWithoutDecryptOrKeyCreation() = runBlocking {
        val fixture = fixture()
        val cipher = NoDecryptCipher()
        assertFalse(fixture.envelopes.isConfigured(SecretAlias.AI_API_KEY))

        fixture.dataStore.edit { values ->
            values[intPreferencesKey("secret_envelope_ai_api_key_version")] = 1
            values[stringPreferencesKey("secret_envelope_ai_api_key_iv")] = "partial-iv"
        }
        assertFalse(fixture.envelopes.isConfigured(SecretAlias.AI_API_KEY))
        assertEquals(0, cipher.decryptCalls)

        fixture.envelopes.write(
            SecretAlias.AI_API_KEY,
            SecretEnvelope(1, "complete-iv", "complete-ciphertext")
        )
        assertTrue(fixture.envelopes.isConfigured(SecretAlias.AI_API_KEY))
        assertEquals(0, cipher.decryptCalls)
    }

    @Test
    fun corruptWireValuesFailClosedAndStateStillContainsNoRawSecretField() = runBlocking {
        val fixture = fixture()
        fixture.dataStore.edit { values ->
            values[stringPreferencesKey(AppPreferenceKeys.AI_ENDPOINT)] = "http://unsafe.invalid"
            values[stringPreferencesKey(AppPreferenceKeys.AI_TEMPERATURE)] = "Infinity"
            values[stringPreferencesKey(AppPreferenceKeys.AI_MAX_OUTPUT_TOKENS)] = "overflow"
            values[stringPreferencesKey(AppPreferenceKeys.AI_CONTEXT_WINDOW)] = "0"
            values[stringPreferencesKey("secret_envelope_ai_api_key_iv")] = "orphan"
        }

        val decoded = fixture.preferences.state.first()

        assertEquals("", decoded.ai.endpoint)
        assertEquals(AppPreferencesState.DEFAULT.ai.temperature, decoded.ai.temperature, 0.0)
        assertFalse(fixture.envelopes.isConfigured(SecretAlias.AI_API_KEY))
        assertTrue(
            AppPreferencesState::class.java.declaredFields.none {
                it.name.contains("key", ignoreCase = true) ||
                    it.name.contains("password", ignoreCase = true) ||
                    it.name.contains("secret", ignoreCase = true)
            }
        )
        assertFalse(decoded.toString().contains("ciphertext", ignoreCase = true))
    }

    private fun fixture(): Fixture {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.filesDir, "datastore/c2a-${UUID.randomUUID()}.preferences_pb")
        files += file
        val delegate = PreferenceDataStoreFactory.create(scope = scope) { file }
        val counting = CountingDataStore(delegate)
        return Fixture(
            preferences = DataStoreAppPreferences(counting),
            envelopes = DataStoreSecretEnvelopeStorage(counting),
            dataStore = counting,
            counting = counting
        )
    }

    private suspend fun seedEveryUnrelatedSection(dataStore: DataStore<Preferences>) {
        dataStore.edit { values ->
            values[stringPreferencesKey(AppPreferenceKeys.ACTIVE_CONVERSATION_ID)] =
                "active-fixture"
            values[stringPreferencesKey(AppPreferenceKeys.WEB_DAV_ENABLED)] = "true"
            values[stringPreferencesKey(AppPreferenceKeys.WEB_DAV_URL)] =
                "https://dav.example.invalid/root/"
            values[stringPreferencesKey(AppPreferenceKeys.WEB_DAV_USERNAME)] = "fixture-user"
            values[stringPreferencesKey(AppPreferenceKeys.LAST_TOP_DESTINATION)] = "EVENTS"
            values[stringPreferencesKey(AppPreferenceKeys.SELECTED_DATE)] = "2026-08-31"
            values[stringPreferencesKey(AppPreferenceKeys.CALENDAR_MODE)] = "WEEK"
        }
    }

    private suspend fun assertUnrelatedSeedSurvived(dataStore: DataStore<Preferences>) {
        val state = DataStoreAppPreferences(dataStore).state.first()
        assertEquals("active-fixture", state.activeConversationId)
        assertEquals(
            WebDavPreferences(true, "https://dav.example.invalid/root/", "fixture-user"),
            state.webDav
        )
        assertEquals(
            NavigationPreferences(
                TopDestinationPreference.EVENTS,
                LocalDate.of(2026, 8, 31),
                CalendarModePreference.WEEK
            ),
            state.navigation
        )
    }

    private fun changedAi() = AppPreferencesState.DEFAULT.ai.copy(
        endpoint = "https://ai.example.invalid/base",
        model = "fixture-model",
        temperature = 1.25,
        maxOutputTokens = 2_048,
        contextWindow = 32_768,
        thinkingEnabled = false,
        thinkingEffort = ThinkingEffort.MEDIUM,
        systemPrompt = "系统提示\n🌏",
        personality = "line one\nline two 🙂"
    )
}

private data class Fixture(
    val preferences: DataStoreAppPreferences,
    val envelopes: DataStoreSecretEnvelopeStorage,
    val dataStore: DataStore<Preferences>,
    val counting: CountingDataStore
)

private class CountingDataStore(private val delegate: DataStore<Preferences>) :
    DataStore<Preferences> {
    val updateCalls = AtomicInteger(0)
    var failNextUpdate: Boolean = false

    override val data: Flow<Preferences> = delegate.data

    override suspend fun updateData(
        transform: suspend (t: Preferences) -> Preferences
    ): Preferences {
        updateCalls.incrementAndGet()
        check(!failNextUpdate.also { failNextUpdate = false }) { "fixture edit failure" }
        return delegate.updateData(transform)
    }

    fun reset() {
        updateCalls.set(0)
    }
}

private class RecordingAtomicCipher(private val failEncryption: Boolean = false) : SecretCipher {
    val encryptedAliases = mutableListOf<SecretAlias>()
    val envelope = SecretEnvelope(1, "replacement-iv", "replacement-ciphertext")

    override fun encrypt(alias: SecretAlias, plaintext: CharArray): SecretEnvelope {
        encryptedAliases += alias
        check(!failEncryption) { "fixture encryption failure" }
        return envelope
    }

    override fun decrypt(alias: SecretAlias, envelope: SecretEnvelope): SecretDecryptionResult =
        error("settings update must not decrypt")
}

private class NoDecryptCipher : SecretCipher {
    var decryptCalls = 0

    override fun encrypt(alias: SecretAlias, plaintext: CharArray): SecretEnvelope =
        error("KEEP/REMOVE/configured presence must not encrypt")

    override fun decrypt(alias: SecretAlias, envelope: SecretEnvelope): SecretDecryptionResult {
        decryptCalls += 1
        error("configured presence must not decrypt")
    }
}

private fun assertOnlyKeysChanged(
    before: Map<Preferences.Key<*>, Any>,
    after: Map<Preferences.Key<*>, Any>,
    allowedNames: Set<String>
) {
    val allNames = (before.keys + after.keys).map { it.name }.toSet()
    allNames.filterNot { it in allowedNames }.forEach { name ->
        assertEquals(
            "unexpected change to $name",
            before.entries.firstOrNull { it.key.name == name }?.value,
            after.entries.firstOrNull { it.key.name == name }?.value
        )
    }
}

private val AI_NON_SECRET_KEYS = setOf(
    AppPreferenceKeys.AI_ENDPOINT,
    AppPreferenceKeys.AI_MODEL,
    AppPreferenceKeys.AI_TEMPERATURE,
    AppPreferenceKeys.AI_MAX_OUTPUT_TOKENS,
    AppPreferenceKeys.AI_CONTEXT_WINDOW,
    AppPreferenceKeys.AI_THINKING_ENABLED,
    AppPreferenceKeys.AI_THINKING_EFFORT,
    AppPreferenceKeys.AI_SYSTEM_PROMPT,
    AppPreferenceKeys.AI_PERSONALITY
)
