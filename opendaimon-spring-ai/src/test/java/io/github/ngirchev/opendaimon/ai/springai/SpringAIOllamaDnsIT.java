package io.github.ngirchev.opendaimon.ai.springai;

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
import io.github.ngirchev.opendaimon.common.service.AIUtils;

import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for DNS resolution in Spring AI Ollama when streaming.
 *
 * Verifies that Spring AI ChatClient.stream().chatResponse() with a .local domain
 * does not produce DNS errors (NXDOMAIN).
 *
 * Test uses minimal config with our WebClient.Builder and obtains OllamaChatModel
 * from Spring context (as in the real application).
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
    "open-daimon.ai.spring-ai.openrouter-auto-rotation.models.enabled=false",
    // Exclude autoconfigurations not needed for this test
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
            "io.github.ngirchev.opendaimon.common.config.CoreAutoConfig," +
            "io.github.ngirchev.opendaimon.ai.springai.config.SpringAIAutoConfig"
})
class SpringAIOllamaDnsIT {

    @Value("${spring.ai.ollama.base-url}")
    private String ollamaBaseUrl;

    @Autowired
    private OllamaChatModel ollamaChatModel;

    /**
     * .\mvnw.cmd test -pl opendaimon-spring-ai -Dtest=SpringAIOllamaDnsIT#testStreamToConsole (not in idea console!!!)
     * Manual test: run locally with Ollama to see streaming output in console.
     * Disabled in CI; remove @Disabled for a local run.
     */
    @Test
    @Disabled("Manual test: run locally to verify streaming to console")
    void testStreamToConsole() {
        // Note: chunk size in streaming is not configurable via Ollama params;
        // num_batch does not affect stream chunk size; num_predict limits tokens (we skip it to avoid cutting generation)
        var responseFlux = ChatClient.builder(ollamaChatModel).build().prompt()
                .user("Write a short tale")
                .stream()
                .chatResponse();
        ChatResponse response = AIUtils.processStreamingResponse(responseFlux, text -> {
            try {
                System.out.write(text.getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            System.out.flush();
        });
        assertNotNull(response);
        assertNotNull(response.getResult());
        assertNotNull(response.getResult().getOutput());
        String text = response.getResult().getOutput().getText();
        assertNotNull(text);
        assertFalse(text.isEmpty());
    }

    /**
     *.\mvnw.cmd test -pl opendaimon-spring-ai -Dtest=SpringAIOllamaDnsIT#testStreamParagraphToConsole (not in idea console!!!)
     * Manual test: run locally with Ollama to see paragraph-by-paragraph streaming in console.
     * Disabled in CI; remove @Disabled for a local run.
     */
    @Test
//    @Disabled("Manual test: run locally to verify streaming by paragraphs to console")
    void testStreamParagraphToConsole() {
        // Note: chunk size in streaming is not configurable via Ollama params
        var responseFlux = ChatClient.builder(ollamaChatModel).build().prompt()
                .user("Write a short tale")
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
        assertNotNull(chatResponse);
        assertFalse(chatResponse.getResults().isEmpty());
        assertNotNull(chatResponse.getResults().getFirst().getOutput());
        String text = chatResponse.getResults().getFirst().getOutput().getText();
        assertNotNull(text);
        assertFalse(text.isEmpty());
        System.out.println("\nSentence:\n\n" + text);
    }

    /**
     * Verifies that Spring AI ChatClient.stream().chatResponse() does not produce DNS errors.
     *
     * Test obtains OllamaChatModel from Spring context (created by Spring AI autoconfiguration
     * with our WebClient.Builder) and creates ChatClient to verify streaming.
     */
    @Test
    void testSpringAIStreamingWithDnsResolution() {
        log.info("=== Testing Spring AI Streaming with DNS Resolution ===");
        log.info("Base URL: {} (Ollama host from config)", ollamaBaseUrl);
        log.info("This test checks if Spring AI streaming generates DNS errors (NXDOMAIN)");
        log.info("NOTE: Check console logs for DNS errors");

        AtomicBoolean dnsErrorDetected = new AtomicBoolean(false);
        AtomicReference<String> dnsErrorMessage = new AtomicReference<>("");
        AtomicReference<String> collectedResponse = new AtomicReference<>("");

        try {
            // Get OllamaChatModel from Spring context (created by Spring AI autoconfig with our WebClient.Builder)
            // Create ChatClient for the test
            ChatClient chatClient = ChatClient.builder(ollamaChatModel).build();
            
            // Build prompt and start streaming (as in SpringAIGateway.processStreamingResponse)
            Flux<ChatResponse> responseStream = chatClient.prompt()
                    .user("Hello, this is a DNS test message. Please respond briefly.")
                    .stream()
                    .chatResponse()
                    .doOnError(error -> {
                        // Check if error is a DNS error
                        boolean isDnsError = checkIfDnsError(new Exception(error.getMessage(), error));
                        if (isDnsError) {
                            dnsErrorDetected.set(true);
                            dnsErrorMessage.set(error.getMessage());
                            log.error("DNS ERROR DETECTED in stream: {}", error.getMessage());
                        }
                    })
                    .onErrorContinue((error, obj) -> {
                        // Continue processing even on errors
                        boolean isDnsError = checkIfDnsError(new Exception(error.getMessage(), error));
                        if (isDnsError) {
                            dnsErrorDetected.set(true);
                            dnsErrorMessage.set(error.getMessage());
                            log.error("DNS ERROR DETECTED in onErrorContinue: {}", error.getMessage());
                        }
                    });

            // Collect response from stream
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

            // Check result
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

            // Verify error is NOT related to DNS resolution
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
     * Checks whether the exception is a DNS error.
     */
    private boolean checkIfDnsError(Exception e) {
        String errorMessage = e.getMessage();
        Throwable cause = e.getCause();
        
        // Check error message
        if (errorMessage != null) {
            if (errorMessage.contains("NXDOMAIN") || 
                errorMessage.contains("Failed to resolve") ||
                errorMessage.contains("DnsErrorCauseException") ||
                errorMessage.contains("Query failed")) {
                return true;
            }
        }
        
        // Check exception cause
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
            
            // Recursively check nested causes
            Throwable nestedCause = cause.getCause();
            if (nestedCause != null) {
                return checkIfDnsError(new Exception(nestedCause.getMessage(), nestedCause));
            }
        }
        
        return false;
    }

    /**
     * Minimal test configuration.
     * Creates only WebClient.Builder with proper DNS resolver.
     * Spring AI autoconfiguration will create OllamaChatModel using this bean.
     */
    @SpringBootApplication
    static class TestConfig {
        /**
         * Creates WebClient.Builder for Ollama with proper DNS resolver.
         * Spring AI OllamaAutoConfiguration looks for bean named "ollamaWebClientBuilder"
         * and uses it to create WebClient inside OllamaApi.
         */
        @Bean("ollamaWebClientBuilder")
        @ConditionalOnMissingBean(name = "ollamaWebClientBuilder")
        public WebClient.Builder ollamaWebClientBuilder(
                @Value("${spring.ai.ollama.base-url:http://localhost:11434}") String baseUrl) {
            log.info("Creating custom Ollama WebClient.Builder with system DNS resolver for: {}", baseUrl);
            log.info("This WebClient.Builder will be used by Spring AI OllamaAutoConfiguration");
            
            HttpClient httpClient = HttpClient.create()
                    .resolver(DefaultAddressResolverGroup.INSTANCE) // Use system DNS (including /etc/hosts and mDNS)
                    .responseTimeout(Duration.ofMinutes(10));
            
            return WebClient.builder()
                    .baseUrl(baseUrl)
                    .clientConnector(new ReactorClientHttpConnector(httpClient));
        }
        
        // Mock entityManagerFactory for autoconfigurations that require it
        @Bean("entityManagerFactory")
        public EntityManagerFactory entityManagerFactory() {
            return mock(EntityManagerFactory.class);
        }
        
        // Spring AI autoconfiguration (OllamaAutoConfiguration) will create OllamaChatModel
        // and use our ollamaWebClientBuilder bean to create WebClient
    }

}
