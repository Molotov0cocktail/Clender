package com.molotov.clender.data.settings

import java.security.AlgorithmParameters
import java.security.InvalidAlgorithmParameterException
import java.security.InvalidKeyException
import java.security.Key
import java.security.Provider
import java.security.SecureRandom
import java.security.Security
import java.security.spec.AlgorithmParameterSpec
import javax.crypto.Cipher
import javax.crypto.CipherSpi
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/** Test-only non-exportable handle: software-provider fallback cannot bypass the policy. */
internal class PolicySecretKey : SecretKey {
    val delegate: SecretKey = SecretKeySpec(ByteArray(32).also(SecureRandom()::nextBytes), "AES")

    override fun getAlgorithm(): String = "AES"
    override fun getFormat(): String? = null
    override fun getEncoded(): ByteArray? = null
}

internal class RandomizedPolicyBackend : AndroidKeyStoreBackend {
    private val keys = mutableMapOf<String, SecretKey>()

    override fun loadOrGenerate(alias: String, policy: AndroidKeyStoreKeyPolicy): SecretKey {
        check(policy.randomizedEncryptionRequired)
        return keys.getOrPut(alias) { PolicySecretKey() }
    }

    override fun delete(alias: String, policy: AndroidKeyStoreKeyPolicy) {
        keys.remove(alias)
    }
}

internal class RandomizedPolicyProvider : Provider(NAME, 1.0, "Synthetic policy regression") {
    init {
        put("Cipher.AES/GCM/NoPadding", RandomizedPolicyCipherSpi::class.java.name)
    }

    companion object {
        const val NAME = "ClenderTestRandomizedPolicy"
    }
}

/** Enforces randomized-encryption IV rules while delegating cryptography to a real provider. */
class RandomizedPolicyCipherSpi : CipherSpi() {
    private val cipher = Cipher.getInstance(
        "AES/GCM/NoPadding",
        Security.getProviders().first { provider ->
            provider.name != RandomizedPolicyProvider.NAME &&
                provider.getService("Cipher", "AES/GCM/NoPadding") != null
        }
    )

    override fun engineSetMode(mode: String) {
        require(mode.equals("GCM", ignoreCase = true))
    }

    override fun engineSetPadding(padding: String) {
        require(padding.equals("NoPadding", ignoreCase = true))
    }

    override fun engineGetBlockSize(): Int = cipher.blockSize
    override fun engineGetOutputSize(inputLen: Int): Int = cipher.getOutputSize(inputLen)
    override fun engineGetIV(): ByteArray? = cipher.iv
    override fun engineGetParameters(): AlgorithmParameters? = cipher.parameters

    override fun engineInit(opmode: Int, key: Key, random: SecureRandom?) {
        cipher.init(opmode, unwrap(key), random)
    }

    override fun engineInit(
        opmode: Int,
        key: Key,
        params: AlgorithmParameterSpec?,
        random: SecureRandom?
    ) {
        rejectCallerIv(opmode, params != null)
        cipher.init(opmode, unwrap(key), params, random)
    }

    override fun engineInit(
        opmode: Int,
        key: Key,
        params: AlgorithmParameters?,
        random: SecureRandom?
    ) {
        rejectCallerIv(opmode, params != null)
        cipher.init(opmode, unwrap(key), params, random)
    }

    override fun engineUpdate(input: ByteArray, offset: Int, len: Int): ByteArray? =
        cipher.update(input, offset, len)

    override fun engineUpdate(
        input: ByteArray,
        offset: Int,
        len: Int,
        output: ByteArray,
        outputOffset: Int
    ): Int = cipher.update(input, offset, len, output, outputOffset)

    override fun engineDoFinal(input: ByteArray, offset: Int, len: Int): ByteArray =
        cipher.doFinal(input, offset, len)

    override fun engineDoFinal(
        input: ByteArray,
        offset: Int,
        len: Int,
        output: ByteArray,
        outputOffset: Int
    ): Int = cipher.doFinal(input, offset, len, output, outputOffset)

    override fun engineUpdateAAD(src: ByteArray, offset: Int, len: Int) {
        cipher.updateAAD(src, offset, len)
    }

    private fun unwrap(key: Key): SecretKey = (key as? PolicySecretKey)?.delegate
        ?: throw InvalidKeyException("Test provider only accepts policy handles")

    private fun rejectCallerIv(opmode: Int, hasParameters: Boolean) {
        if (opmode == Cipher.ENCRYPT_MODE && hasParameters) {
            throw InvalidAlgorithmParameterException("Caller IV forbidden by randomized policy")
        }
    }
}
