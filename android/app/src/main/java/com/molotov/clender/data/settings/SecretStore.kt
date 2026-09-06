package com.molotov.clender.data.settings

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class SecretAlias(val keyAlias: String) {
    AI_API_KEY("clender.ai.api-key"),
    WEB_DAV_PASSWORD("clender.webdav.password")
}

data class SecretEnvelope(val version: Int, val iv: String, val ciphertext: String)

sealed interface SecretDecryptionResult {
    data class Success(val value: CharArray) : SecretDecryptionResult

    data object KeyInvalidated : SecretDecryptionResult

    data object AuthenticationFailed : SecretDecryptionResult

    data object InvalidEnvelope : SecretDecryptionResult
}

interface SecretCipher {
    fun encrypt(alias: SecretAlias, plaintext: CharArray): SecretEnvelope

    fun decrypt(alias: SecretAlias, envelope: SecretEnvelope): SecretDecryptionResult
}

interface SecretKeyProvider {
    fun getOrCreate(alias: SecretAlias): SecretKey

    fun delete(alias: SecretAlias) = Unit
}

class SecretKeyInvalidatedException(cause: Throwable? = null) :
    RuntimeException("The encrypted secret key is no longer usable", cause)

data class AndroidKeyStoreKeyPolicy(
    val providerName: String = "AndroidKeyStore",
    val keyAlgorithm: String = KeyProperties.KEY_ALGORITHM_AES,
    val blockMode: String = KeyProperties.BLOCK_MODE_GCM,
    val padding: String = KeyProperties.ENCRYPTION_PADDING_NONE,
    val keySizeBits: Int = 256,
    val randomizedEncryptionRequired: Boolean = true,
    val userAuthenticationRequired: Boolean = false
)

interface AndroidKeyStoreBackend {
    fun loadOrGenerate(alias: String, policy: AndroidKeyStoreKeyPolicy): SecretKey

    fun delete(alias: String, policy: AndroidKeyStoreKeyPolicy)
}

class AndroidKeyStoreSecretKeyProvider(
    private val backend: AndroidKeyStoreBackend = PlatformAndroidKeyStoreBackend(),
    private val policy: AndroidKeyStoreKeyPolicy = AndroidKeyStoreKeyPolicy()
) : SecretKeyProvider {
    override fun getOrCreate(alias: SecretAlias): SecretKey = try {
        backend.loadOrGenerate(alias.keyAlias, policy)
    } catch (error: KeyPermanentlyInvalidatedException) {
        throw SecretKeyInvalidatedException(error)
    }

    override fun delete(alias: SecretAlias) {
        backend.delete(alias.keyAlias, policy)
    }
}

class PlatformAndroidKeyStoreBackend : AndroidKeyStoreBackend {
    override fun loadOrGenerate(alias: String, policy: AndroidKeyStoreKeyPolicy): SecretKey {
        val keyStore = KeyStore.getInstance(policy.providerName).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(policy.keyAlgorithm, policy.providerName)
        val purposes = KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        val specification = KeyGenParameterSpec.Builder(alias, purposes)
            .setBlockModes(policy.blockMode)
            .setEncryptionPaddings(policy.padding)
            .setKeySize(policy.keySizeBits)
            .setRandomizedEncryptionRequired(policy.randomizedEncryptionRequired)
            .setUserAuthenticationRequired(policy.userAuthenticationRequired)
            .build()
        generator.init(specification)
        return generator.generateKey()
    }

    override fun delete(alias: String, policy: AndroidKeyStoreKeyPolicy) {
        KeyStore.getInstance(policy.providerName).apply {
            load(null)
            deleteEntry(alias)
        }
    }
}

class AesGcmSecretCipher(
    private val applicationId: String,
    private val keyProvider: SecretKeyProvider,
    private val secureRandom: SecureRandom = SecureRandom()
) : SecretCipher {
    init {
        require(applicationId.isNotBlank()) { "Application ID cannot be blank" }
    }

    override fun encrypt(alias: SecretAlias, plaintext: CharArray): SecretEnvelope {
        val bytes = plaintext.toUtf8Bytes()
        var iv = ByteArray(0)
        var encrypted: ByteArray? = null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.ENCRYPT_MODE,
                keyProvider.getOrCreate(alias),
                secureRandom
            )
            iv = cipher.iv
            cipher.updateAAD(associatedData(alias, ENVELOPE_VERSION))
            encrypted = cipher.doFinal(bytes)
            SecretEnvelope(
                version = ENVELOPE_VERSION,
                iv = Base64.getEncoder().encodeToString(iv),
                ciphertext = Base64.getEncoder().encodeToString(encrypted)
            )
        } catch (error: SecretKeyInvalidatedException) {
            deleteInvalidatedKey(alias)
            throw error
        } catch (error: KeyPermanentlyInvalidatedException) {
            deleteInvalidatedKey(alias)
            throw SecretKeyInvalidatedException(error)
        } catch (error: GeneralSecurityException) {
            throw IllegalStateException("Secret encryption failed", error)
        } finally {
            bytes.fill(0)
            iv.fill(0)
            encrypted?.fill(0)
        }
    }

    @Suppress("ReturnCount", "SwallowedException")
    override fun decrypt(alias: SecretAlias, envelope: SecretEnvelope): SecretDecryptionResult {
        if (envelope.version != ENVELOPE_VERSION) return SecretDecryptionResult.InvalidEnvelope
        val iv = envelope.iv.decodeBase64OrNull() ?: return SecretDecryptionResult.InvalidEnvelope
        val encrypted = envelope.ciphertext.decodeBase64OrNull()
            ?: return SecretDecryptionResult.InvalidEnvelope.also { iv.fill(0) }
        if (iv.size != IV_BYTES || encrypted.size < TAG_BYTES) {
            iv.fill(0)
            encrypted.fill(0)
            return SecretDecryptionResult.InvalidEnvelope
        }
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                keyProvider.getOrCreate(alias),
                GCMParameterSpec(TAG_BITS, iv)
            )
            cipher.updateAAD(associatedData(alias, envelope.version))
            val plaintext = cipher.doFinal(encrypted)
            try {
                SecretDecryptionResult.Success(plaintext.fromUtf8Bytes())
            } finally {
                plaintext.fill(0)
            }
        } catch (_: SecretKeyInvalidatedException) {
            deleteInvalidatedKey(alias)
            SecretDecryptionResult.KeyInvalidated
        } catch (_: KeyPermanentlyInvalidatedException) {
            deleteInvalidatedKey(alias)
            SecretDecryptionResult.KeyInvalidated
        } catch (_: AEADBadTagException) {
            SecretDecryptionResult.AuthenticationFailed
        } catch (_: GeneralSecurityException) {
            SecretDecryptionResult.InvalidEnvelope
        } catch (_: CharacterCodingException) {
            SecretDecryptionResult.InvalidEnvelope
        } catch (_: IllegalArgumentException) {
            SecretDecryptionResult.InvalidEnvelope
        } finally {
            iv.fill(0)
            encrypted.fill(0)
        }
    }

    private fun associatedData(alias: SecretAlias, version: Int): ByteArray =
        "$applicationId\u0000${alias.keyAlias}\u0000$version".toByteArray(Charsets.UTF_8)

    @Suppress("SwallowedException")
    private fun deleteInvalidatedKey(alias: SecretAlias) {
        try {
            keyProvider.delete(alias)
        } catch (_: RuntimeException) {
            // SecretStore still removes the envelope; re-entry can retry alias cleanup.
        }
    }

    private companion object {
        const val ENVELOPE_VERSION = 1
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val TAG_BYTES = TAG_BITS / 8
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

interface SecretEnvelopeStorage {
    suspend fun read(alias: SecretAlias): SecretEnvelope?

    suspend fun write(alias: SecretAlias, envelope: SecretEnvelope)

    suspend fun delete(alias: SecretAlias)
}

class DataStoreSecretEnvelopeStorage(private val dataStore: DataStore<Preferences>) :
    SecretEnvelopeStorage {
    override suspend fun read(alias: SecretAlias): SecretEnvelope? {
        val preferences = dataStore.data.first()
        val keys = PreferenceWireCodec.envelope(alias)
        val version = preferences[keys.version]
        val iv = preferences[keys.iv]
        val ciphertext = preferences[keys.ciphertext]
        val presentCount = listOf(version, iv, ciphertext).count { it != null }
        val envelope = if (presentCount == ENVELOPE_FIELD_COUNT) {
            SecretEnvelope(
                requireNotNull(version),
                requireNotNull(iv),
                requireNotNull(ciphertext)
            )
        } else {
            null
        }
        if (presentCount in 1 until ENVELOPE_FIELD_COUNT) {
            delete(alias)
        }
        return envelope
    }

    override suspend fun write(alias: SecretAlias, envelope: SecretEnvelope) {
        val keys = PreferenceWireCodec.envelope(alias)
        dataStore.edit { preferences ->
            preferences[keys.version] = envelope.version
            preferences[keys.iv] = envelope.iv
            preferences[keys.ciphertext] = envelope.ciphertext
        }
    }

    override suspend fun delete(alias: SecretAlias) {
        val keys = PreferenceWireCodec.envelope(alias)
        dataStore.edit { preferences ->
            preferences.remove(keys.version)
            preferences.remove(keys.iv)
            preferences.remove(keys.ciphertext)
        }
    }

    suspend fun isConfigured(alias: SecretAlias): Boolean {
        val preferences = dataStore.data.first()
        val keys = PreferenceWireCodec.envelope(alias)
        return preferences[keys.version] != null &&
            preferences[keys.iv] != null &&
            preferences[keys.ciphertext] != null
    }

    fun presence(alias: SecretAlias): Flow<Boolean> = dataStore.data.map { preferences ->
        val keys = PreferenceWireCodec.envelope(alias)
        preferences[keys.version] != null &&
            preferences[keys.iv] != null &&
            preferences[keys.ciphertext] != null
    }
}

interface SecretStore {
    suspend fun put(alias: SecretAlias, value: CharArray)

    suspend fun get(alias: SecretAlias): CharArray?

    suspend fun delete(alias: SecretAlias)
}

class EnvelopeSecretStore(
    private val storage: SecretEnvelopeStorage,
    private val cipher: SecretCipher
) : SecretStore {
    override suspend fun put(alias: SecretAlias, value: CharArray) {
        try {
            storage.write(alias, cipher.encrypt(alias, value))
        } finally {
            value.fill('\u0000')
        }
    }

    override suspend fun get(alias: SecretAlias): CharArray? {
        val envelope = storage.read(alias) ?: return null
        return when (val result = cipher.decrypt(alias, envelope)) {
            is SecretDecryptionResult.Success -> result.value

            SecretDecryptionResult.AuthenticationFailed,
            SecretDecryptionResult.InvalidEnvelope,
            SecretDecryptionResult.KeyInvalidated -> {
                storage.delete(alias)
                null
            }
        }
    }

    override suspend fun delete(alias: SecretAlias) {
        storage.delete(alias)
    }
}

private const val ENVELOPE_FIELD_COUNT = 3

private fun CharArray.toUtf8Bytes(): ByteArray {
    val encoder = Charsets.UTF_8.newEncoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    val output = encoder.encode(CharBuffer.wrap(this))
    return ByteArray(output.remaining()).also(output::get)
}

private fun ByteArray.fromUtf8Bytes(): CharArray {
    val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    val output = decoder.decode(ByteBuffer.wrap(this))
    return CharArray(output.remaining()).also(output::get)
}

private fun String.decodeBase64OrNull(): ByteArray? = try {
    Base64.getDecoder().decode(this)
} catch (_: IllegalArgumentException) {
    null
}
