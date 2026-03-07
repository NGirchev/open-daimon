package ru.girchev.aibot.ai.springai.config;

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

@ConfigurationProperties(prefix = "ai-bot.ai.spring-ai")
@Validated
@Getter
@Setter
public class SpringAIProperties {
    
    private Boolean mock;

    private OpenRouterAutoRotation openrouterAutoRotation = new OpenRouterAutoRotation();

    /**
     * Атрибуция приложения для OpenRouter (дашборд: столбец App).
     * HTTP-Referer и X-Title передаются в запросах к OpenRouter.
     */
    private OpenRouterApp openrouterApp = new OpenRouterApp();

    private HttpLogs httpLogs = new HttpLogs();
    
    /**
     * Размер окна истории сообщений для ChatMemory (количество последних сообщений).
     * Используется в SummarizingChatMemory для определения, когда нужно запускать суммаризацию.
     */
    @NotNull(message = "historyWindowSize обязателен")
    @Min(value = 1, message = "historyWindowSize должен быть >= 1")
    private Integer historyWindowSize;
    
    private Serper serper = new Serper();
    
    private Models models = new Models();

    @Getter
    @Setter
    public static class OpenRouterApp {
        /** URL приложения (HTTP-Referer). Опционально. */
        private String siteUrl;
        /** Название приложения в дашборде OpenRouter (X-Title). Опционально. */
        private String title;
    }

    @Getter
    @Setter
    public static class OpenRouterAutoRotation {
        /**
         * Максимальное количество попыток при AUTO-ротации OpenRouter моделей.
         * Значение 2 = 1 retry (первая попытка + одна дополнительная).
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
            
            @NotBlank(message = "URL API Serper не может быть пустым")
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
         * Логирует стек вызова "кто сделал AI HTTP запрос" (один раз на старте).
         * По умолчанию выключено, т.к. выглядит как исключение в логах и шумит.
         */
        private Boolean callsiteStacktraceEnabled = false;
    }
    
    private Timeouts timeouts = new Timeouts();
    
    @Getter
    @Setter
    public static class Timeouts {
        /**
         * Таймаут ответа для HTTP запросов к AI провайдерам (в секундах).
         * Применяется к WebClient для Ollama и OpenAI/OpenRouter.
         */
        @NotNull(message = "responseTimeoutSeconds обязателен")
        @Min(value = 1, message = "responseTimeoutSeconds должен быть >= 1")
        private Integer responseTimeoutSeconds;
        
        /**
         * Таймаут для обработки стриминга (в секундах).
         * Максимальное время ожидания завершения стрима.
         */
        @NotNull(message = "streamTimeoutSeconds обязателен")
        @Min(value = 1, message = "streamTimeoutSeconds должен быть >= 1")
        private Integer streamTimeoutSeconds;
    }
} 
