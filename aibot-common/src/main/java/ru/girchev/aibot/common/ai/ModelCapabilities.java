package ru.girchev.aibot.common.ai;

public enum ModelCapabilities {
    /**
     * Автоматически определяются тулы, включает всё что возможно.
     */
    AUTO,
    /**
     * Передаёт имя как есть, применимо например для openrouter.
     */
    RAW_TYPE,
    /**
     * Генерация текста/диалог: ответ, переписывание, код, reasoning.
     * 	•	вход: messages/system+user, tools?, response_format?
     * 	•	выход: text + tool_calls
     */
    CHAT,

    /**
     * Векторизация текста для поиска/кластеризации.
     * 	•	вход: list
     * 	•	выход: list
     */
    EMBEDDING,

    /**
     * Переранжирование кандидатов (после vector search). Очень полезно для качества RAG (не поддерживается нашим spring ai)
     * 	•	вход: query + candidates[]
     * 	•	выход: candidates with scores (sorted)
     */
    RERANK,

    /**
     * Проверка контента/политик/PII для внешних провайдеров или перед логированием. (у нас используется openrouter
     * и этот фильтр так же не вызывается)
     * 	•	вход: text/images
     * 	•	выход: flags/categories
     */
    MODERATION,

    SUMMARIZATION,

    /**
     * Когда тебе нужен JSON строго по схеме.
     * 	•	в OpenAI это “response_format/json_schema”, у других — аналоги/хаки.
     * 	•	контракт: schema + prompt → валидный JSON
     */
    STRUCTURED_OUTPUT,

    /**
     * Вызов функций (function calling / tools).
     * В capabilities модели — модель умеет возвращать tool_calls и обрабатывать tools в запросе.
     * Используется для выбора модели с поддержкой function calling.
     * Для передачи конкретных tools в запрос используйте специализированные типы (например, WEB для веб-поиска).
     */
    TOOL_CALLING,

    /**
     * Веб-поиск и работа с URL (web search, fetch URL).
     * <ul>
     *   <li>В capabilities модели — модель поддерживает веб-поиск.</li>
     *   <li>В command.modelTypes() — в этот запрос нужно передать WebTools (веб-поиск и fetch URL).</li>
     * </ul>
     */
    WEB,

    /**
     * Модели с поддержкой изображений (Vision).
     * Например: GPT-4o, Claude 3, Gemini Pro Vision.
     */
    VISION,

    /**
     * Модели бесплатного тира (OpenRouter free и т.п.).
     * Используется для ранжирования и retry; в yml добавлять только для реально бесплатных моделей.
     */
    FREE
}
