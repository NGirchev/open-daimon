package io.github.ngirchev.opendaimon.common.config;

/**
 * Centralized registry of all feature toggle property keys.
 *
 * <p>String constants are grouped by category and usable directly in
 * {@link org.springframework.boot.autoconfigure.condition.ConditionalOnProperty} annotations.
 *
 * <p>For runtime iteration and validation, see {@link Toggle}.
 *
 * @see <a href="docs/feature-toggles.md">Feature Toggle Conventions</a>
 */
public final class FeatureToggle {

    private FeatureToggle() {
    }

    // ── Module toggles ──────────────────────────────────────────

    /**
     * Module-level toggles that enable/disable entire modules.
     * Used in top-level {@code @ConditionalOnProperty} on auto-configuration classes.
     */
    public static final class Module {

        private Module() {
        }

        public static final String TELEGRAM_ENABLED = "open-daimon.telegram.enabled";
        public static final String SPRING_AI_ENABLED = "open-daimon.ai.spring-ai.enabled";
        public static final String REST_ENABLED = "open-daimon.rest.enabled";
        public static final String UI_ENABLED = "open-daimon.ui.enabled";
        public static final String AGENT_ENABLED = "open-daimon.agent.enabled";
        public static final String GATEWAY_MOCK_ENABLED = "open-daimon.ai.gateway-mock.enabled";
    }

    // ── Feature toggles ─────────────────────────────────────────

    /**
     * Feature-level toggles within modules.
     * Enable/disable specific capabilities without turning off the entire module.
     */
    public static final class Feature {

        private Feature() {
        }

        public static final String RAG_ENABLED = "open-daimon.ai.spring-ai.rag.enabled";
        public static final String BULKHEAD_ENABLED = "open-daimon.common.bulkhead.enabled";
        public static final String STORAGE_ENABLED = "open-daimon.common.storage.enabled";
        public static final String TELEGRAM_CACHE_REDIS_ENABLED = "open-daimon.telegram.cache.redis-enabled";
        public static final String TELEGRAM_FILE_UPLOAD_ENABLED = "open-daimon.telegram.file-upload.enabled";
        public static final String OPENROUTER_MODELS_ENABLED = "open-daimon.ai.spring-ai.openrouter-auto-rotation.models.enabled";
        public static final String AGENT_HTTP_API_TOOL_ENABLED = "open-daimon.agent.tools.http-api.enabled";
    }

    // ── Telegram command toggles (prefix-based) ─────────────────

    /**
     * Telegram command toggles using prefix-based {@code @ConditionalOnProperty}.
     * <p>Usage: {@code @ConditionalOnProperty(prefix = TelegramCommand.PREFIX, name = TelegramCommand.START, ...)}
     */
    public static final class TelegramCommand {

        private TelegramCommand() {
        }

        public static final String PREFIX = "open-daimon.telegram.commands";
        public static final String BUGREPORT = "bugreport-enabled";
        public static final String START = "start-enabled";
        public static final String ROLE = "role-enabled";
        public static final String LANGUAGE = "language-enabled";
        public static final String NEW_THREAD = "newthread-enabled";
        public static final String HISTORY = "history-enabled";
        public static final String THREADS = "threads-enabled";
        public static final String MESSAGE = "message-enabled";
        public static final String MODEL = "model-enabled";
        public static final String MODE = "mode-enabled";
    }

    // ── OpenRouter model rotation toggles (prefix-based) ────────

    /**
     * OpenRouter auto-rotation toggles using prefix-based {@code @ConditionalOnProperty}.
     * <p>Usage: {@code @ConditionalOnProperty(prefix = OpenRouterModels.PREFIX, name = OpenRouterModels.ENABLED, ...)}
     */
    public static final class OpenRouterModels {

        private OpenRouterModels() {
        }

        public static final String PREFIX = "open-daimon.ai.spring-ai.openrouter-auto-rotation.models";
        public static final String ENABLED = "enabled";
    }

    // ── Runtime enum for iteration / validation ─────────────────

    /**
     * Runtime companion enum referencing the same string constants.
     * Use for iteration, validation, documentation — NOT in annotations.
     */
    public enum Toggle {
        // Module
        TELEGRAM(Module.TELEGRAM_ENABLED),
        SPRING_AI(Module.SPRING_AI_ENABLED),
        REST(Module.REST_ENABLED),
        UI(Module.UI_ENABLED),
        AGENT(Module.AGENT_ENABLED),
        GATEWAY_MOCK(Module.GATEWAY_MOCK_ENABLED),
        // Feature
        RAG(Feature.RAG_ENABLED),
        BULKHEAD(Feature.BULKHEAD_ENABLED),
        STORAGE(Feature.STORAGE_ENABLED),
        TELEGRAM_CACHE_REDIS(Feature.TELEGRAM_CACHE_REDIS_ENABLED),
        TELEGRAM_FILE_UPLOAD(Feature.TELEGRAM_FILE_UPLOAD_ENABLED),
        OPENROUTER_MODELS(Feature.OPENROUTER_MODELS_ENABLED),
        AGENT_HTTP_API_TOOL(Feature.AGENT_HTTP_API_TOOL_ENABLED),
        // Telegram commands
        CMD_BUGREPORT(TelegramCommand.PREFIX + "." + TelegramCommand.BUGREPORT),
        CMD_START(TelegramCommand.PREFIX + "." + TelegramCommand.START),
        CMD_ROLE(TelegramCommand.PREFIX + "." + TelegramCommand.ROLE),
        CMD_LANGUAGE(TelegramCommand.PREFIX + "." + TelegramCommand.LANGUAGE),
        CMD_NEW_THREAD(TelegramCommand.PREFIX + "." + TelegramCommand.NEW_THREAD),
        CMD_HISTORY(TelegramCommand.PREFIX + "." + TelegramCommand.HISTORY),
        CMD_THREADS(TelegramCommand.PREFIX + "." + TelegramCommand.THREADS),
        CMD_MESSAGE(TelegramCommand.PREFIX + "." + TelegramCommand.MESSAGE),
        CMD_MODEL(TelegramCommand.PREFIX + "." + TelegramCommand.MODEL),
        CMD_MODE(TelegramCommand.PREFIX + "." + TelegramCommand.MODE);

        private final String propertyKey;

        Toggle(String propertyKey) {
            this.propertyKey = propertyKey;
        }

        public String propertyKey() {
            return propertyKey;
        }
    }
}
