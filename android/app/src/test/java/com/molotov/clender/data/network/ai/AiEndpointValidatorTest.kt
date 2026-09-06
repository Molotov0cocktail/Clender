package com.molotov.clender.data.network.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AiEndpointValidatorTest {
    @Test
    fun explicitNumericVersionTailIsPreservedForBothOperations() {
        val bases = listOf("/v2", "/api/paas/v4", "/proxy/v12", "/v01", "/%E8%AE%A1%E5%88%92/v4")
        bases.forEach { base ->
            listOf("", "/").forEach { trailing ->
                val endpoint = "https://example.invalid$base$trailing"
                assertEquals("$base/models", AiEndpointValidator.modelsUrl(endpoint).encodedPath)
                assertEquals(
                    "$base/chat/completions",
                    AiEndpointValidator.chatUrl(endpoint).encodedPath
                )
            }
        }
    }

    @Test
    fun rootV1AndUnversionedProxyKeepExistingResolution() {
        mapOf(
            "" to "/v1",
            "/v1" to "/v1",
            "/proxy" to "/proxy/v1",
            "/proxy/v1" to "/proxy/v1",
            "/v2/proxy" to "/v2/proxy/v1",
            "/proxy-v4" to "/proxy-v4/v1",
            "/v4beta" to "/v4beta/v1",
            "/V4" to "/V4/v1",
            "/v4/gateway" to "/v4/gateway/v1",
            "/v" to "/v/v1",
            "/%76%34" to "/%76%34/v1",
            "/v%34" to "/v%34/v1",
            "/v%D9%A4" to "/v%D9%A4/v1",
            "/%E8%AE%A1%E5%88%92/v1" to "/%E8%AE%A1%E5%88%92/v1"
        ).forEach { (base, resolved) ->
            listOf("", "/").forEach { trailing ->
                val endpoint = "https://example.invalid$base$trailing"
                assertEquals(
                    "$resolved/models",
                    AiEndpointValidator.modelsUrl(endpoint).encodedPath
                )
                assertEquals(
                    "$resolved/chat/completions",
                    AiEndpointValidator.chatUrl(endpoint).encodedPath
                )
            }
        }
    }

    @Test
    fun versionedBasesDoNotBypassSecurityOrAcceptOperationUrls() {
        listOf(
            "http://example.invalid/v4",
            "https://user:pass@example.invalid/v4",
            "https://example.invalid/v4?tenant=1",
            "https://example.invalid/v4#fragment",
            "https://example.invalid/api/../v4",
            "https://example.invalid/api/%2e%2e/v4",
            "https://example.invalid/api//v4",
            "https://example.invalid/api%2fv4",
            "https://example.invalid/api%5cv4",
            "https://example.invalid/v4/models/",
            "https://example.invalid/v4/chat/completions/"
        ).forEach { endpoint ->
            assertThrows("models must reject $endpoint", AiConfigurationException::class.java) {
                AiEndpointValidator.modelsUrl(endpoint)
            }
            assertThrows("chat must reject $endpoint", AiConfigurationException::class.java) {
                AiEndpointValidator.chatUrl(endpoint)
            }
        }
    }

    @Test
    fun resolvesBasePathAndExistingV1WithoutLosingEncodedSegments() {
        assertEquals(
            "https://example.invalid/gateway/v1/models",
            AiEndpointValidator.modelsUrl("https://example.invalid/gateway").toString()
        )
        assertEquals(
            "https://example.invalid/gateway/v1/chat/completions",
            AiEndpointValidator.chatUrl("https://example.invalid/gateway/v1/").toString()
        )
        assertEquals(
            "/%E8%AE%A1%E5%88%92/v1/models",
            AiEndpointValidator.modelsUrl("https://example.invalid/%E8%AE%A1%E5%88%92").encodedPath
        )
    }

    @Test
    fun rejectsCleartextCredentialsQueryFragmentTraversalAndEndpointFile() {
        listOf(
            "",
            "http://example.invalid",
            "https://user:pass@example.invalid",
            "https://example.invalid?tenant=1",
            "https://example.invalid#fragment",
            "https://example.invalid/base/../escape",
            "https://example.invalid/v1/chat/completions",
            "ftp://example.invalid"
        ).forEach { endpoint ->
            assertThrows("must reject $endpoint", AiConfigurationException::class.java) {
                AiEndpointValidator.modelsUrl(endpoint)
            }
        }
    }
}
