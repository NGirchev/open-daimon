package io.github.ngirchev.aibot.common.ai;

public enum ModelCapabilities {
    /**
     * Tools determined automatically, includes all possible.
     */
    AUTO,
    /**
     * Passes name as-is, e.g. for openrouter.
     */
    RAW_TYPE,
    /**
     * Text generation/dialog: response, rewriting, code, reasoning.
     * Input: messages/system+user, tools?, response_format?
     * Output: text + tool_calls
     */
    CHAT,

    /**
     * Text vectorization for search/clustering.
     * Input: list
     * Output: list
     */
    EMBEDDING,

    /**
     * Reranking candidates (after vector search). Very useful for RAG quality (not supported by our spring ai).
     * Input: query + candidates[]
     * Output: candidates with scores (sorted)
     */
    RERANK,

    /**
     * Content/policy/PII check for external providers or before logging. (we use openrouter
     * and this filter is not invoked either)
     * Input: text/images
     * Output: flags/categories
     */
    MODERATION,

    SUMMARIZATION,

    /**
     * When you need JSON strictly by schema.
     * In OpenAI this is "response_format/json_schema"; others have equivalents/workarounds.
     * Contract: schema + prompt → valid JSON
     */
    STRUCTURED_OUTPUT,

    /**
     * Function calling / tools.
     * In model capabilities — model can return tool_calls and handle tools in request.
     * Used to select model with function calling support.
     * For passing specific tools use specialized types (e.g. WEB for web search).
     */
    TOOL_CALLING,

    /**
     * Web search and URL handling (web search, fetch URL).
     * <ul>
     *   <li>In model capabilities — model supports web search.</li>
     *   <li>In command.modelTypes() — this request should pass WebTools (web search and fetch URL).</li>
     * </ul>
     */
    WEB,

    /**
     * Models with image support (Vision).
     * E.g. GPT-4o, Claude 3, Gemini Pro Vision.
     */
    VISION,

    /**
     * Free-tier models (OpenRouter free, etc.).
     * Used for ranking and retry; add in yml only for actually free models.
     */
    FREE
}
