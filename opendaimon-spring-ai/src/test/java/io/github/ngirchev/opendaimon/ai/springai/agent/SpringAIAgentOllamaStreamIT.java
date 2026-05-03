package io.github.ngirchev.opendaimon.ai.springai.agent;

import io.github.ngirchev.opendaimon.common.service.AIUtils;
import io.github.ngirchev.opendaimon.common.service.ParagraphBatcher;
import io.netty.resolver.DefaultAddressResolverGroup;
import jakarta.persistence.EntityManagerFactory;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
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

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Manual integration test for the new agent streaming pipeline.
 *
 * <p>Mirrors {@code SpringAIOllamaDnsIT} (same Ollama WebClient, same minimal autoconfig exclusions)
 * but exercises the exact transformation pipeline used by the agent branch:
 *
 * <ol>
 *   <li>{@code chatModel.stream()} — raw Spring AI streaming (what {@code SpringAgentLoopActions}
 *   emits as {@code PARTIAL_ANSWER} events; no filtering, no batching in Spring AI).</li>
 *   <li>{@link ParagraphBatcher} — stateful per-session batcher, owned by the Telegram module,
 *   groups raw chunks into paragraph-sized blocks up to {@link #MAX_MESSAGE_LENGTH} chars.</li>
 * </ol>
 *
 * <p>Each emitted block is rendered by simulating Telegram's {@code editMessageText}: the terminal
 * is cleared (ANSI {@code \033[H\033[2J}) and the accumulated message is printed. When the next
 * block would push the buffer past {@link #MAX_MESSAGE_LENGTH}, a "close" marker is printed and the
 * buffer resets — imitating Telegram starting a new message when the 4096-char limit is hit.
 *
 * <p>Compared to {@code SpringAIOllamaDnsIT#testStreamParagraphToConsole} (which uses
 * {@code AIUtils.processStreamingResponseByParagraphs} — the Gateway path), this test verifies
 * the new agent path where paragraph batching has moved from {@code SpringAgentLoopActions}
 * into the Telegram-side {@link ParagraphBatcher}.
 */
@Slf4j
//@Disabled("Manual test: run locally in a real terminal to visually verify agent-style streaming")
@SpringBootTest(classes = SpringAIAgentOllamaStreamIT.TestConfig.class)
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
class SpringAIAgentOllamaStreamIT {

    /**
     * Telegram message limit — mirrors {@code TelegramProperties.maxMessageLength} default
     * (source of truth is the Telegram module config; duplicated here because this test does
     * not load Spring Boot's Telegram configuration).
     */
    private static final int MAX_MESSAGE_LENGTH = 4096;
    /** ANSI clear-screen + cursor-home — simulates Telegram re-rendering an edited message. */
    private static final String ANSI_CLEAR_SCREEN = "\033[H\033[2J";

    @Autowired
    private OllamaChatModel ollamaChatModel;

    /**
     * <pre>
     * mvn test -pl opendaimon-spring-ai -am -Dtest=SpringAIAgentOllamaStreamIT#testAgentStreamToConsoleEditSimulation
     * </pre>
     *
     * <p>Manual test: run locally with Ollama available on {@code localhost:11434} to see
     * the growing "Telegram message" re-rendered to the console on each paragraph block.
     * Disabled in CI; remove {@code @Disabled} for a local run (and run in a real terminal,
     * not the IDE console — ANSI clear is a no-op there).
     */
    @Test
    void testAgentStreamToConsoleEditSimulation() {
        Flux<ChatResponse> responseFlux = ChatClient.builder(ollamaChatModel).build()
                .prompt()
                .user("Write a 3-paragraph short tale about a dragon and a clever mouse, "
                        + "with clear paragraph breaks between the setup, the conflict, and the resolution.")
                .stream()
                .chatResponse();

        ParagraphBatcher batcher = new ParagraphBatcher(MAX_MESSAGE_LENGTH);
        StringBuilder currentMessage = new StringBuilder();
        AtomicInteger messageNumber = new AtomicInteger(1);
        AtomicInteger totalBlocks = new AtomicInteger(0);
        AtomicReference<String> lastRender = new AtomicReference<>("");

        responseFlux
                .map(AIUtils::extractText)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .filter(text -> !text.isEmpty())
                .doOnNext(chunk -> emitBlocks(
                        batcher.feed(chunk),
                        currentMessage, messageNumber, totalBlocks, lastRender))
                .blockLast(Duration.ofMinutes(5));

        // Stream finished — drain remaining buffered text from the batcher.
        emitBlocks(batcher.flush(), currentMessage, messageNumber, totalBlocks, lastRender);

        String finalAnswer = lastRender.get();
        log.info("Agent stream finished: totalBlocks={}, totalMessages={}, finalAnswerLength={}",
                totalBlocks.get(), messageNumber.get(), finalAnswer.length());
        System.out.println();
        System.out.println("=== FINAL ANSWER ===");
        System.out.println(finalAnswer);

        assertNotNull(finalAnswer, "Rendered answer must not be null");
        assertFalse(finalAnswer.isBlank(), "Rendered answer must not be blank");
        assertTrue(totalBlocks.get() >= 1,
                "At least one paragraph block must be emitted from ParagraphBatcher");
    }

    private static void emitBlocks(List<String> blocks,
                                   StringBuilder currentMessage,
                                   AtomicInteger messageNumber,
                                   AtomicInteger totalBlocks,
                                   AtomicReference<String> lastRender) {
        for (String block : blocks) {
            totalBlocks.incrementAndGet();

            int joinedLength = currentMessage.length() == 0
                    ? block.length()
                    : currentMessage.length() + 2 + block.length();

            if (joinedLength > MAX_MESSAGE_LENGTH) {
                System.out.println();
                System.out.println("--- message #" + messageNumber.getAndIncrement()
                        + " closed (length=" + currentMessage.length() + ") ---");
                currentMessage.setLength(0);
            }
            if (currentMessage.length() > 0) {
                currentMessage.append("\n\n");
            }
            currentMessage.append(block);

            String rendered = currentMessage.toString();
            lastRender.set(rendered);
            System.out.print(ANSI_CLEAR_SCREEN);
            System.out.println("--- message #" + messageNumber.get()
                    + " (blocks=" + totalBlocks.get() + ", length=" + rendered.length() + ") ---");
            System.out.println(rendered);
            System.out.flush();
        }
    }

    /**
     * Minimal test configuration — same shape as {@code SpringAIOllamaDnsIT.TestConfig}.
     * Spring AI's {@code OllamaAutoConfiguration} picks up our {@code ollamaWebClientBuilder}
     * and wires an {@link OllamaChatModel} with the system DNS resolver (so {@code localhost}
     * and {@code .local} hosts resolve correctly).
     */
    @SpringBootApplication
    static class TestConfig {

        @Bean("ollamaWebClientBuilder")
        @ConditionalOnMissingBean(name = "ollamaWebClientBuilder")
        public WebClient.Builder ollamaWebClientBuilder(
                @Value("${spring.ai.ollama.base-url:http://localhost:11434}") String baseUrl) {
            log.info("Creating custom Ollama WebClient.Builder with system DNS resolver for: {}", baseUrl);

            HttpClient httpClient = HttpClient.create()
                    .resolver(DefaultAddressResolverGroup.INSTANCE)
                    .responseTimeout(Duration.ofMinutes(10));

            return WebClient.builder()
                    .baseUrl(baseUrl)
                    .clientConnector(new ReactorClientHttpConnector(httpClient));
        }

        @Bean("entityManagerFactory")
        public EntityManagerFactory entityManagerFactory() {
            return mock(EntityManagerFactory.class);
        }
    }
}
