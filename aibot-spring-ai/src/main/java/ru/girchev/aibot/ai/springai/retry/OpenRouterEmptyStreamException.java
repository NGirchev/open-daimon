package ru.girchev.aibot.ai.springai.retry;

/**
 * Выбрасывается, когда OpenRouter SSE-стрим завершился без текста в delta.content
 * (например, reasoning-only или лимит генерации), чтобы аспект ротации моделей
 * мог повторить запрос с другой моделью.
 */
public class OpenRouterEmptyStreamException extends RuntimeException {

    public OpenRouterEmptyStreamException(String message) {
        super(message);
    }
}
