package io.github.ngirchev.aibot.ai.springai.retry;

/**
 * Thrown when OpenRouter SSE stream finished with no text in delta.content
 * (e.g. reasoning-only or generation limit), so the model rotation aspect
 * can retry the request with another model.
 */
public class OpenRouterEmptyStreamException extends RuntimeException {

    public OpenRouterEmptyStreamException(String message) {
        super(message);
    }
}
