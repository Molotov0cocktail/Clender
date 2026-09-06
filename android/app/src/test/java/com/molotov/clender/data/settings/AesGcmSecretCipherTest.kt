package com.molotov.clender.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.Base64
import java.util.UUID
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class AesGcmSecretCipherTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val files = mutableListOf<File>()
    private val keyProvider = FixedSecretKeyProvider(
        SecretKeySpec(ByteArray(32) { index -> (index + 1).toByte() }, "AES")
    )
    private val cipher = AesGcmSecretCipher(
        applicationId = "com.molotov.clender",
        keyProvider = keyProvider
    )

    @After
    fun tearDown() {
        scope.cancel()
        files.forEach(File::delete)
    }

    @Test
    fun realAes256GcmUsesVersionedTwelveByteIvAnd128BitTag() {
        listOf(
            charArrayOf(),
            "测试-🌏".toCharArray(),
            CharArray(8_192) { index -> ('a'.code + index % 26).toChar() }
        ).forEach { plaintext ->
            val expected = plaintext.copyOf()

            val envelope = cipher.encrypt(SecretAlias.AI_API_KEY, plaintext)

            assertEquals(1, envelope.version)
            assertEquals(12, Base64.getDecoder().decode(envelope.iv).size)
            assertEquals(
                String(expected).toByteArray(Charsets.UTF_8).size + 16,
                Base64.getDecoder().decode(envelope.ciphertext).size
            )
            val result = cipher.decrypt(SecretAlias.AI_API_KEY, envelope)
            assertTrue(result is SecretDecryptionResult.Success)
            assertArrayEquals(
                expected,
                (result as SecretDecryptionResult.Success).value
            )
            if (expected.isNotEmpty()) {
                assertFalse(envelope.ciphertext.contains(String(expected)))
            }
        }
        assertEquals(32, keyProvider.key.encoded.size)
    }

    @Test
    fun applicationAliasAndEnvelopeVersionAreAuthenticatedAssociatedData() {
        val envelope = cipher.encrypt(SecretAlias.AI_API_KEY, "bound".toCharArray())
        val secondEnvelope = cipher.encrypt(SecretAlias.AI_API_KEY, "bound".toCharArray())

        assertFalse(envelope.iv == secondEnvelope.iv)
        assertEquals(
            SecretDecryptionResult.AuthenticationFailed,
            cipher.decrypt(SecretAlias.WEB_DAV_PASSWORD, envelope)
        )
        assertEquals(
            SecretDecryptionResult.InvalidEnvelope,
            cipher.decrypt(SecretAlias.AI_API_KEY, envelope.copy(version = 2))
        )
        val otherApplication = AesGcmSecretCipher("com.example.other", keyProvider)
        assertEquals(
            SecretDecryptionResult.AuthenticationFailed,
            otherApplication.decrypt(SecretAlias.AI_API_KEY, envelope)
        )
    }

    @Test
    fun tamperedIvAndCiphertextFailAuthenticationWithoutPlaintextInFailure() {
        val envelope = cipher.encrypt(SecretAlias.AI_API_KEY, "tamper-target".toCharArray())
        val tamperedCiphertext = Base64.getDecoder().decode(envelope.ciphertext).apply {
            this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte()
        }
        val tamperedIv = Base64.getDecoder().decode(envelope.iv).apply {
            this[0] = (this[0].toInt() xor 1).toByte()
        }

        listOf(
            envelope.copy(ciphertext = Base64.getEncoder().encodeToString(tamperedCiphertext)),
            envelope.copy(iv = Base64.getEncoder().encodeToString(tamperedIv))
        ).forEach { broken ->
            assertEquals(
                SecretDecryptionResult.AuthenticationFailed,
                cipher.decrypt(SecretAlias.AI_API_KEY, broken)
            )
        }
    }

    @Test
    fun malformedBase64WrongIvLengthAndShortCiphertextAreSafeInvalidEnvelopes() {
        val valid = cipher.encrypt(SecretAlias.AI_API_KEY, "shape".toCharArray())
        val invalid = listOf(
            valid.copy(iv = "%not-base64%"),
            valid.copy(iv = Base64.getEncoder().encodeToString(ByteArray(11))),
            valid.copy(ciphertext = "%not-base64%"),
            valid.copy(ciphertext = Base64.getEncoder().encodeToString(ByteArray(15)))
        )

        invalid.forEach { envelope ->
            assertEquals(
                SecretDecryptionResult.InvalidEnvelope,
                cipher.decrypt(SecretAlias.AI_API_KEY, envelope)
            )
        }
    }

    @Test
    fun keyProviderInvalidationMapsToRestrictedKeyInvalidatedResult() {
        val envelope = cipher.encrypt(SecretAlias.AI_API_KEY, "value".toCharArray())
        val invalidatedCipher = AesGcmSecretCipher(
            applicationId = "com.molotov.clender",
            keyProvider = InvalidatedSecretKeyProvider()
        )

        assertEquals(
            SecretDecryptionResult.KeyInvalidated,
            invalidatedCipher.decrypt(SecretAlias.AI_API_KEY, envelope)
        )
    }

    @Test
    fun androidKeyStoreAdapterRequestsPinnedProviderAndAesGcmPolicy() {
        val backend = RecordingAndroidKeyStoreBackend(keyProvider.key)
        val provider = AndroidKeyStoreSecretKeyProvider(backend)

        assertEquals(keyProvider.key, provider.getOrCreate(SecretAlias.AI_API_KEY))
        assertEquals(keyProvider.key, provider.getOrCreate(SecretAlias.WEB_DAV_PASSWORD))
        provider.delete(SecretAlias.AI_API_KEY)
        assertEquals(
            listOf(SecretAlias.AI_API_KEY.keyAlias, SecretAlias.WEB_DAV_PASSWORD.keyAlias),
            backend.aliases
        )
        assertEquals(listOf(SecretAlias.AI_API_KEY.keyAlias), backend.deletedAliases)
        backend.policies.forEach { policy ->
            assertEquals("AndroidKeyStore", policy.providerName)
            assertEquals("AES", policy.keyAlgorithm)
            assertEquals("GCM", policy.blockMode)
            assertEquals("NoPadding", policy.padding)
            assertEquals(256, policy.keySizeBits)
            assertTrue(policy.randomizedEncryptionRequired)
            assertFalse(policy.userAuthenticationRequired)
        }
    }

    @Test
    fun dataStoreEnvelopeStoragePersistsOnlyVersionIvAndCiphertextPerAlias() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = File(
            context.filesDir,
            "datastore/secret-${UUID.randomUUID()}.preferences_pb"
        )
        files += file
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) { file }
        val storage: SecretEnvelopeStorage = DataStoreSecretEnvelopeStorage(dataStore)
        val ai = SecretEnvelope(1, "iv-ai", "cipher-ai")
        val webDav = SecretEnvelope(1, "iv-webdav", "cipher-webdav")

        storage.write(SecretAlias.AI_API_KEY, ai)
        storage.write(SecretAlias.WEB_DAV_PASSWORD, webDav)

        assertEquals(ai, storage.read(SecretAlias.AI_API_KEY))
        assertEquals(webDav, storage.read(SecretAlias.WEB_DAV_PASSWORD))
        val raw = dataStore.data.first().asMap()
        assertTrue(raw.size >= 2)
        assertTrue(raw.keys.all { it.name.contains("secret") || it.name.contains("envelope") })
        assertFalse(raw.values.any { it.toString().contains("plaintext") })

        storage.delete(SecretAlias.AI_API_KEY)
        assertEquals(null, storage.read(SecretAlias.AI_API_KEY))
        assertEquals(webDav, storage.read(SecretAlias.WEB_DAV_PASSWORD))
    }

    @Test
    fun partialPersistedEnvelopeIsDeletedAsInvalidWithoutAffectingOtherAlias() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = File(
            context.filesDir,
            "datastore/partial-${UUID.randomUUID()}.preferences_pb"
        )
        files += file
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) { file }
        val storage = DataStoreSecretEnvelopeStorage(dataStore)
        val other = SecretEnvelope(1, "other-iv", "other-cipher")
        storage.write(SecretAlias.AI_API_KEY, SecretEnvelope(1, "iv", "cipher"))
        storage.write(SecretAlias.WEB_DAV_PASSWORD, other)
        dataStore.edit { values ->
            values.remove(stringPreferencesKey("secret_envelope_ai_api_key_ciphertext"))
        }

        assertEquals(null, storage.read(SecretAlias.AI_API_KEY))
        assertEquals(other, storage.read(SecretAlias.WEB_DAV_PASSWORD))
        assertFalse(
            dataStore.data.first().asMap().keys.any { it.name.contains("ai_api_key") }
        )
    }

    @Test
    fun invalidatedAliasIsDeletedThenCanRecreateKeyWithoutAffectingOtherSecret() = runBlocking {
        val storage = MemoryAesEnvelopeStorage()
        val rebuiltKey = SecretKeySpec(ByteArray(32) { index -> (index + 11).toByte() }, "AES")
        val healthyCipher = AesGcmSecretCipher(
            "com.molotov.clender",
            FixedSecretKeyProvider(rebuiltKey)
        )
        storage.values[SecretAlias.AI_API_KEY] =
            healthyCipher.encrypt(SecretAlias.AI_API_KEY, "old".toCharArray())
        storage.values[SecretAlias.WEB_DAV_PASSWORD] =
            healthyCipher.encrypt(SecretAlias.WEB_DAV_PASSWORD, "webdav".toCharArray())
        val provider = RebuildingSecretKeyProvider(rebuiltKey, SecretAlias.AI_API_KEY)
        val store = EnvelopeSecretStore(
            storage,
            AesGcmSecretCipher("com.molotov.clender", provider)
        )

        assertEquals(null, store.get(SecretAlias.AI_API_KEY))
        assertEquals(listOf(SecretAlias.AI_API_KEY), provider.deletedAliases)
        assertTrue(storage.values.containsKey(SecretAlias.WEB_DAV_PASSWORD))

        store.put(SecretAlias.AI_API_KEY, "replacement".toCharArray())
        assertArrayEquals("replacement".toCharArray(), store.get(SecretAlias.AI_API_KEY))
        assertArrayEquals("webdav".toCharArray(), store.get(SecretAlias.WEB_DAV_PASSWORD))
    }
}

private class FixedSecretKeyProvider(val key: SecretKey) : SecretKeyProvider {
    override fun getOrCreate(alias: SecretAlias): SecretKey = key
}

private class InvalidatedSecretKeyProvider : SecretKeyProvider {
    override fun getOrCreate(alias: SecretAlias): SecretKey = throw SecretKeyInvalidatedException()
}

private class RecordingAndroidKeyStoreBackend(private val key: SecretKey) : AndroidKeyStoreBackend {
    val aliases = mutableListOf<String>()
    val policies = mutableListOf<AndroidKeyStoreKeyPolicy>()
    val deletedAliases = mutableListOf<String>()

    override fun loadOrGenerate(alias: String, policy: AndroidKeyStoreKeyPolicy): SecretKey {
        aliases += alias
        policies += policy
        return key
    }

    override fun delete(alias: String, policy: AndroidKeyStoreKeyPolicy) {
        deletedAliases += alias
        policies += policy
    }
}

private class RebuildingSecretKeyProvider(
    private val rebuiltKey: SecretKey,
    invalidatedAlias: SecretAlias
) : SecretKeyProvider {
    private val invalidatedAliases = mutableSetOf(invalidatedAlias)
    val deletedAliases = mutableListOf<SecretAlias>()

    override fun getOrCreate(alias: SecretAlias): SecretKey {
        if (alias in invalidatedAliases) throw SecretKeyInvalidatedException()
        return rebuiltKey
    }

    override fun delete(alias: SecretAlias) {
        deletedAliases += alias
        invalidatedAliases.remove(alias)
    }
}

private class MemoryAesEnvelopeStorage : SecretEnvelopeStorage {
    val values = linkedMapOf<SecretAlias, SecretEnvelope>()

    override suspend fun read(alias: SecretAlias): SecretEnvelope? = values[alias]

    override suspend fun write(alias: SecretAlias, envelope: SecretEnvelope) {
        values[alias] = envelope
    }

    override suspend fun delete(alias: SecretAlias) {
        values.remove(alias)
    }
}
