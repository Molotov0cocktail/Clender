package com.molotov.clender.data.settings

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object AiEndpointPolicy {
    fun resolve(endpoint: String, relativePath: String): HttpUrl {
        require(relativePath == "models" || relativePath == "chat/completions") {
            "AI relative path is unsupported"
        }
        val raw = endpoint.trim()
        validateRawUrlPath(raw, "AI endpoint")
        val parsed = requireNotNull(raw.toHttpUrlOrNull()) { "AI endpoint is invalid" }
        require(parsed.scheme == "https" && parsed.host.isNotBlank()) {
            "AI endpoint must use HTTPS"
        }
        require(parsed.username.isEmpty() && parsed.password.isEmpty()) {
            "AI endpoint must not contain credentials"
        }
        require(parsed.query == null && parsed.fragment == null) {
            "AI endpoint must not contain query or fragment"
        }
        val base = parsed.encodedPath.trimEnd('/')
        require(!base.endsWith("/models") && !base.endsWith("/chat/completions")) {
            "AI endpoint must be a base URL"
        }
        val versionedBase = if (VERSION_SEGMENT.matches(base.substringAfterLast('/'))) {
            base
        } else {
            "$base/v1"
        }
        return parsed.newBuilder()
            .encodedPath("$versionedBase/$relativePath")
            .query(null)
            .fragment(null)
            .build()
    }

    private val VERSION_SEGMENT = Regex("v[0-9]+")
}

data class ValidatedWebDavSettings(
    val directoryUrl: String,
    val username: String,
    val remoteUrl: String
)

object WebDavSettingsPolicy {
    private const val REMOTE_FILENAME = "clender-events.json"

    fun validate(directoryUrl: String, username: String): ValidatedWebDavSettings {
        val rawUrl = directoryUrl.trim()
        val normalizedUsername = username.trim()
        require(normalizedUsername.isNotEmpty()) { "WebDAV username is required" }
        require(':' !in normalizedUsername) { "WebDAV username must not contain a colon" }
        requireValidUnicode(normalizedUsername)
        validateRawUrlPath(rawUrl, "WebDAV directory URL")
        val parsed = requireNotNull(rawUrl.toHttpUrlOrNull()) {
            "WebDAV directory URL is invalid"
        }
        require(parsed.scheme == "https") { "WebDAV directory URL must use HTTPS" }
        require(parsed.host.isNotBlank()) { "WebDAV directory URL must include a host" }
        require(parsed.username.isEmpty() && parsed.password.isEmpty()) {
            "WebDAV directory URL must not contain credentials"
        }
        require(parsed.query == null && parsed.fragment == null) {
            "WebDAV directory URL must not contain a query or fragment"
        }
        val directoryPath = parsed.encodedPath.let { path ->
            if (path.endsWith('/')) path else "$path/"
        }
        val normalizedDirectory = parsed.newBuilder()
            .encodedPath(directoryPath)
            .query(null)
            .fragment(null)
            .build()
        val remote = normalizedDirectory.newBuilder()
            .addPathSegment(REMOTE_FILENAME)
            .build()
        return ValidatedWebDavSettings(
            directoryUrl = normalizedDirectory.toString(),
            username = normalizedUsername,
            remoteUrl = remote.toString()
        )
    }
}

private fun validateRawUrlPath(rawUrl: String, label: String) {
    require(rawUrl.isNotBlank()) { "$label is required" }
    require('\\' !in rawUrl) { "$label contains an unsafe path" }
    val schemeSeparator = rawUrl.indexOf("://")
    require(schemeSeparator > 0) { "$label is invalid" }
    val afterAuthority = rawUrl.substring(schemeSeparator + SCHEME_SEPARATOR_LENGTH)
    val pathStart = afterAuthority.indexOf('/')
    if (pathStart < 0) return
    val rawPath = afterAuthority.substring(pathStart).substringBefore('?').substringBefore('#')
    require("//" !in rawPath) { "$label contains repeated separators" }
    rawPath.split('/').forEach { segment ->
        val lower = segment.lowercase()
        require("%2f" !in lower && "%5c" !in lower) {
            "$label contains an encoded separator"
        }
        val decodedDots = lower.replace("%2e", ".")
        require(decodedDots != "." && decodedDots != "..") {
            "$label contains path traversal"
        }
    }
}

private fun requireValidUnicode(value: String) {
    var index = 0
    while (index < value.length) {
        val character = value[index]
        when {
            Character.isHighSurrogate(character) -> {
                require(index + 1 < value.length && Character.isLowSurrogate(value[index + 1])) {
                    "WebDAV text contains an unpaired surrogate"
                }
                index += 2
            }

            Character.isLowSurrogate(character) -> throw IllegalArgumentException(
                "WebDAV text contains an unpaired surrogate"
            )

            else -> index += 1
        }
    }
}

private const val SCHEME_SEPARATOR_LENGTH = 3
