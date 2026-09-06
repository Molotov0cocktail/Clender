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
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class C2bWebDavSecretPresenceFlowTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val files = mutableListOf<File>()

    @After
    fun tearDown() {
        scope.cancel()
        files.forEach(File::delete)
    }

    @Test
    fun presenceIsFalseWhenAbsentAndOnlyTrueOnceAllThreeEnvelopeFieldsExist() = runBlocking {
        val fixture = fixture()
        val storage = DataStoreSecretEnvelopeStorage(fixture.dataStore)

        assertFalse(storage.presence(SecretAlias.WEB_DAV_PASSWORD).first())

        fixture.dataStore.edit { values ->
            values[intPreferencesKey("secret_envelope_web_dav_password_version")] = 1
        }
        assertFalse(storage.presence(SecretAlias.WEB_DAV_PASSWORD).first())

        fixture.dataStore.edit { values ->
            values[stringPreferencesKey("secret_envelope_web_dav_password_iv")] = "partial-iv"
        }
        assertFalse(storage.presence(SecretAlias.WEB_DAV_PASSWORD).first())

        fixture.dataStore.edit { values ->
            values[stringPreferencesKey("secret_envelope_web_dav_password_ciphertext")] =
                "partial-ciphertext"
        }
        assertTrue(storage.presence(SecretAlias.WEB_DAV_PASSWORD).first())
    }

    @Test
    fun incompleteEnvelopeFailsClosedWithZeroDecryptAndReadReturnsNull() = runBlocking {
        val cipher = C2bPresenceNoDecryptCipher()
        val twoFields = fixture()
        val storage = DataStoreSecretEnvelopeStorage(twoFields.dataStore)
        twoFields.dataStore.edit { values ->
            values[intPreferencesKey("secret_envelope_web_dav_password_version")] = 1
            values[stringPreferencesKey("secret_envelope_web_dav_password_ciphertext")] = "ct-only"
        }

        assertFalse(storage.presence(SecretAlias.WEB_DAV_PASSWORD).first())
        assertFalse(storage.isConfigured(SecretAlias.WEB_DAV_PASSWORD))
        assertNull(storage.read(SecretAlias.WEB_DAV_PASSWORD))
        assertEquals(0, cipher.decryptCalls)
    }

    @Test
    fun presenceTracksWriteAndDeleteWithoutDecrypting() = runBlocking {
        val fixture = fixture()
        val storage = DataStoreSecretEnvelopeStorage(fixture.dataStore)
        val cipher = C2bPresenceNoDecryptCipher()

        storage.write(
            SecretAlias.WEB_DAV_PASSWORD,
            SecretEnvelope(1, "dav-iv", "dav-ciphertext")
        )
        assertTrue(storage.presence(SecretAlias.WEB_DAV_PASSWORD).first())

        storage.delete(SecretAlias.WEB_DAV_PASSWORD)
        assertFalse(storage.presence(SecretAlias.WEB_DAV_PASSWORD).first())

        storage.write(
            SecretAlias.WEB_DAV_PASSWORD,
            SecretEnvelope(2, "again-iv", "again-ciphertext")
        )
        assertTrue(storage.presence(SecretAlias.WEB_DAV_PASSWORD).first())
        assertEquals(0, cipher.decryptCalls)
    }

    @Test
    fun corruptCompleteEnvelopeStaysConfiguredWithoutDecryptOrRawSecretExposure() = runBlocking {
        val fixture = fixture()
        val storage = DataStoreSecretEnvelopeStorage(fixture.dataStore)
        val cipher = C2bPresenceNoDecryptCipher()
        fixture.dataStore.edit { values ->
            values[intPreferencesKey("secret_envelope_web_dav_password_version")] = 1
            values[stringPreferencesKey("secret_envelope_web_dav_password_iv")] = "%%%"
            values[stringPreferencesKey("secret_envelope_web_dav_password_ciphertext")] =
                "%%%%"
        }

        assertTrue(storage.presence(SecretAlias.WEB_DAV_PASSWORD).first())
        assertTrue(storage.isConfigured(SecretAlias.WEB_DAV_PASSWORD))
        assertEquals("", fixture.preferences.state.first().webDav.url)
        assertFalse(
            fixture.preferences.state.first().toString()
                .contains("ciphertext", ignoreCase = true)
        )
        assertEquals(0, cipher.decryptCalls)
    }

    private fun fixture(): C2bPresenceFixture {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(
            context.filesDir,
            "datastore/c2b-presence-${UUID.randomUUID()}.preferences_pb"
        )
        files += file
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) { file }
        return C2bPresenceFixture(
            preferences = DataStoreAppPreferences(dataStore),
            dataStore = dataStore
        )
    }
}

private data class C2bPresenceFixture(
    val preferences: DataStoreAppPreferences,
    val dataStore: DataStore<Preferences>
)

private class C2bPresenceNoDecryptCipher : SecretCipher {
    var decryptCalls = 0

    override fun encrypt(alias: SecretAlias, plaintext: CharArray): SecretEnvelope =
        error("presence must not encrypt")

    override fun decrypt(alias: SecretAlias, envelope: SecretEnvelope): SecretDecryptionResult {
        decryptCalls += 1
        error("presence must not decrypt")
    }
}
