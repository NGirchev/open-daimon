package io.github.ngirchev.opendaimon.ai.springai.retry;

import org.springframework.util.StringUtils;

/**
 * Minimal config for calling OpenRouter Models API.
 *
 * @param baseUrl base URL, e.g. {@code https://openrouter.ai/api}
 * @param apiKey  OpenRouter API key
 */
public record OpenRouterClientConfig(String baseUrl, String apiKey) {

    public static OpenRouterClientConfig fromBaseUrl(String baseUrl, String apiKey) {
        return new OpenRouterClientConfig(normalizeBaseUrl(baseUrl), apiKey);
    }

    /**
     * Convenient when we have full URL to chat/completions:
     * {@code https://openrouter.ai/api/v1/chat/completions}.
     */
    public static OpenRouterClientConfig fromChatCompletionsUrl(String chatCompletionsUrl, String apiKey) {
        String normalized = normalizeBaseUrl(chatCompletionsUrl);
        if (normalized.endsWith("/v1/chat/completions")) {
            normalized = normalized.substring(0, normalized.length() - "/v1/chat/completions".length());
        }
        if (normalized.endsWith("/v1")) {
            normalized = normalized.substring(0, normalized.length() - "/v1".length());
        }
        return new OpenRouterClientConfig(normalizeBaseUrl(normalized), apiKey);
    }

    private static String normalizeBaseUrl(String url) {
        if (!StringUtils.hasText(url)) {
            return url;
        }
        String trimmed = url.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }
}
