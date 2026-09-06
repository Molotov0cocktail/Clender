package com.molotov.clender.data.settings

import java.util.Base64
import kotlin.coroutines.startCoroutine
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretStoreTest {
    @Test
    fun roundTripsEmptyUnicodeAndLongSecretsWithoutPersistingPlaintext() = runSuspend {
        val storage = MemorySecretEnvelopeStorage()
        val cipher = RecordingSecretCipher()
        val store: SecretStore = EnvelopeSecretStore(storage, cipher)

        listOf(
            charArrayOf(),
            "仅测试-🌏-密钥".toCharArray(),
            CharArray(8_192) { index -> ('a'.code + index % 26).toChar() }
        ).forEach { original ->
            val expected = original.copyOf()
            store.put(SecretAlias.AI_API_KEY, original)

            assertTrue("caller-owned input must be wiped", original.all { it == '\u0000' })
            val restored = requireNotNull(store.get(SecretAlias.AI_API_KEY))
            assertArrayEquals(expected, restored)
            restored.fill('\u0000')

            val envelope = requireNotNull(storage.values[SecretAlias.AI_API_KEY])
            assertEquals(1, envelope.version)
            assertTrue(envelope.iv.isNotBlank())
            if (expected.isNotEmpty()) {
                assertFalse(envelope.iv.contains(String(expected)))
                assertFalse(envelope.ciphertext.contains(String(expected)))
            }
        }
    }

    @Test
    fun aliasIsBoundAsAssociatedDataAndSwappedEnvelopeIsInvalidated() = runSuspend {
        val storage = MemorySecretEnvelopeStorage()
        val store: SecretStore = EnvelopeSecretStore(storage, RecordingSecretCipher())

        store.put(SecretAlias.AI_API_KEY, "alpha".toCharArray())
        storage.values[SecretAlias.WEB_DAV_PASSWORD] =
            requireNotNull(storage.values[SecretAlias.AI_API_KEY])

        assertNull(store.get(SecretAlias.WEB_DAV_PASSWORD))
        assertFalse(storage.values.containsKey(SecretAlias.WEB_DAV_PASSWORD))
        assertArrayEquals("alpha".toCharArray(), store.get(SecretAlias.AI_API_KEY))
    }

    @Test
    fun invalidatedKeyBadTagAndBadEnvelopeDeleteOnlyAffectedCiphertext() = runSuspend {
        val storage = MemorySecretEnvelopeStorage()
        val cipher = RecordingSecretCipher()
        val store: SecretStore = EnvelopeSecretStore(storage, cipher)
        store.put(SecretAlias.AI_API_KEY, "one".toCharArray())
        store.put(SecretAlias.WEB_DAV_PASSWORD, "two".toCharArray())

        listOf(
            SecretDecryptionResult.KeyInvalidated,
            SecretDecryptionResult.AuthenticationFailed,
            SecretDecryptionResult.InvalidEnvelope
        ).forEach { failure ->
            storage.values[SecretAlias.AI_API_KEY] = SecretEnvelope(
                version = 1,
                iv = "not-private-test-iv",
                ciphertext = "not-private-test-ciphertext"
            )
            cipher.nextDecryption = failure

            assertNull(store.get(SecretAlias.AI_API_KEY))
            assertFalse(storage.values.containsKey(SecretAlias.AI_API_KEY))
            assertTrue(storage.values.containsKey(SecretAlias.WEB_DAV_PASSWORD))
        }
    }

    @Test
    fun putWipesInputEvenWhenEncryptionFailsAndDeleteIsIdempotent() {
        val storage = MemorySecretEnvelopeStorage()
        val cipher = RecordingSecretCipher().apply { failEncryption = true }
        val store: SecretStore = EnvelopeSecretStore(storage, cipher)
        val value = "wipe-me".toCharArray()

        assertThrows(IllegalStateException::class.java) {
            runSuspend { store.put(SecretAlias.AI_API_KEY, value) }
        }

        assertTrue(value.all { it == '\u0000' })
        assertTrue(storage.values.isEmpty())
        runSuspend {
            store.delete(SecretAlias.AI_API_KEY)
            store.delete(SecretAlias.AI_API_KEY)
        }
        assertEquals(2, storage.deleteCalls)
    }

    @Test
    fun putWipesInputWhenEnvelopePersistenceFails() {
        val storage = MemorySecretEnvelopeStorage().apply { failWrites = true }
        val store: SecretStore = EnvelopeSecretStore(storage, RecordingSecretCipher())
        val value = "wipe-on-storage-failure".toCharArray()

        assertThrows(IllegalStateException::class.java) {
            runSuspend { store.put(SecretAlias.AI_API_KEY, value) }
        }

        assertTrue(value.all { it == '\u0000' })
        assertTrue(storage.values.isEmpty())
    }
}

private class MemorySecretEnvelopeStorage : SecretEnvelopeStorage {
    val values = linkedMapOf<SecretAlias, SecretEnvelope>()
    var deleteCalls = 0
    var failWrites = false

    override suspend fun read(alias: SecretAlias): SecretEnvelope? = values[alias]

    override suspend fun write(alias: SecretAlias, envelope: SecretEnvelope) {
        check(!failWrites) { "test storage failure" }
        values[alias] = envelope
    }

    override suspend fun delete(alias: SecretAlias) {
        deleteCalls += 1
        values.remove(alias)
    }
}

private class RecordingSecretCipher : SecretCipher {
    var nextDecryption: SecretDecryptionResult? = null
    var failEncryption = false

    override fun encrypt(alias: SecretAlias, plaintext: CharArray): SecretEnvelope {
        check(!failEncryption) { "test encryption failure" }
        val bytes = String(plaintext).toByteArray(Charsets.UTF_8)
        return SecretEnvelope(
            version = 1,
            iv = Base64.getEncoder().encodeToString(alias.name.toByteArray(Charsets.UTF_8)),
            ciphertext = Base64.getEncoder().encodeToString(bytes)
        )
    }

    override fun decrypt(alias: SecretAlias, envelope: SecretEnvelope): SecretDecryptionResult {
        val configured = nextDecryption
        return if (configured != null) {
            nextDecryption = null
            configured
        } else {
            val encodedAlias = Base64.getDecoder().decode(envelope.iv).toString(Charsets.UTF_8)
            if (encodedAlias != alias.name || envelope.version != 1) {
                SecretDecryptionResult.AuthenticationFailed
            } else {
                SecretDecryptionResult.Success(
                    Base64.getDecoder()
                        .decode(envelope.ciphertext)
                        .toString(Charsets.UTF_8)
                        .toCharArray()
                )
            }
        }
    }
}

private fun <T> runSuspend(block: suspend () -> T): T {
    var outcome: Result<T>? = null
    block.startCoroutine(
        object : kotlin.coroutines.Continuation<T> {
            override val context = kotlin.coroutines.EmptyCoroutineContext

            override fun resumeWith(result: Result<T>) {
                outcome = result
            }
        }
    )
    return requireNotNull(outcome) { "test block suspended unexpectedly" }.getOrThrow()
}
