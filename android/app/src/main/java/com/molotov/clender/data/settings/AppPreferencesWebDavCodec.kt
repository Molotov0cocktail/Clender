package com.molotov.clender.data.settings

import androidx.datastore.preferences.core.MutablePreferences

sealed interface WebDavPasswordMutation {
    data object Keep : WebDavPasswordMutation

    class Replace(val value: CharArray) : WebDavPasswordMutation

    data object Remove : WebDavPasswordMutation
}

internal fun WebDavPasswordMutation.normalizeBlankReplace(): WebDavPasswordMutation =
    if (this is WebDavPasswordMutation.Replace && value.all(Char::isWhitespace)) {
        WebDavPasswordMutation.Keep
    } else {
        this
    }

internal fun WebDavPasswordMutation.encryptIfReplacement(cipher: SecretCipher): SecretEnvelope? =
    when (this) {
        is WebDavPasswordMutation.Replace -> cipher.encrypt(SecretAlias.WEB_DAV_PASSWORD, value)

        WebDavPasswordMutation.Keep,
        WebDavPasswordMutation.Remove -> null
    }

internal fun writeWebDavEnvelope(
    values: MutablePreferences,
    envelopeKeys: SecretEnvelopeKeys,
    mutation: WebDavPasswordMutation,
    replacementEnvelope: SecretEnvelope?
) {
    when (mutation) {
        WebDavPasswordMutation.Keep -> Unit

        WebDavPasswordMutation.Remove -> {
            values.remove(envelopeKeys.version)
            values.remove(envelopeKeys.iv)
            values.remove(envelopeKeys.ciphertext)
        }

        is WebDavPasswordMutation.Replace -> {
            val envelope = requireNotNull(replacementEnvelope)
            values[envelopeKeys.version] = envelope.version
            values[envelopeKeys.iv] = envelope.iv
            values[envelopeKeys.ciphertext] = envelope.ciphertext
        }
    }
}

data class WebDavSectionSnapshot(
    val enabled: Boolean,
    val url: String,
    val username: String,
    val passwordConfigured: Boolean
)

internal fun validateWebDavDraftOrNull(settings: WebDavPreferences): ValidatedWebDavSettings? =
    when {
        settings.enabled -> WebDavSettingsPolicy.validate(settings.url, settings.username)
        settings.url.trim().isEmpty() && settings.username.trim().isEmpty() -> null
        else -> WebDavSettingsPolicy.validate(settings.url, settings.username)
    }
