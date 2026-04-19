package io.github.ngirchev.opendaimon.ai.springai.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "open-daimon.ai.spring-ai")
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
    
    private Serper serper = new Serper();
    
    private Models models = new Models();

    private WebToolsClient webTools = new WebToolsClient();

    private UrlCheck urlCheck = new UrlCheck();

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

    /**
     * Post-processing URL liveness check applied to the agent's final answer before
     * it reaches the user. Guards against hallucinated or stale links.
     */
    @Getter
    @Setter
    public static class UrlCheck {
        /**
         * Timeout for a single HEAD (or ranged GET fallback) request, in milliseconds.
         */
        @NotNull(message = "urlCheck.timeoutMs is required")
        @Min(value = 1, message = "urlCheck.timeoutMs must be >= 1")
        private Integer timeoutMs;

        /**
         * Maximum number of unique URLs to check per answer. Keeps streaming latency bounded.
         */
        @NotNull(message = "urlCheck.maxUrlsPerAnswer is required")
        @Min(value = 1, message = "urlCheck.maxUrlsPerAnswer must be >= 1")
        private Integer maxUrlsPerAnswer;
    }

    @Getter
    @Setter
    public static class WebToolsClient {
        /**
         * Maximum response bytes buffered by WebClient codecs for tool HTTP requests.
         */
        @NotNull(message = "webTools.maxInMemoryBytes is required")
        @Min(value = 1, message = "webTools.maxInMemoryBytes must be >= 1")
        private Integer maxInMemoryBytes;

        /**
         * Maximum bytes read for fetch_url page body before aborting with TOO_LARGE.
         */
        @NotNull(message = "webTools.maxFetchBytes is required")
        @Min(value = 1, message = "webTools.maxFetchBytes must be >= 1")
        private Integer maxFetchBytes;

        /**
         * User-Agent sent for web tool requests to reduce anti-bot denials.
         */
        @NotBlank(message = "webTools.userAgent is required")
        private String userAgent;
    }
}
