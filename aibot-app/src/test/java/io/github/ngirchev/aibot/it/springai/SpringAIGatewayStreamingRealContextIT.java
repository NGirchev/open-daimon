package io.github.ngirchev.aibot.it.springai;

import lombok.extern.slf4j.Slf4j;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import io.github.ngirchev.aibot.ai.springai.config.SpringAIFlywayConfig;
import io.github.ngirchev.aibot.ai.springai.service.SpringAIGateway;
import io.github.ngirchev.aibot.common.ai.command.ChatAICommand;
import io.github.ngirchev.aibot.common.ai.response.AIResponse;
import io.github.ngirchev.aibot.common.ai.response.SpringAIStreamResponse;
import io.github.ngirchev.aibot.common.config.CoreFlywayConfig;
import io.github.ngirchev.aibot.common.config.CoreJpaConfig;
import io.github.ngirchev.aibot.test.TestDatabaseConfiguration;

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.ngirchev.aibot.common.ai.LlmParamNames.MAX_PRICE;
import static io.github.ngirchev.aibot.common.ai.ModelCapabilities.CHAT;

/**
 * Integration test for streaming in conditions close to the real app:
 * real context (SpringAIGateway, SpringAIChatService, real ChatClient/WebClient),
 * only HTTP is mocked — MockWebServer serves SSE with delay between chunks (throttleBody).
 * If the stream is buffered in prod, chunks arrive in one go (span ≈ 0) — test fails.
 * After fixing buffering the test passes (span >= minSpanMs).
 */
@Slf4j
@SpringBootTest(
        classes = SpringAIGatewayStreamingRealContextIT.TestConfig.class,
        properties = {"spring.main.banner-mode=off"}
)
@Import({
        TestDatabaseConfiguration.class,
        CoreFlywayConfig.class,
        CoreJpaConfig.class,
        SpringAIFlywayConfig.class
})
@TestPropertySource(properties = {
        "spring.autoconfigure.exclude=org.springframework.ai.model.chat.memory.autoconfigure.ChatMemoryAutoConfiguration",
        "spring.ai.ollama.base-url=http://127.0.0.1:0",
        "ai-bot.common.bulkhead.enabled=false",
        "ai-bot.common.summarization.max-context-tokens=8000",
        "ai-bot.common.summarization.summary-trigger-threshold=0.7",
        "ai-bot.common.summarization.keep-recent-messages=20",
        "ai-bot.common.summarization.prompt=You are an assistant. Create a summary in JSON. Conversation:",
        "ai-bot.common.manual-conversation-history.enabled=false",
        "ai-bot.common.manual-conversation-history.max-response-tokens=4000",
        "ai-bot.common.manual-conversation-history.default-window-size=20",
        "ai-bot.common.manual-conversation-history.include-system-prompt=true",
        "ai-bot.common.manual-conversation-history.token-estimation-chars-per-token=4",
        "ai-bot.ai.spring-ai.enabled=true",
        "ai-bot.ai.spring-ai.mock=false",
        "ai-bot.ai.spring-ai.history-window-size=20",
        "ai-bot.ai.spring-ai.timeouts.response-timeout-seconds=600",
        "ai-bot.ai.spring-ai.timeouts.stream-timeout-seconds=600",
        "ai-bot.ai.spring-ai.openrouter-auto-rotation.models.enabled=false",
        "ai-bot.ai.spring-ai.serper.api.key=test-key",
        "ai-bot.ai.spring-ai.serper.api.url=https://example.com",
        "ai-bot.ai.spring-ai.models.list[0].name=openrouter/auto",
        "ai-bot.ai.spring-ai.models.list[0].capabilities=CHAT",
        "ai-bot.ai.spring-ai.models.list[0].provider-type=OPENAI",
        "ai-bot.ai.spring-ai.models.list[0].priority=3"
})
class SpringAIGatewayStreamingRealContextIT {

    private static MockWebServer mockWebServer;

    @BeforeAll
    static void startMockServer() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();
    }

    @AfterAll
    static void shutdownMockServer() throws IOException {
        if (mockWebServer != null) {
            mockWebServer.shutdown();
        }
    }

    @DynamicPropertySource
    static void setOpenAiBaseUrl(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.openai.base-url", () -> mockWebServer.url("/").toString());
        registry.add("spring.ai.openai.api-key", () -> "test");
    }

    @Autowired
    private SpringAIGateway springAIGateway;

    /**
     * Real context, real WebClient → MockWebServer with SSE and throttleBody.
     * If stream is buffered, chunks arrive in one go → span ≈ 0 → test fails.
     */
    @Test
    void whenStreamViaRealGateway_thenChunksArriveProgressivelyNotAllAtOnce() {
        int chunkDelayMs = 80;
        int numChunks = 5;
        long minSpanMs = (numChunks - 1) * (chunkDelayMs - 20);

        StringBuilder sseBody = new StringBuilder();
        for (int i = 1; i <= numChunks; i++) {
            sseBody.append("data: {\"choices\":[{\"delta\":{\"content\":\"c").append(i).append("\"}}]}\n\n");
        }
        sseBody.append("data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n");
        sseBody.append("data: [DONE]\n\n");

        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", MediaType.TEXT_EVENT_STREAM_VALUE)
                .setBody(sseBody.toString())
                .throttleBody(64, chunkDelayMs, TimeUnit.MILLISECONDS));

        ChatAICommand command = new ChatAICommand(
                Set.of(CHAT),
                0.7,
                100,
                "You are helpful.",
                "Say hi in one word.",
                true,
                new HashMap<>(),
                createBodyWithMaxPrice()
        );

        AIResponse response = springAIGateway.generateResponse(command);
        assertNotNull(response);
        assertInstanceOf(SpringAIStreamResponse.class, response);

        SpringAIStreamResponse streamResponse = (SpringAIStreamResponse) response;
        assertNotNull(streamResponse.chatResponse());

        List<Long> receiveTimeNanos = new CopyOnWriteArrayList<>();
        streamResponse.chatResponse()
                .doOnNext(chatResponse -> receiveTimeNanos.add(System.nanoTime()))
                .blockLast(Duration.ofSeconds(15));

        assertTrue(receiveTimeNanos.size() >= 2,
                "Should receive at least 2 chunks, got " + receiveTimeNanos.size());

        long firstTime = receiveTimeNanos.getFirst();
        long lastTime = receiveTimeNanos.getLast();
        long spanMs = (lastTime - firstTime) / 1_000_000;

        log.info("Stream chunks: {}, spanMs: {} (min required: {})",
                receiveTimeNanos.size(), spanMs, minSpanMs);

        assertTrue(spanMs >= minSpanMs,
                "Chunks must arrive progressively (span >= " + minSpanMs + " ms), but span was " + spanMs + " ms. " +
                        "If all arrive at once, streaming is buffered somewhere in the chain.");
    }

    private Map<String, Object> createBodyWithMaxPrice() {
        Map<String, Object> body = new HashMap<>();
        body.put(MAX_PRICE, Map.of("prompt", 0.0, "completion", 0.0));
        return body;
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(excludeName = {
            "io.github.ngirchev.aibot.telegram.config.TelegramAutoConfig",
            "io.github.ngirchev.aibot.rest.config.RestAutoConfig",
            "io.github.ngirchev.aibot.ui.config.UIAutoConfig",
            "org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration",
            "org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionAutoConfiguration",
            "org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration",
            "org.springframework.ai.model.openai.autoconfigure.OpenAiImageAutoConfiguration",
            "org.springframework.ai.model.openai.autoconfigure.OpenAiModerationAutoConfiguration"
    })
    static class TestConfig {
    }
}
