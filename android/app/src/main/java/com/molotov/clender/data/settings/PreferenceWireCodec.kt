package com.molotov.clender.data.settings

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

internal data class SecretEnvelopeKeys(
    val version: Preferences.Key<Int>,
    val iv: Preferences.Key<String>,
    val ciphertext: Preferences.Key<String>
)

internal object PreferenceWireCodec {
    fun string(name: String): Preferences.Key<String> = stringPreferencesKey(name)

    fun envelope(alias: SecretAlias): SecretEnvelopeKeys {
        val prefix = "secret_envelope_${alias.name.lowercase()}"
        return SecretEnvelopeKeys(
            version = intPreferencesKey("${prefix}_version"),
            iv = stringPreferencesKey("${prefix}_iv"),
            ciphertext = stringPreferencesKey("${prefix}_ciphertext")
        )
    }
}
