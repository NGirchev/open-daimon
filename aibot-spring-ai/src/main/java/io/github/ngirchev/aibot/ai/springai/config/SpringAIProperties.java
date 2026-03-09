package io.github.ngirchev.aibot.ai.springai.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@ConfigurationProperties(prefix = "ai-bot.ai.spring-ai")
@Validated
@Getter
@Setter
public class SpringAIProperties {
    
    private Boolean mock;

    private OpenRouterAutoRotation openrouterAutoRotation = new OpenRouterAutoRotation();

    /**
     * Application attribution for OpenRouter (dashboard: App column).
     * HTTP-Referer and X-Title are sent in requests to OpenRouter.
     */
    private OpenRouterApp openrouterApp = new OpenRouterApp();

    private HttpLogs httpLogs = new HttpLogs();
    
    /**
     * ChatMemory history window size (number of recent messages).
     * Used by SummarizingChatMemory to determine when to trigger summarization.
     */
    @NotNull(message = "historyWindowSize is required")
    @Min(value = 1, message = "historyWindowSize must be >= 1")
    private Integer historyWindowSize;
    
    private Serper serper = new Serper();
    
    private Models models = new Models();

    @Getter
    @Setter
    public static class OpenRouterApp {
        /** Application URL (HTTP-Referer). Optional. */
        private String siteUrl;
        /** Application name in OpenRouter dashboard (X-Title). Optional. */
        private String title;
    }

    @Getter
    @Setter
    public static class OpenRouterAutoRotation {
        /**
         * Maximum number of attempts for OpenRouter model AUTO-rotation.
         * Value 2 = 1 retry (first attempt + one additional).
         */
        @Min(value = 1, message = "maxAttempts must be >= 1")
        private Integer maxAttempts = 2;
    }
    
    @Getter
    @Setter
    public static class Serper {
        private Api api = new Api();
        
        @Getter
        @Setter
        public static class Api {
            @NotBlank(message = "API key for Serper cannot be blank")
            private String key;
            
            @NotBlank(message = "Serper API URL cannot be blank")
            private String url;
        }
    }
    
    @Getter
    @Setter
    public static class Models {
        @NotEmpty(message = "List of models cannot be empty")
        private List<SpringAIModelConfig> list = new ArrayList<>();
    }

    @Getter
    @Setter
    public static class HttpLogs {
        /**
         * Log call stack of "who made the AI HTTP request" (once at startup).
         * Disabled by default as it looks like an exception in logs and is noisy.
         */
        private Boolean callsiteStacktraceEnabled = false;
    }
    
    private Timeouts timeouts = new Timeouts();
    
    /**
     * Model restrictions per user role.
     * If empty or not configured - all models are available.
     * If role has empty list - user has no access to AI.
     */
    private RoleModels roleModels = new RoleModels();
    
    @Getter
    @Setter
    public static class Timeouts {
        /**
         * Response timeout for HTTP requests to AI providers (seconds).
         * Applied to WebClient for Ollama and OpenAI/OpenRouter.
         */
        @NotNull(message = "responseTimeoutSeconds is required")
        @Min(value = 1, message = "responseTimeoutSeconds must be >= 1")
        private Integer responseTimeoutSeconds;
        
        /**
         * Timeout for stream processing (seconds).
         * Maximum time to wait for stream completion.
         */
        @NotNull(message = "streamTimeoutSeconds is required")
        @Min(value = 1, message = "streamTimeoutSeconds must be >= 1")
        private Integer streamTimeoutSeconds;
    }

    @Getter
    @Setter
    public static class RoleModels {
        private LevelModels admin = new LevelModels();
        private LevelModels vip = new LevelModels();
        private LevelModels regular = new LevelModels();

        @Getter
        @Setter
        public static class LevelModels {
            /**
             * List of model names allowed for this role.
             * Empty list = no access to AI.
             * Null = all models available.
             */
            private Set<String> models = new HashSet<>();
        }

        public Set<String> getModelsForRole(String role) {
            if (role == null) {
                return null;
            }
            return switch (role.toUpperCase()) {
                case "ADMIN" -> admin.getModels();
                case "VIP" -> vip.getModels();
                case "REGULAR" -> regular.getModels();
                default -> null;
            };
        }
    }
} 
