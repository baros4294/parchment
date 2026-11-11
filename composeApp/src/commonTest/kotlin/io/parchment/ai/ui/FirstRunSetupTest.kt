package io.parchment.ai.ui

import io.parchment.ai.model.ProviderProfile
import io.parchment.ai.model.ProviderProtocol
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FirstRunSetupTest {
    private fun profile(baseUrl: String, apiKey: String = "") =
        ProviderProfile("p", "Provider", ProviderProtocol.OPENAI_RESPONSES, baseUrl = baseUrl, apiKey = apiKey)

    @Test
    fun aHostedProviderWithoutAKeyNeedsSetup() {
        assertTrue(needsApiKey(profile("https://api.openai.com/v1")))
    }

    @Test
    fun aKeyEndsSetup() {
        assertFalse(needsApiKey(profile("https://api.openai.com/v1", apiKey = "sk-test")))
    }

    @Test
    fun aServerOnThisComputerNeedsNoKey() {
        listOf("http://localhost:11434/v1", "http://127.0.0.1:1234", "http://[::1]:8080/v1", "http://[::1]", "HTTP://LOCALHOST")
            .forEach { assertFalse(needsApiKey(profile(it)), it) }
    }

    @Test
    fun aLookalikeHostStillNeedsAKey() {
        assertTrue(needsApiKey(profile("https://localhost.example.com/v1")))
    }
}
