package ru.girchev.aibot.common.ai;

public enum ModelType {
    AUTO,
    /**
     * model name directly
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
     * Переранжирование кандидатов (после vector search). Очень полезно для качества RAG.
     * 	•	вход: query + candidates[]
     * 	•	выход: candidates with scores (sorted)
     */
    RERANK,

    /**
     * Проверка контента/политик/PII для внешних провайдеров или перед логированием.
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
     * Когда модель должна возвращать вызовы функций стабильно.
     *
     * Если ты хочешь компактнее, то 8–9 можно сделать как CHAT_COMPLETION + capability flags.
     */
    TOOL_CALLING,

    WEB,

    CODE,

    /**
     * Модели с поддержкой изображений (Vision).
     * Например: GPT-4o, Claude 3, Gemini Pro Vision.
     */
    VISION
}
