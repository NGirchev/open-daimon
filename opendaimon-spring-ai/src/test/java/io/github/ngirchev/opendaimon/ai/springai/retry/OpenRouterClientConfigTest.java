package io.github.ngirchev.opendaimon.ai.springai.retry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for OpenRouterClientConfig (included in Sonar coverage).
 */
class OpenRouterClientConfigTest {

    @Test
    void fromBaseUrl_normalizesTrailingSlash() {
        OpenRouterClientConfig config = OpenRouterClientConfig.fromBaseUrl("https://openrouter.ai/api/", "key");
        assertEquals("https://openrouter.ai/api", config.baseUrl());
        assertEquals("key", config.apiKey());
    }

    @Test
    void fromBaseUrl_noTrailingSlash_unchanged() {
        OpenRouterClientConfig config = OpenRouterClientConfig.fromBaseUrl("https://openrouter.ai/api", "key");
        assertEquals("https://openrouter.ai/api", config.baseUrl());
    }

    @Test
    void fromBaseUrl_emptyOrBlank_returnsAsIs() {
        OpenRouterClientConfig empty = OpenRouterClientConfig.fromBaseUrl("", "k");
        assertEquals("", empty.baseUrl());
        OpenRouterClientConfig blank = OpenRouterClientConfig.fromBaseUrl("  ", "k");
        assertEquals("  ", blank.baseUrl());
    }

    @Test
    void fromChatCompletionsUrl_stripsPath() {
        OpenRouterClientConfig config = OpenRouterClientConfig.fromChatCompletionsUrl(
                "https://openrouter.ai/api/v1/chat/completions", "key");
        assertEquals("https://openrouter.ai/api", config.baseUrl());
    }

    @Test
    void fromChatCompletionsUrl_stripsV1Only() {
        OpenRouterClientConfig config = OpenRouterClientConfig.fromChatCompletionsUrl(
                "https://openrouter.ai/api/v1", "key");
        assertEquals("https://openrouter.ai/api", config.baseUrl());
    }

    @Test
    void fromChatCompletionsUrl_withTrailingSlash_normalizes() {
        OpenRouterClientConfig config = OpenRouterClientConfig.fromChatCompletionsUrl(
                "https://openrouter.ai/api/v1/chat/completions/", "key");
        assertEquals("https://openrouter.ai/api", config.baseUrl());
    }
}
