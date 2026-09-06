package com.molotov.clender.data.settings

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.security.InvalidAlgorithmParameterException
import java.security.Security
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class ApiKeyRandomizedEncryptionPersistenceTest {
    private val provider = AndroidKeyStoreSecretKeyProvider(RandomizedPolicyBackend())
    private val cipher = AesGcmSecretCipher(APP_ID, provider)
    private val jobs = mutableListOf<Job>()
    private val files = mutableListOf<File>()

    @Before
    fun installPolicyProvider() {
        assertTrue(Security.insertProviderAt(RandomizedPolicyProvider(), 1) > 0)
    }

    @After
    fun releasePolicyAndStores() = runBlocking {
        try {
            jobs.forEach { it.cancelAndJoin() }
            files.forEach { assertTrue(!it.exists() || it.delete()) }
        } finally {
            Security.removeProvider(RandomizedPolicyProvider.NAME)
        }
    }

    @Test
    fun policyBackendRejectsCallerIvButAllowsProviderGeneratedIv() {
        val key = provider.getOrCreate(SecretAlias.AI_API_KEY)
        assertNull(key.encoded)
        val rejected = Cipher.getInstance("AES/GCM/NoPadding", RandomizedPolicyProvider.NAME)
        assertThrows(InvalidAlgorithmParameterException::class.java) {
            rejected.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, ByteArray(12)))
        }
        val accepted = Cipher.getInstance("AES/GCM/NoPadding", RandomizedPolicyProvider.NAME)
        accepted.init(Cipher.ENCRYPT_MODE, key)
        assertEquals(12, accepted.iv.size)
    }

    @Test
    fun productionCipherEncryptsBothAliasesWithRandomizedPolicyAndFreshIvs() {
        SecretAlias.entries.forEach { alias ->
            listOf(0, 32, 8192).forEach { length ->
                val secret = syntheticSecret(length)
                try {
                    val first = cipher.encrypt(alias, secret)
                    val second = cipher.encrypt(alias, secret)
                    assertEquals(1, first.version)
                    assertEquals(12, Base64.getDecoder().decode(first.iv).size)
                    assertEquals(
                        String(secret).toByteArray(Charsets.UTF_8).size + 16,
                        Base64.getDecoder().decode(first.ciphertext).size
                    )
                    assertNotEquals(first.iv, second.iv)
                    assertSecretRoundTrip(alias, first, secret)
                    assertSecretRoundTrip(alias, second, secret)
                } finally {
                    secret.fill('\u0000')
                }
            }
        }
    }

    @Test
    fun replacementSurvivesDataStoreReopenAndKeepThenRemovePreserveContracts() = runBlocking {
        val file = newFile()
        val firstJob = SupervisorJob().also(jobs::add)
        val firstStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(firstJob + Dispatchers.IO),
            produceFile = { file }
        )
        val preferences = DataStoreAppPreferences(firstStore)
        val input = syntheticSecret(32)
        val expected = input.copyOf()
        try {
            preferences.updateAi(configured(), ApiKeyMutation.Replace(input), cipher)
            assertTrue(input.all { it == '\u0000' })
            val original = requireNotNull(
                DataStoreSecretEnvelopeStorage(firstStore).read(SecretAlias.AI_API_KEY)
            )
            firstJob.cancelAndJoin()

            val secondJob = SupervisorJob().also(jobs::add)
            val reopened = PreferenceDataStoreFactory.create(
                scope = CoroutineScope(secondJob + Dispatchers.IO),
                produceFile = { file }
            )
            val storage = DataStoreSecretEnvelopeStorage(reopened)
            val saved = requireNotNull(storage.read(SecretAlias.AI_API_KEY))
            assertTrue(saved == original)
            assertTrue(storage.isConfigured(SecretAlias.AI_API_KEY))
            assertSecretRoundTrip(SecretAlias.AI_API_KEY, saved, expected)
            val restored = DataStoreAppPreferences(reopened)
            assertEquals(configured(), restored.state.first().ai)
            restored.updateAi(
                configured().copy(model = "another-model"),
                ApiKeyMutation.Keep,
                cipher
            )
            assertTrue(saved == storage.read(SecretAlias.AI_API_KEY))
            restored.updateAi(configured(), ApiKeyMutation.Remove, cipher)
            assertFalse(storage.isConfigured(SecretAlias.AI_API_KEY))
        } finally {
            input.fill('\u0000')
            expected.fill('\u0000')
        }
    }

    @Test
    fun encryptionFailurePreservesPreviouslyCommittedSettingsAndEnvelope() = runBlocking {
        val store = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(SupervisorJob().also(jobs::add) + Dispatchers.IO),
            produceFile = { newFile() }
        )
        val preferences = DataStoreAppPreferences(store)
        val originalInput = syntheticSecret(24)
        preferences.updateAi(configured(), ApiKeyMutation.Replace(originalInput), cipher)
        assertTrue(originalInput.all { it == '\u0000' })
        val storage = DataStoreSecretEnvelopeStorage(store)
        val originalEnvelope = requireNotNull(storage.read(SecretAlias.AI_API_KEY))
        val originalExpected = syntheticSecret(24)
        try {
            assertSecretRoundTrip(SecretAlias.AI_API_KEY, originalEnvelope, originalExpected)
        } finally {
            originalExpected.fill('\u0000')
        }
        val original = store.data.first()
        val input = syntheticSecret(32)
        val failing = object : SecretCipher {
            override fun encrypt(alias: SecretAlias, plaintext: CharArray): SecretEnvelope =
                error("Synthetic encryption rejection")

            override fun decrypt(
                alias: SecretAlias,
                envelope: SecretEnvelope
            ): SecretDecryptionResult = error("Unexpected decryption")
        }
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                preferences.updateAi(
                    configured().copy(model = "not-committed"),
                    ApiKeyMutation.Replace(input),
                    failing
                )
            }
        }
        assertTrue(input.all { it == '\u0000' })
        assertTrue(original == store.data.first())
        val retained = requireNotNull(storage.read(SecretAlias.AI_API_KEY))
        assertTrue(originalEnvelope.version == retained.version)
        assertTrue(originalEnvelope.iv == retained.iv)
        assertTrue(originalEnvelope.ciphertext == retained.ciphertext)
        val expected = syntheticSecret(24)
        try {
            assertSecretRoundTrip(SecretAlias.AI_API_KEY, retained, expected)
        } finally {
            expected.fill('\u0000')
        }
    }

    @Test
    fun existingVersionOneCallerIvEnvelopeRemainsReadableAndAliasBound() {
        val alias = SecretAlias.AI_API_KEY
        val key = provider.getOrCreate(alias) as PolicySecretKey
        val software = Cipher.getInstance(
            "AES/GCM/NoPadding",
            Security.getProviders().first {
                it.name != RandomizedPolicyProvider.NAME &&
                    it.getService("Cipher", "AES/GCM/NoPadding") != null
            }
        )
        val input = syntheticSecret(32)
        val bytes = String(input).toByteArray(Charsets.UTF_8)
        try {
            software.init(Cipher.ENCRYPT_MODE, key.delegate, GCMParameterSpec(128, ByteArray(12)))
            software.updateAAD("$APP_ID\u0000${alias.keyAlias}\u00001".toByteArray(Charsets.UTF_8))
            val envelope = SecretEnvelope(
                1,
                Base64.getEncoder().encodeToString(software.iv),
                Base64.getEncoder().encodeToString(software.doFinal(bytes))
            )
            assertSecretRoundTrip(alias, envelope, input)
            assertTrue(
                cipher.decrypt(
                    SecretAlias.WEB_DAV_PASSWORD,
                    envelope
                ) !is SecretDecryptionResult.Success
            )
        } finally {
            bytes.fill(0)
            input.fill('\u0000')
        }
    }

    private fun assertSecretRoundTrip(
        alias: SecretAlias,
        envelope: SecretEnvelope,
        expected: CharArray
    ) {
        val result = cipher.decrypt(alias, envelope)
        assertTrue(
            "Encrypted synthetic value must be recoverable",
            result is SecretDecryptionResult.Success
        )
        val restored = (result as SecretDecryptionResult.Success).value
        try {
            assertTrue("Synthetic value mismatch", expected.contentEquals(restored))
        } finally {
            restored.fill('\u0000')
        }
    }

    private fun newFile(): File {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return File(context.cacheDir, "t49-${UUID.randomUUID()}.preferences_pb").also(files::add)
    }

    private fun configured() = AppPreferencesState.DEFAULT.ai.copy(
        endpoint = "https://example.invalid/v1",
        model = "test-model"
    )

    private fun syntheticSecret(length: Int): CharArray =
        CharArray(length) { if (it % 2 == 0) '测' else ('a'.code + it % 26).toChar() }

    private companion object {
        const val APP_ID = "com.molotov.clender"
    }
}
