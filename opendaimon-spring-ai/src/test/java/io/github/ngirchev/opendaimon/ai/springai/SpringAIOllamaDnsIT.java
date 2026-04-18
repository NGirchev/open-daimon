package io.github.ngirchev.opendaimon.ai.springai;

import io.github.ngirchev.opendaimon.ai.springai.agent.ReActAgentExecutor;
import io.github.ngirchev.opendaimon.ai.springai.agent.SpringAgentLoopActions;
import io.github.ngirchev.opendaimon.common.agent.AgentLoopFsmFactory;
import io.github.ngirchev.opendaimon.common.agent.AgentRequest;
import io.github.ngirchev.opendaimon.common.agent.AgentStrategy;
import io.github.ngirchev.opendaimon.common.agent.AgentStreamEvent;
import io.github.ngirchev.opendaimon.common.service.AIUtils;
import io.netty.resolver.DefaultAddressResolverGroup;
import jakarta.persistence.EntityManagerFactory;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.tool.resolution.StaticToolCallbackResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
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

import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
//@Disabled("Manual test: run locally to verify streaming by paragraphs to console")
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
    "spring.ai.ollama.chat.options.model=deepseek-r1:1.5b",
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
     * mvn test -pl opendaimon-spring-ai -Dtest=SpringAIOllamaDnsIT#testStreamToConsole (not in idea console!!!)
     * If you run with -am, add: -Dsurefire.failIfNoSpecifiedTests=false
     * Manual test: run locally with Ollama to see streaming output in console.
     * Disabled in CI; remove @Disabled for a local run.
     */
    @Test
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
     * mvn test -pl opendaimon-spring-ai -Dtest=SpringAIOllamaDnsIT#testStreamParagraphToConsole (not in idea console!!!)
     * If you run with -am, add: -Dsurefire.failIfNoSpecifiedTests=false
     * Manual test: run locally with Ollama to see paragraph-by-paragraph streaming in console.
     * Disabled in CI; remove @Disabled for a local run.
     */
    @Test
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
     * mvn test -pl opendaimon-spring-ai -Dtest=SpringAIOllamaDnsIT#testReActStreamToConsoleSnapshots (not in idea console!!!)
     * If you run with -am, add: -Dsurefire.failIfNoSpecifiedTests=false
     * Manual test: run locally with Ollama to inspect REACT stream events at a higher level
     * than direct ChatClient streaming. The console is updated with a full snapshot on each event.
     *
     * <p>Expected behavior: FINAL_ANSWER_CHUNK events are emitted progressively during think-step
     * streaming (not as a burst right before terminal event).
     *
     * <p>Flow under test:
     * <ol>
     *   <li>Build a real REACT executor (same stack as production agent loop).</li>
     *   <li>Subscribe to {@code executeStream(...)} and observe every {@link AgentStreamEvent}.</li>
     *   <li>Print a "console snapshot" on each event (thinking/tool/observation/final text so far).</li>
     *   <li>Assert that final chunks exist and are not emitted only at the very end.</li>
     * </ol>
     */
    @Test
    void testReActStreamToConsoleSnapshots() {
        // Build an empty tool-calling manager: this test focuses on streaming behavior, not tool execution.
        ToolCallingManager toolCallingManager = DefaultToolCallingManager.builder()
                .toolCallbackResolver(new StaticToolCallbackResolver(List.of()))
                .build();

        // Reuse the same loop actions and FSM executor used by REACT runtime code.
        SpringAgentLoopActions loopActions = new SpringAgentLoopActions(
                ollamaChatModel,
                toolCallingManager,
                List.of(),
                null,
                null
        );
        ReActAgentExecutor reActAgentExecutor = new ReActAgentExecutor(AgentLoopFsmFactory.create(loopActions));

        // Keep the prompt stable for reproducible manual checks across local runs.
        AgentRequest request = new AgentRequest(
                """
                Write a short tale
                """,
                "react-console-" + System.currentTimeMillis(),
                Map.of(),
                5,
                Set.of(),
                AgentStrategy.REACT
        );

        // Collect full raw stream for post-run assertions and timing analysis.
        List<AgentStreamEvent> events = new ArrayList<>();
        // Keep latest values per channel to print readable snapshots every time any event arrives.
        AtomicReference<String> lastThinking = new AtomicReference<>("");
        AtomicReference<String> lastToolCall = new AtomicReference<>("");
        AtomicReference<String> lastObservation = new AtomicReference<>("");
        // Keep terminal answer text if executor emits FINAL_ANSWER/MAX_ITERATIONS/ERROR.
        AtomicReference<String> terminalAnswer = new AtomicReference<>("");
        // Reconstruct streamed final text from FINAL_ANSWER_CHUNK sequence.
        StringBuilder streamedFinalAnswer = new StringBuilder();
        // Track snapshot order and elapsed time for manual diagnostics in console output.
        AtomicReference<Integer> snapshotCounter = new AtomicReference<>(0);
        Instant startedAt = Instant.now();

        // Subscribe to event stream and print a full state snapshot after every event.
        // blockLast(...) returns the terminal event we can assert on.
        AgentStreamEvent terminalEvent = reActAgentExecutor.executeStream(request)
                .doOnNext(event -> {
                    events.add(event);
                    // Update our latest per-channel view based on event type.
                    switch (event.type()) {
                        case THINKING -> {
                            // Some THINKING events may be empty markers; keep only meaningful reasoning text.
                            if (event.content() != null && !event.content().isBlank()) {
                                lastThinking.set(event.content());
                            }
                        }
                        case TOOL_CALL -> lastToolCall.set(event.content());
                        case OBSERVATION -> lastObservation.set(event.content());
                        case FINAL_ANSWER_CHUNK -> {
                            // Accumulate visible answer text exactly as streamed.
                            if (event.content() != null) {
                                streamedFinalAnswer.append(event.content());
                            }
                        }
                        // Keep terminal payload if available (normal completion or max-iterations fallback).
                        case FINAL_ANSWER, MAX_ITERATIONS -> terminalAnswer.set(event.content());
                        // Error path still carries terminal payload useful for debugging.
                        case ERROR -> terminalAnswer.set(event.content());
                        default -> {
                            // No-op for METADATA and unknown future event types.
                        }
                    }

                    // Print one aggregated snapshot per event so manual runs can "see streaming" in real time.
                    int snapshotNumber = snapshotCounter.updateAndGet(current -> current + 1);
                    long elapsedMs = Duration.between(startedAt, Instant.now()).toMillis();
                    String snapshot = """
                            
                            ===== REACT SNAPSHOT #%d (+%d ms) =====
                            event: %s (iteration=%d)
                            thinking:
                            %s
                            
                            tool_call:
                            %s
                            
                            observation:
                            %s
                            
                            final_answer_so_far:
                            %s
                            ===== END SNAPSHOT =====
                            """.formatted(
                            snapshotNumber,
                            elapsedMs,
                            event.type(),
                            event.iteration(),
                            truncateForConsole(lastThinking.get(), 1200),
                            truncateForConsole(lastToolCall.get(), 600),
                            truncateForConsole(lastObservation.get(), 600),
                            truncateForConsole(streamedFinalAnswer.toString(), 1200)
                    );
                    System.out.print(snapshot);
                    System.out.flush();
                })
                .blockLast(Duration.ofMinutes(5));

        // Basic stream sanity: we expect a terminal event and at least one stream event.
        assertNotNull(terminalEvent);
        assertFalse(events.isEmpty());

        // Core expectation: streaming path must emit intermediate final chunks.
        List<AgentStreamEvent> finalChunkEvents = events.stream()
                .filter(event -> event.type() == AgentStreamEvent.EventType.FINAL_ANSWER_CHUNK)
                .toList();
        assertFalse(finalChunkEvents.isEmpty(), "Expected at least one FINAL_ANSWER_CHUNK event");

        // If terminal event has no visible payload, fallback to reconstructed stream content.
        if (terminalAnswer.get() == null || terminalAnswer.get().isBlank()) {
            terminalAnswer.set(streamedFinalAnswer.toString());
        }
        assertFalse(terminalAnswer.get() == null || terminalAnswer.get().isBlank());

        // Build timing markers to detect "all chunks came at the end" regressions.
        Instant firstThinkingAt = events.stream()
                .filter(event -> event.type() == AgentStreamEvent.EventType.THINKING)
                .map(AgentStreamEvent::timestamp)
                .findFirst()
                .orElse(null);
        Instant firstChunkAt = finalChunkEvents.stream()
                .map(AgentStreamEvent::timestamp)
                .findFirst()
                .orElse(null);
        Instant lastChunkAt = finalChunkEvents.getLast().timestamp();
        Instant terminalAt = terminalEvent.timestamp();

        if (firstThinkingAt != null && firstChunkAt != null) {
            // Time to first visible answer chunk after think started.
            long thinkToFirstChunkMs = Duration.between(firstThinkingAt, firstChunkAt).toMillis();
            // Total span between first and last final chunk; should be > 0 for progressive delivery.
            long chunkBurstWindowMs = Duration.between(firstChunkAt, lastChunkAt).toMillis();
            // Distance from first chunk to terminal event; should be visible (not immediate).
            long firstChunkToTerminalMs = Duration.between(firstChunkAt, terminalAt).toMillis();
            System.out.printf(
                    "%nREACT timing: THINKING→first chunk=%d ms, chunk burst window=%d ms, first chunk→terminal=%d ms, chunks=%d%n",
                    thinkToFirstChunkMs,
                    chunkBurstWindowMs,
                    firstChunkToTerminalMs,
                    finalChunkEvents.size()
            );
            // Guardrail #1: reject near-zero burst windows (symptom of end-of-stream batch emit).
            assertTrue(
                    chunkBurstWindowMs >= 200,
                    "Expected progressive FINAL_ANSWER_CHUNK streaming, but chunks arrived as a burst"
            );
            // Guardrail #2: first chunk must appear noticeably before terminal event.
            assertTrue(
                    firstChunkToTerminalMs >= 200,
                    "Expected first FINAL_ANSWER_CHUNK before terminal event by a visible margin"
            );
        }
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

    private static String truncateForConsole(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        String normalized = text.trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength) + "...";
    }

    /**
     * Minimal test configuration.
     * Creates only WebClient.Builder with proper DNS resolver.
     * Spring AI autoconfiguration will create OllamaChatModel using this bean.
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration
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
