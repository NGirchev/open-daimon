package io.github.ngirchev.opendaimon.it.springai;

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
import io.github.ngirchev.opendaimon.ai.springai.config.SpringAIFlywayConfig;
import io.github.ngirchev.opendaimon.ai.springai.service.SpringAIGateway;
import io.github.ngirchev.opendaimon.common.ai.command.ChatAICommand;
import io.github.ngirchev.opendaimon.common.ai.response.AIResponse;
import io.github.ngirchev.opendaimon.common.ai.response.SpringAIStreamResponse;
import io.github.ngirchev.opendaimon.common.config.CoreFlywayConfig;
import io.github.ngirchev.opendaimon.common.config.CoreJpaConfig;
import io.github.ngirchev.opendaimon.test.TestDatabaseConfiguration;

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.ngirchev.opendaimon.common.ai.LlmParamNames.MAX_PRICE;
import static io.github.ngirchev.opendaimon.common.ai.ModelCapabilities.CHAT;

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
        "open-daimon.common.bulkhead.enabled=false",
        "open-daimon.common.summarization.max-context-tokens=8000",
        "open-daimon.common.summarization.summary-trigger-threshold=0.7",
        "open-daimon.common.summarization.keep-recent-messages=20",
        "open-daimon.common.summarization.prompt=You are an assistant. Create a summary in JSON. Conversation:",
        "open-daimon.common.manual-conversation-history.enabled=false",
        "open-daimon.common.manual-conversation-history.max-response-tokens=4000",
        "open-daimon.common.manual-conversation-history.default-window-size=20",
        "open-daimon.common.manual-conversation-history.include-system-prompt=true",
        "open-daimon.common.manual-conversation-history.token-estimation-chars-per-token=4",
        "open-daimon.ai.spring-ai.enabled=true",
        "open-daimon.ai.spring-ai.mock=false",
        "open-daimon.ai.spring-ai.history-window-size=20",
        "open-daimon.ai.spring-ai.timeouts.response-timeout-seconds=600",
        "open-daimon.ai.spring-ai.timeouts.stream-timeout-seconds=600",
        "open-daimon.ai.spring-ai.openrouter-auto-rotation.models.enabled=false",
        "open-daimon.ai.spring-ai.serper.api.key=test-key",
        "open-daimon.ai.spring-ai.serper.api.url=https://example.com",
        "open-daimon.ai.spring-ai.models.list[0].name=openrouter/auto",
        "open-daimon.ai.spring-ai.models.list[0].capabilities=CHAT",
        "open-daimon.ai.spring-ai.models.list[0].provider-type=OPENAI",
        "open-daimon.ai.spring-ai.models.list[0].priority=3"
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
     * Noise floor: when buffered, sequential doOnNext calls differ by ~0.01 ms.
     * With real progressive delivery (throttleBody 80 ms), gaps are ~80 ms.
     * 5 ms separates the two cases with a 16× margin on each side.
     */
    private static final long GAP_NOISE_FLOOR_MS = 5;

    /**
     * Real context, real WebClient → MockWebServer with SSE and throttleBody.
     * <p>
     * Instead of checking total span (fragile in CI), we count how many
     * inter-chunk gaps are above the noise floor. If buffered, ALL gaps ≈ 0;
     * if progressive, at least one gap will be clearly above the noise floor.
     */
    @Test
    void whenStreamViaRealGateway_thenChunksArriveProgressivelyNotAllAtOnce() {
        int chunkDelayMs = 80;
        int numChunks = 5;

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

        int significantGaps = 0;
        StringBuilder gapDetails = new StringBuilder();
        for (int i = 1; i < receiveTimeNanos.size(); i++) {
            long gapMs = (receiveTimeNanos.get(i) - receiveTimeNanos.get(i - 1)) / 1_000_000;
            gapDetails.append(gapMs).append("ms ");
            if (gapMs > GAP_NOISE_FLOOR_MS) {
                significantGaps++;
            }
        }

        log.info("Stream chunks: {}, inter-chunk gaps: [{}], significant (>{} ms): {}",
                receiveTimeNanos.size(), gapDetails.toString().trim(), GAP_NOISE_FLOOR_MS, significantGaps);

        assertTrue(significantGaps >= 1,
                "At least 1 inter-chunk gap must be > " + GAP_NOISE_FLOOR_MS + " ms (progressive delivery), " +
                        "but found " + significantGaps + " significant gaps. Gaps: [" + gapDetails.toString().trim() + "]. " +
                        "If 0, all chunks arrived in a single burst (stream is buffered).");
    }

    private Map<String, Object> createBodyWithMaxPrice() {
        Map<String, Object> body = new HashMap<>();
        body.put(MAX_PRICE, Map.of("prompt", 0.0, "completion", 0.0));
        return body;
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(excludeName = {
            "io.github.ngirchev.opendaimon.telegram.config.TelegramAutoConfig",
            "io.github.ngirchev.opendaimon.rest.config.RestAutoConfig",
            "io.github.ngirchev.opendaimon.ui.config.UIAutoConfig",
            "org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration",
            "org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionAutoConfiguration",
            "org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration",
            "org.springframework.ai.model.openai.autoconfigure.OpenAiImageAutoConfiguration",
            "org.springframework.ai.model.openai.autoconfigure.OpenAiModerationAutoConfiguration"
    })
    static class TestConfig {
    }
}
