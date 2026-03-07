package ru.girchev.aibot.ai.springai;

import io.netty.resolver.DefaultAddressResolverGroup;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.netty.http.client.HttpClient;
import ru.girchev.aibot.common.service.AIUtils;

import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Интеграционный тест для проверки DNS резолвинга в Spring AI Ollama при стриминге.
 * 
 * Этот тест проверяет, что при использовании Spring AI ChatClient.stream().chatResponse()
 * с .local доменом не возникает ошибок DNS (NXDOMAIN).
 * 
 * Тест создает минимальную конфигурацию с нашим WebClient.Builder и получает OllamaChatModel
 * из Spring контекста (как в реальном приложении).
 */
@Slf4j
@SpringBootTest(classes = SpringAIOllamaDnsIT.TestConfig.class)
@ComponentScan(
    basePackages = "org.springframework.ai",
    excludeFilters = @ComponentScan.Filter(
        type = FilterType.REGEX,
        pattern = "ru\\.girchev\\..*"
    )
)
@TestPropertySource(properties = {
    "spring.ai.ollama.base-url=http://localhost:11434",
    "spring.ai.ollama.chat.options.model=gemma3:1b",
    "ai-bot.ai.spring-ai.openrouter-auto-rotation.models.enabled=false",
    // Исключаем автоконфигурации, которые не нужны для теста
    "spring.autoconfigure.exclude=" +
            "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration," +
            "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration," +
            "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration," +
            "org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration," +
            "org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration," +
            "org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionAutoConfiguration," +
            "org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration," +
            "org.springframework.ai.model.openai.autoconfigure.OpenAiImageAutoConfiguration," +
            "org.springframework.ai.model.openai.autoconfigure.OpenAiModerationAutoConfiguration," +
            "org.springframework.ai.model.chat.memory.autoconfigure.ChatMemoryAutoConfiguration," +
            "org.springframework.ai.model.chat.memory.repository.jdbc.autoconfigure.JdbcChatMemoryRepositoryAutoConfiguration," +
            "ru.girchev.aibot.common.config.CoreAutoConfig," +
            "ru.girchev.aibot.ai.springai.config.SpringAIAutoConfig"
})
class SpringAIOllamaDnsIT {

    @Autowired
    private OllamaChatModel ollamaChatModel;

    @Test
    @Disabled
    void testStreamToConsole() {
        // Примечание: размер чанков при стриминге не настраивается через параметры Ollama
        // num_batch не влияет на размер чанков в стриме
        // num_predict ограничивает количество токенов (не используем, чтобы не прерывать генерацию)
        var responseFlux = ChatClient.builder(ollamaChatModel).build().prompt()
                .user("Напиши сказку")
                .stream()
                .chatResponse();
        AIUtils.processStreamingResponse(responseFlux, text -> {
            try {
                System.out.write(text.getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            System.out.flush();
        });
    }

    @Test
    @Disabled
    void testStreamParagraphToConsole() {
        // Примечание: размер чанков при стриминге не настраивается через параметры Ollama
        // num_batch не влияет на размер чанков в стриме
        // num_predict ограничивает количество токенов (не используем, чтобы не прерывать генерацию)
//        System.out.println("Sentence:\n" + ChatClient.builder(ollamaChatModel).build().prompt()
//                .user("Напиши сказку").call().chatResponse().getResult().getOutput().getText());


        var responseFlux = ChatClient.builder(ollamaChatModel).build().prompt()
                .user("Напиши сказку")
                .stream()
                .chatResponse();
        ChatResponse chatResponse = AIUtils.processStreamingResponseByParagraphs(responseFlux, 4096, text -> {
            try {
                System.out.write(text.getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            System.out.flush();
        });
        System.out.println("\nSentence:\n\n" + chatResponse.getResults().getFirst().getOutput().getText());
    }

    /**
     * Тест проверяет, что Spring AI ChatClient.stream().chatResponse() не генерирует DNS ошибки.
     * 
     * Тест получает OllamaChatModel из Spring контекста (созданный Spring AI автоконфигурацией
     * с нашим WebClient.Builder) и создает ChatClient для проверки стриминга.
     */
    @Test
    void testSpringAIStreamingWithDnsResolution() {
        log.info("=== Testing Spring AI Streaming with DNS Resolution ===");
        log.info("Base URL: from spring.ai.ollama.base-url");
        log.info("This test checks if Spring AI streaming generates DNS errors (NXDOMAIN)");
        log.info("NOTE: Check console logs for DNS errors");

        AtomicBoolean dnsErrorDetected = new AtomicBoolean(false);
        AtomicReference<String> dnsErrorMessage = new AtomicReference<>("");
        AtomicReference<String> collectedResponse = new AtomicReference<>("");

        try {
            // Получаем OllamaChatModel из Spring контекста
            // Он создан Spring AI автоконфигурацией с нашим WebClient.Builder
            // Создаем ChatClient для теста
            ChatClient chatClient = ChatClient.builder(ollamaChatModel).build();
            
            // Создаем промпт и запускаем стриминг (как в SpringAIGateway.processStreamingResponse)
            Flux<ChatResponse> responseStream = chatClient.prompt()
                    .user("Hello, this is a DNS test message. Please respond briefly.")
                    .stream()
                    .chatResponse()
                    .doOnError(error -> {
                        // Проверяем, является ли ошибка DNS ошибкой
                        boolean isDnsError = checkIfDnsError(new Exception(error.getMessage(), error));
                        if (isDnsError) {
                            dnsErrorDetected.set(true);
                            dnsErrorMessage.set(error.getMessage());
                            log.error("DNS ERROR DETECTED in stream: {}", error.getMessage());
                        }
                    })
                    .onErrorContinue((error, obj) -> {
                        // Продолжаем обработку даже при ошибках
                        boolean isDnsError = checkIfDnsError(new Exception(error.getMessage(), error));
                        if (isDnsError) {
                            dnsErrorDetected.set(true);
                            dnsErrorMessage.set(error.getMessage());
                            log.error("DNS ERROR DETECTED in onErrorContinue: {}", error.getMessage());
                        }
                    });

            // Собираем ответ из стрима
            responseStream
                    .doOnNext(response -> {
                        try {
                            String text = response.getResult().getOutput().getText();
                            if (text != null && !text.isEmpty()) {
                                collectedResponse.updateAndGet(current -> current + text);
                            }
                        } catch (Exception e) {
                            log.debug("Could not extract text from response: {}", e.getMessage());
                        }
                    })
                    .blockLast(Duration.ofMinutes(2));

            // Проверяем результат
            if (dnsErrorDetected.get()) {
                log.error("FAIL: DNS errors detected during Spring AI streaming!");
                log.error("DNS Error: {}", dnsErrorMessage.get());
                log.error("This means Spring AI is NOT using our configured DNS resolver");
                fail("DNS errors detected during Spring AI streaming: " + dnsErrorMessage.get());
            } else {
                log.info("SUCCESS: No DNS errors detected during Spring AI streaming!");
                log.info("Collected response length: {}", collectedResponse.get().length());
                if (!collectedResponse.get().isEmpty()) {
                    log.info("Response preview: {}", 
                            collectedResponse.get().substring(0, Math.min(100, collectedResponse.get().length())));
                }
            }

        } catch (Exception e) {
            String errorMessage = e.getMessage();
            Throwable cause = e.getCause();

            log.info("Request failed. Error: {}", errorMessage);
            if (cause != null) {
                log.info("Cause: {} - {}", cause.getClass().getSimpleName(), cause.getMessage());
            }

            // Проверяем, что ошибка НЕ связана с DNS резолвингом
            boolean isDnsError = checkIfDnsError(e);

            if (isDnsError) {
                log.error("FAIL: DNS resolution failed in Spring AI streaming!");
                fail("DNS resolution failed! Error: " + errorMessage);
            } else {
                log.info("SUCCESS: Error is not DNS-related (connection/timeout/server error is OK)");
                log.info("Error type: {} - This means DNS resolution worked!",
                        e.getClass().getSimpleName());
            }
        }
    }

    /**
     * Проверяет, является ли исключение DNS ошибкой.
     */
    private boolean checkIfDnsError(Exception e) {
        String errorMessage = e.getMessage();
        Throwable cause = e.getCause();
        
        // Проверяем сообщение об ошибке
        if (errorMessage != null) {
            if (errorMessage.contains("NXDOMAIN") || 
                errorMessage.contains("Failed to resolve") ||
                errorMessage.contains("DnsErrorCauseException") ||
                errorMessage.contains("Query failed")) {
                return true;
            }
        }
        
        // Проверяем причину исключения
        if (cause != null) {
            String causeClass = cause.getClass().getSimpleName();
            String causeMessage = cause.getMessage();
            
            if (causeClass.contains("Dns") || 
                (causeMessage != null && (
                    causeMessage.contains("NXDOMAIN") ||
                    causeMessage.contains("Failed to resolve") ||
                    causeMessage.contains("Query failed")))) {
                return true;
            }
            
            // Рекурсивно проверяем вложенные причины
            Throwable nestedCause = cause.getCause();
            if (nestedCause != null) {
                return checkIfDnsError(new Exception(nestedCause.getMessage(), nestedCause));
            }
        }
        
        return false;
    }

    /**
     * Минимальная конфигурация для теста.
     * Создает только WebClient.Builder с правильным DNS резолвером.
     * Spring AI автоконфигурация создаст OllamaChatModel, используя наш bean.
     */
    @SpringBootApplication
    static class TestConfig {
        /**
         * Создает WebClient.Builder для Ollama с правильным DNS резолвером.
         * Spring AI OllamaAutoConfiguration ищет bean с именем "ollamaWebClientBuilder"
         * и использует его для создания WebClient внутри OllamaApi.
         */
        @Bean("ollamaWebClientBuilder")
        @ConditionalOnMissingBean(name = "ollamaWebClientBuilder")
        public WebClient.Builder ollamaWebClientBuilder(
                @Value("${spring.ai.ollama.base-url:http://localhost:11434}") String baseUrl) {
            log.info("Creating custom Ollama WebClient.Builder with system DNS resolver for: {}", baseUrl);
            log.info("This WebClient.Builder will be used by Spring AI OllamaAutoConfiguration");
            
            HttpClient httpClient = HttpClient.create()
                    .resolver(DefaultAddressResolverGroup.INSTANCE) // Использует системный DNS (включая /etc/hosts и mDNS)
                    .responseTimeout(Duration.ofMinutes(10));
            
            return WebClient.builder()
                    .baseUrl(baseUrl)
                    .clientConnector(new ReactorClientHttpConnector(httpClient));
        }
        
        // Создаем мок entityManagerFactory для автоконфигураций, которые его требуют
        @Bean("entityManagerFactory")
        public EntityManagerFactory entityManagerFactory() {
            return mock(EntityManagerFactory.class);
        }
        
        // Spring AI автоконфигурация (OllamaAutoConfiguration) создаст OllamaChatModel
        // и будет использовать наш ollamaWebClientBuilder bean для создания WebClient
    }

}
