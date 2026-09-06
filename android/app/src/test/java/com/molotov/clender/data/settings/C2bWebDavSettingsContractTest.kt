package com.molotov.clender.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import java.io.File
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
class C2bWebDavSettingsContractTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val files = mutableListOf<File>()

    @After
    fun tearDown() {
        scope.cancel()
        files.forEach(File::delete)
    }

    @Test
    fun keepWritesOnlyWebDavNonSecretKeysInOneEditAndKeepsEnvelopeByteForByte() = runBlocking {
        val fixture = fixture()
        seedEveryUnrelatedSection(fixture.dataStore)
        val original = SecretEnvelope(7, "dav-iv", "dav-ciphertext")
        fixture.envelopes.write(SecretAlias.WEB_DAV_PASSWORD, original)
        fixture.envelopes.write(
            SecretAlias.AI_API_KEY,
            SecretEnvelope(1, "ai-iv", "ai-ciphertext")
        )
        val before = fixture.dataStore.data.first().asMap()
        fixture.counting.reset()
        val cipher = C2bNoDecryptCipher()

        val snapshot = fixture.preferences.updateWebDav(
            validSettings(),
            WebDavPasswordMutation.Keep,
            cipher
        )

        assertEquals(1, fixture.counting.updateCalls.get())
        assertEquals(0, cipher.decryptCalls)
        assertEquals(
            WebDavSectionSnapshot(
                enabled = true,
                url = validSettings().url,
                username = validSettings().username,
                passwordConfigured = true
            ),
            snapshot
        )
        assertEquals(original, fixture.envelopes.read(SecretAlias.WEB_DAV_PASSWORD))
        assertEquals(
            SecretEnvelope(1, "ai-iv", "ai-ciphertext"),
            fixture.envelopes.read(SecretAlias.AI_API_KEY)
        )
        c2bAssertOnlyKeysChanged(
            before,
            fixture.dataStore.data.first().asMap(),
            WEB_DAV_NON_SECRET_KEYS
        )
        assertEquals(validSettings(), fixture.preferences.state.first().webDav)
    }

    @Test
    fun replaceEncryptsBeforeOneAtomicEditWipesInputAndPreservesAiSecretAndEveryOtherSection() =
        runBlocking {
            val fixture = fixture()
            seedEveryUnrelatedSection(fixture.dataStore)
            fixture.envelopes.write(
                SecretAlias.AI_API_KEY,
                SecretEnvelope(1, "ai-iv", "ai-ciphertext")
            )
            fixture.envelopes.write(
                SecretAlias.WEB_DAV_PASSWORD,
                SecretEnvelope(1, "old-dav-iv", "old-dav-ciphertext")
            )
            val before = fixture.dataStore.data.first().asMap()
            fixture.counting.reset()
            val cipher = C2bRecordingCipher()
            val input = charArrayOf('f', 'i', 'x', 't', 'u', 'r', 'e')

            val snapshot = fixture.preferences.updateWebDav(
                validSettings(),
                WebDavPasswordMutation.Replace(input),
                cipher
            )

            assertEquals(1, fixture.counting.updateCalls.get())
            assertTrue(input.all { it == '\u0000' })
            assertEquals(listOf(SecretAlias.WEB_DAV_PASSWORD), cipher.encryptedAliases)
            assertEquals(cipher.envelope, fixture.envelopes.read(SecretAlias.WEB_DAV_PASSWORD))
            assertEquals(
                SecretEnvelope(1, "ai-iv", "ai-ciphertext"),
                fixture.envelopes.read(SecretAlias.AI_API_KEY)
            )
            assertTrue(snapshot.passwordConfigured)
            c2bAssertOnlyKeysChanged(
                before,
                fixture.dataStore.data.first().asMap(),
                WEB_DAV_NON_SECRET_KEYS + WEB_DAV_ENVELOPE_KEYS
            )
            assertEquals(validSettings(), fixture.preferences.state.first().webDav)
        }

    @Test
    fun removeWritesWebDavNonSecretKeysDeletesOnlyWebDavEnvelopeInOneEdit() = runBlocking {
        val fixture = fixture()
        seedEveryUnrelatedSection(fixture.dataStore)
        fixture.envelopes.write(
            SecretAlias.AI_API_KEY,
            SecretEnvelope(1, "ai-iv", "ai-ciphertext")
        )
        fixture.envelopes.write(
            SecretAlias.WEB_DAV_PASSWORD,
            SecretEnvelope(1, "dav-iv", "dav-ciphertext")
        )
        val before = fixture.dataStore.data.first().asMap()
        fixture.counting.reset()
        val disabledPreconfigured =
            WebDavPreferences(false, validSettings().url, validSettings().username)

        val snapshot = fixture.preferences.updateWebDav(
            disabledPreconfigured,
            WebDavPasswordMutation.Remove,
            C2bNoDecryptCipher()
        )

        assertEquals(1, fixture.counting.updateCalls.get())
        assertFalse(snapshot.passwordConfigured)
        assertEquals(disabledPreconfigured, fixture.preferences.state.first().webDav)
        assertNull(fixture.envelopes.read(SecretAlias.WEB_DAV_PASSWORD))
        assertEquals(
            SecretEnvelope(1, "ai-iv", "ai-ciphertext"),
            fixture.envelopes.read(SecretAlias.AI_API_KEY)
        )
        c2bAssertOnlyKeysChanged(
            before,
            fixture.dataStore.data.first().asMap(),
            WEB_DAV_NON_SECRET_KEYS + WEB_DAV_ENVELOPE_KEYS
        )
    }

    @Test
    fun enabledWithoutUsablePasswordIsRejectedWithZeroEdits() = runBlocking {
        val fixture = fixture()
        seedEveryUnrelatedSection(fixture.dataStore)
        fixture.envelopes.write(
            SecretAlias.WEB_DAV_PASSWORD,
            SecretEnvelope(1, "dav-iv", "dav-ciphertext")
        )
        val before = fixture.dataStore.data.first().asMap()
        fixture.counting.reset()
        val blankInput = "   ".toCharArray()

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                fixture.preferences.updateWebDav(
                    validSettings(),
                    WebDavPasswordMutation.Remove,
                    C2bNoDecryptCipher()
                )
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                val noEnvelope = fixture()
                noEnvelope.preferences.updateWebDav(
                    validSettings(),
                    WebDavPasswordMutation.Keep,
                    C2bNoDecryptCipher()
                )
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                val noEnvelope = fixture()
                noEnvelope.preferences.updateWebDav(
                    validSettings(),
                    WebDavPasswordMutation.Replace(blankInput),
                    C2bNoDecryptCipher()
                )
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                fixture.preferences.updateWebDav(
                    validSettings().copy(url = "https://dav.example.invalid/root//bad"),
                    WebDavPasswordMutation.Keep,
                    C2bNoDecryptCipher()
                )
            }
        }

        assertEquals(0, fixture.counting.updateCalls.get())
        assertTrue(blankInput.all { it == '\u0000' })
        assertEquals(before, fixture.dataStore.data.first().asMap())
    }

    @Test
    fun disabledBlankAndDisabledPreconfiguredBothSaveWithoutPasswordRequirement() = runBlocking {
        val blankKeep = fixture()
        val blankSnapshot = blankKeep.preferences.updateWebDav(
            WebDavPreferences(false, "", ""),
            WebDavPasswordMutation.Keep,
            C2bNoDecryptCipher()
        )
        assertEquals(WebDavSectionSnapshot(false, "", "", false), blankSnapshot)
        assertEquals(1, blankKeep.counting.updateCalls.get())

        val preconfigured = fixture()
        val preconfiguredSnapshot = preconfigured.preferences.updateWebDav(
            WebDavPreferences(false, validSettings().url, validSettings().username),
            WebDavPasswordMutation.Keep,
            C2bNoDecryptCipher()
        )
        assertEquals(
            WebDavSectionSnapshot(
                false,
                validSettings().url,
                validSettings().username,
                false
            ),
            preconfiguredSnapshot
        )
        assertEquals(
            WebDavPreferences(false, validSettings().url, validSettings().username),
            preconfigured.preferences.state.first().webDav
        )
        assertEquals(1, preconfigured.counting.updateCalls.get())

        val preconfiguredReplace = fixture()
        val cipher = C2bRecordingCipher()
        val input = "disabled-pass".toCharArray()
        val replaceSnapshot = preconfiguredReplace.preferences.updateWebDav(
            WebDavPreferences(false, validSettings().url, "disabled-user"),
            WebDavPasswordMutation.Replace(input),
            cipher
        )
        assertTrue(input.all { it == '\u0000' })
        assertTrue(replaceSnapshot.passwordConfigured)
        assertEquals(
            cipher.envelope,
            preconfiguredReplace.envelopes.read(SecretAlias.WEB_DAV_PASSWORD)
        )
        assertEquals(1, preconfiguredReplace.counting.updateCalls.get())
    }

    @Test
    fun invalidUrlOrUsernameIsRejectedWithZeroEditsAndNoEncryption() = runBlocking {
        val fixture = fixture()
        seedEveryUnrelatedSection(fixture.dataStore)
        val before = fixture.dataStore.data.first().asMap()
        fixture.counting.reset()
        val cipher = C2bRecordingCipher()
        val invalid = listOf(
            "https://dav.example.invalid/root/" to "",
            "https://dav.example.invalid/root/" to " ",
            "https://dav.example.invalid/root/" to "user:name",
            "https://dav.example.invalid/root/" to "user\uD800",
            "https://dav.example.invalid/root/" to "\uDC00user",
            "" to "fixture-user",
            "   " to "fixture-user",
            "http://dav.example.invalid/root/" to "fixture-user",
            "https://" to "fixture-user",
            "https://user:pass@dav.example.invalid/root/" to "fixture-user",
            "https://dav.example.invalid/root/?tenant=1" to "fixture-user",
            "https://dav.example.invalid/root/#frag" to "fixture-user",
            "https://dav.example.invalid\\root\\" to "fixture-user",
            "https://dav.example.invalid/root//child" to "fixture-user",
            "https://dav.example.invalid/root/../child" to "fixture-user",
            "https://dav.example.invalid/root/%2Fchild" to "fixture-user",
            "https://dav.example.invalid/root/%5Cchild" to "fixture-user",
            "https://dav.example.invalid/root/%2e%2e/child" to "fixture-user"
        )
        invalid.forEach { (url, username) ->
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    fixture.preferences.updateWebDav(
                        WebDavPreferences(false, url, username),
                        WebDavPasswordMutation.Keep,
                        cipher
                    )
                }
            }
        }

        assertEquals(0, fixture.counting.updateCalls.get())
        assertTrue(cipher.encryptedAliases.isEmpty())
        assertEquals(before, fixture.dataStore.data.first().asMap())
    }

    @Test
    fun encryptionFailureStartsNoEditKeepsOldStateAndWipesInput() = runBlocking {
        val fixture = fixture()
        seedEveryUnrelatedSection(fixture.dataStore)
        val oldEnvelope = SecretEnvelope(1, "old-dav-iv", "old-dav-ciphertext")
        fixture.envelopes.write(SecretAlias.WEB_DAV_PASSWORD, oldEnvelope)
        fixture.seedWebDav()
        val before = fixture.dataStore.data.first().asMap()
        fixture.counting.reset()
        val cipher = C2bRecordingCipher(failEncryption = true)
        val input = charArrayOf('e', 'r', 'r', 'o', 'r')

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                fixture.preferences.updateWebDav(
                    validSettings(),
                    WebDavPasswordMutation.Replace(input),
                    cipher
                )
            }
        }

        assertTrue(input.all { it == '\u0000' })
        assertEquals(listOf(SecretAlias.WEB_DAV_PASSWORD), cipher.encryptedAliases)
        assertEquals(0, fixture.counting.updateCalls.get())
        assertEquals(before, fixture.dataStore.data.first().asMap())
        assertEquals(oldEnvelope, fixture.envelopes.read(SecretAlias.WEB_DAV_PASSWORD))
    }

    @Test
    fun editFailureKeepsOldWebDavAndEnvelopeTogetherAndWipesInput() = runBlocking {
        val fixture = fixture()
        seedEveryUnrelatedSection(fixture.dataStore)
        val oldEnvelope = SecretEnvelope(1, "old-dav-iv", "old-dav-ciphertext")
        fixture.envelopes.write(SecretAlias.WEB_DAV_PASSWORD, oldEnvelope)
        fixture.seedWebDav()
        val before = fixture.dataStore.data.first().asMap()
        fixture.counting.reset()
        val input = charArrayOf('n', 'e', 'w', '-', 'p', 'a', 's', 's')
        fixture.counting.failNextUpdate = true

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                fixture.preferences.updateWebDav(
                    validSettings(),
                    WebDavPasswordMutation.Replace(input),
                    C2bRecordingCipher()
                )
            }
        }

        assertTrue(input.all { it == '\u0000' })
        assertEquals(1, fixture.counting.updateCalls.get())
        assertEquals(before, fixture.dataStore.data.first().asMap())
        assertEquals(OLD_WEB_DAV, fixture.preferences.state.first().webDav)
        assertEquals(oldEnvelope, fixture.envelopes.read(SecretAlias.WEB_DAV_PASSWORD))
    }

    @Test
    fun webDavUpdateConcurrentWithActiveConversationEditLosesNeitherWrite() = runBlocking {
        val fixture = fixture()
        seedEveryUnrelatedSection(fixture.dataStore)
        fixture.counting.reset()
        val active = DataStoreActiveConversationStore(fixture.counting)
        val cipher = C2bRecordingCipher()

        val webDavWrite = async {
            fixture.preferences.updateWebDav(
                validSettings(),
                WebDavPasswordMutation.Replace("concurrent-pass".toCharArray()),
                cipher
            )
        }
        val activeWrite = async { active.setActiveConversationId("conversation-fixture") }
        webDavWrite.await()
        activeWrite.await()

        assertEquals("conversation-fixture", active.activeConversationId.first())
        assertEquals(validSettings(), fixture.preferences.state.first().webDav)
        assertEquals(cipher.envelope, fixture.envelopes.read(SecretAlias.WEB_DAV_PASSWORD))
        assertEquals("DARK", fixture.preferences.state.first().theme.name)
    }

    @Test
    fun blankReplaceIsTreatedAsKeepPreservesEnvelopeAndStillWipesInput() = runBlocking {
        val withEnvelope = fixture()
        seedEveryUnrelatedSection(withEnvelope.dataStore)
        val original = SecretEnvelope(3, "dav-iv", "dav-ciphertext")
        withEnvelope.envelopes.write(SecretAlias.WEB_DAV_PASSWORD, original)
        val cipher = C2bNoDecryptCipher()
        val blankA = "\t ".toCharArray()
        withEnvelope.counting.reset()

        val snapshotA = withEnvelope.preferences.updateWebDav(
            validSettings(),
            WebDavPasswordMutation.Replace(blankA),
            cipher
        )

        assertTrue(blankA.all { it == '\u0000' })
        assertEquals(1, withEnvelope.counting.updateCalls.get())
        assertTrue(snapshotA.passwordConfigured)
        assertEquals(original, withEnvelope.envelopes.read(SecretAlias.WEB_DAV_PASSWORD))

        val blankDisabled = fixture()
        val blankB = "   ".toCharArray()
        val snapshotB = blankDisabled.preferences.updateWebDav(
            WebDavPreferences(false, "", ""),
            WebDavPasswordMutation.Replace(blankB),
            cipher
        )

        assertTrue(blankB.all { it == '\u0000' })
        assertEquals(1, blankDisabled.counting.updateCalls.get())
        assertFalse(snapshotB.passwordConfigured)
        assertNull(blankDisabled.envelopes.read(SecretAlias.WEB_DAV_PASSWORD))
        assertEquals(
            WebDavPreferences(false, "", ""),
            blankDisabled.preferences.state.first().webDav
        )
    }

    @Test
    @Suppress("LongMethod")
    fun snapshotMatchesPersistedSectionForEveryMutation() = runBlocking {
        val keep = fixture()
        val keepEnvelope = SecretEnvelope(1, "dav-iv", "dav-ciphertext")
        keep.envelopes.write(SecretAlias.WEB_DAV_PASSWORD, keepEnvelope)
        val keepSnapshot = keep.preferences.updateWebDav(
            validSettings(),
            WebDavPasswordMutation.Keep,
            C2bNoDecryptCipher()
        )
        assertEquals(
            WebDavSectionSnapshot(
                enabled = validSettings().enabled,
                url = validSettings().url,
                username = validSettings().username,
                passwordConfigured = true
            ),
            keepSnapshot
        )
        assertEquals(validSettings(), keep.preferences.state.first().webDav)
        assertTrue(keep.envelopes.presence(SecretAlias.WEB_DAV_PASSWORD).first())

        val keepDisabled = fixture()
        val displacedSnapshot = keepDisabled.preferences.updateWebDav(
            WebDavPreferences(false, "", ""),
            WebDavPasswordMutation.Keep,
            C2bNoDecryptCipher()
        )
        assertEquals(WebDavSectionSnapshot(false, "", "", false), displacedSnapshot)
        assertEquals(
            WebDavPreferences(false, "", ""),
            keepDisabled.preferences.state.first().webDav
        )
        assertFalse(keepDisabled.envelopes.presence(SecretAlias.WEB_DAV_PASSWORD).first())

        val replace = fixture()
        val replaceOnes = C2bRecordingCipher()
        val replaceSnapshot = replace.preferences.updateWebDav(
            validSettings(),
            WebDavPasswordMutation.Replace("snapshot-pass".toCharArray()),
            replaceOnes
        )
        assertEquals(
            WebDavSectionSnapshot(
                enabled = true,
                url = validSettings().url,
                username = validSettings().username,
                passwordConfigured = true
            ),
            replaceSnapshot
        )
        assertEquals(validSettings(), replace.preferences.state.first().webDav)
        assertEquals(replaceOnes.envelope, replace.envelopes.read(SecretAlias.WEB_DAV_PASSWORD))
        assertTrue(replace.envelopes.presence(SecretAlias.WEB_DAV_PASSWORD).first())

        val remove = fixture()
        remove.envelopes.write(
            SecretAlias.WEB_DAV_PASSWORD,
            SecretEnvelope(1, "dav-iv", "dav-ciphertext")
        )
        val removeDisabled =
            WebDavPreferences(false, validSettings().url, validSettings().username)
        val removeSnapshot = remove.preferences.updateWebDav(
            removeDisabled,
            WebDavPasswordMutation.Remove,
            C2bNoDecryptCipher()
        )
        assertEquals(
            WebDavSectionSnapshot(
                enabled = false,
                url = validSettings().url,
                username = validSettings().username,
                passwordConfigured = false
            ),
            removeSnapshot
        )
        assertEquals(removeDisabled, remove.preferences.state.first().webDav)
        assertFalse(remove.envelopes.presence(SecretAlias.WEB_DAV_PASSWORD).first())
        assertNull(remove.envelopes.read(SecretAlias.WEB_DAV_PASSWORD))
    }

    private fun fixture(): C2bFixture {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.filesDir, "datastore/c2b-${UUID.randomUUID()}.preferences_pb")
        files += file
        val delegate = PreferenceDataStoreFactory.create(scope = scope) { file }
        val counting = C2bCountingDataStore(delegate)
        return C2bFixture(
            preferences = DataStoreAppPreferences(counting),
            envelopes = DataStoreSecretEnvelopeStorage(counting),
            dataStore = counting,
            counting = counting
        )
    }
}

private data class C2bFixture(
    val preferences: DataStoreAppPreferences,
    val envelopes: DataStoreSecretEnvelopeStorage,
    val dataStore: DataStore<Preferences>,
    val counting: C2bCountingDataStore
)

private suspend fun C2bFixture.seedWebDav() {
    dataStore.edit { values ->
        values[stringPreferencesKey(AppPreferenceKeys.WEB_DAV_ENABLED)] =
            OLD_WEB_DAV.enabled.toString()
        values[stringPreferencesKey(AppPreferenceKeys.WEB_DAV_URL)] = OLD_WEB_DAV.url
        values[stringPreferencesKey(AppPreferenceKeys.WEB_DAV_USERNAME)] = OLD_WEB_DAV.username
    }
}

private suspend fun seedEveryUnrelatedSection(dataStore: DataStore<Preferences>) {
    dataStore.edit { values ->
        values[stringPreferencesKey(AppPreferenceKeys.THEME)] = "DARK"
        values[stringPreferencesKey(AppPreferenceKeys.APP_FONT_SIZE_SP)] = "20"
        values[stringPreferencesKey(AppPreferenceKeys.WIDGET_FONT_SIZE_SP)] = "8"
        values[stringPreferencesKey(AppPreferenceKeys.ACTIVE_CONVERSATION_ID)] = "active-fixture"
        values[stringPreferencesKey(AppPreferenceKeys.LAST_TOP_DESTINATION)] = "EVENTS"
        values[stringPreferencesKey(AppPreferenceKeys.SELECTED_DATE)] = "2026-08-31"
        values[stringPreferencesKey(AppPreferenceKeys.CALENDAR_MODE)] = "WEEK"
        values[stringPreferencesKey(AppPreferenceKeys.AI_ENDPOINT)] =
            "https://ai.example.invalid/base"
        values[stringPreferencesKey(AppPreferenceKeys.AI_MODEL)] = "fixture-model"
        values[stringPreferencesKey(AppPreferenceKeys.AI_TEMPERATURE)] = "1.25"
        values[stringPreferencesKey(AppPreferenceKeys.AI_MAX_OUTPUT_TOKENS)] = "2048"
        values[stringPreferencesKey(AppPreferenceKeys.AI_CONTEXT_WINDOW)] = "32768"
        values[stringPreferencesKey(AppPreferenceKeys.AI_THINKING_ENABLED)] = "false"
        values[stringPreferencesKey(AppPreferenceKeys.AI_THINKING_EFFORT)] = "MEDIUM"
        values[stringPreferencesKey(AppPreferenceKeys.AI_SYSTEM_PROMPT)] = "系统提示\n🌏"
        values[stringPreferencesKey(AppPreferenceKeys.AI_PERSONALITY)] = "line one\nline two 🙂"
    }
}

private fun validSettings() =
    WebDavPreferences(true, "https://dav.example.invalid/root/", "fixture-user")

private val OLD_WEB_DAV =
    WebDavPreferences(true, "https://dav.example.invalid/old/", "old-user")

private val WEB_DAV_NON_SECRET_KEYS = setOf(
    AppPreferenceKeys.WEB_DAV_ENABLED,
    AppPreferenceKeys.WEB_DAV_URL,
    AppPreferenceKeys.WEB_DAV_USERNAME
)

private val WEB_DAV_ENVELOPE_KEYS = setOf(
    "secret_envelope_web_dav_password_version",
    "secret_envelope_web_dav_password_iv",
    "secret_envelope_web_dav_password_ciphertext"
)

private class C2bCountingDataStore(private val delegate: DataStore<Preferences>) :
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

private class C2bRecordingCipher(private val failEncryption: Boolean = false) : SecretCipher {
    val encryptedAliases = mutableListOf<SecretAlias>()
    val envelope = SecretEnvelope(1, "fixture-iv", "fixture-ciphertext")

    override fun encrypt(alias: SecretAlias, plaintext: CharArray): SecretEnvelope {
        encryptedAliases += alias
        check(!failEncryption) { "fixture encryption failure" }
        return envelope
    }

    override fun decrypt(alias: SecretAlias, envelope: SecretEnvelope): SecretDecryptionResult =
        error("settings update must not decrypt")
}

private class C2bNoDecryptCipher : SecretCipher {
    var decryptCalls = 0

    override fun encrypt(alias: SecretAlias, plaintext: CharArray): SecretEnvelope =
        error("KEEP/REMOVE/blank Replace must not encrypt")

    override fun decrypt(alias: SecretAlias, envelope: SecretEnvelope): SecretDecryptionResult {
        decryptCalls += 1
        error("presence and settings update must not decrypt")
    }
}

private fun c2bAssertOnlyKeysChanged(
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
