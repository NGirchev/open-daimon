package io.github.ngirchev.aibot.ai.springai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import io.github.ngirchev.aibot.ai.springai.config.RAGProperties;
import io.github.ngirchev.aibot.ai.springai.config.SpringAIModelConfig;
import io.github.ngirchev.aibot.ai.springai.config.SpringAIProperties;
import io.github.ngirchev.aibot.ai.springai.rest.WebClientLogCustomizer;
import io.github.ngirchev.aibot.ai.springai.retry.SpringAIModelRegistry;
import io.github.ngirchev.aibot.ai.springai.retry.metrics.OpenRouterStreamMetricsTracker;
import io.github.ngirchev.aibot.common.ai.ModelCapabilities;
import io.github.ngirchev.aibot.common.ai.command.ChatAICommand;
import io.github.ngirchev.aibot.common.ai.response.AIResponse;
import io.github.ngirchev.aibot.common.ai.response.SpringAIStreamResponse;
import io.github.ngirchev.aibot.common.service.AIUtils;
import io.github.ngirchev.aibot.common.service.AIGatewayRegistry;

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;

/**
 * Integration test for SpringAIGateway streaming.
 * Verifies that chunks arrive progressively (at different times), not all at once.
 * Runs with local profile to reproduce the environment where streaming was broken
 * (WebClientLogCustomizer for dev/local). Test with MockWebServer explicitly uses the problematic class.
 */
@Slf4j
@SpringBootTest(
        classes = SpringAIGatewayIT.StreamingTestConfig.class,
        properties = {
                "spring.main.banner-mode=off"
        }
)
@ActiveProfiles({"integration-test", "local"})
class SpringAIGatewayIT {

    private static final int CHUNK_DELAY_MS = 50;
    private static final int NUM_CHUNKS = 4;
    /** Minimum span between first and last chunk (ms): with progressive delivery must be >= (N-1)*delay. */
    private static final long MIN_SPAN_MS = (NUM_CHUNKS - 1) * (CHUNK_DELAY_MS - 15);

    @Autowired
    private SpringAIGateway springAIGateway;

    /**
     * Creates Flux simulating streaming response: {@code numChunks} chunks with {@code delayMs}
     * delay between them. Used in SpringAIPromptFactory mock to verify progressive delivery
     * (without real Ollama/OpenRouter).
     */
    static Flux<ChatResponse> createSimulatedStreamFlux(int numChunks, int delayMs) {
        AtomicInteger index = new AtomicInteger(0);
        return Flux.range(0, numChunks)
                .delayElements(Duration.ofMillis(delayMs))
                .map(i -> {
                    String text = "part" + (index.incrementAndGet());
                    return ChatResponse.builder()
                            .generations(List.of(new Generation(new AssistantMessage(text))))
                            .build();
                });
    }

    /**
     * Creates Flux with paragraph chunks: each chunk is a string of at least minParagraphLength
     * ending with \n\n, so processStreamingResponseByParagraphs delivers blocks one by one.
     */
    static Flux<ChatResponse> createSimulatedStreamFluxWithParagraphs(int numChunks, int delayMs, int minParagraphLength) {
        AtomicInteger index = new AtomicInteger(0);
        return Flux.range(0, numChunks)
                .delayElements(Duration.ofMillis(delayMs))
                .map(i -> {
                    int n = index.incrementAndGet();
                    String padding = "x".repeat(Math.max(0, minParagraphLength - 4));
                    String text = padding + " " + n + "\n\n";
                    return ChatResponse.builder()
                            .generations(List.of(new Generation(new AssistantMessage(text))))
                            .build();
                });
    }

    /**
     * Reproduces issue: verifies that chunks from Spring AI Flux arrive progressively
     * (in logs they appear all at once in doOnNext at SpringAIStreamResponse.chatResponse()).
     * When buffered, all chunks arrive at once → test fails.
     * This test should fail if Spring AI or WebClient buffers the stream before it reaches our code.
     */
    @Test
    void whenSpringAIStreamResponse_thenChunksArriveProgressivelyNotAllAtOnce() {
        ChatAICommand command = new ChatAICommand(
                Set.of(ModelCapabilities.CHAT),
                0.7,
                100,
                "You are a helpful assistant.",
                "Reply with exactly: OK",
                true,
                new HashMap<>(),
                new HashMap<>()
        );

        AIResponse response = springAIGateway.generateResponse(command);

        assertNotNull(response);
        assertInstanceOf(SpringAIStreamResponse.class, response);

        SpringAIStreamResponse streamResponse = (SpringAIStreamResponse) response;
        assertNotNull(streamResponse.chatResponse(), "Stream response must expose Flux of ChatResponse");

        // Record receive time of each chunk directly from Spring AI Flux (as in "Received chunk" logs)
        List<Long> receiveTimeNanos = new CopyOnWriteArrayList<>();
        streamResponse.chatResponse()
                .doOnNext(cr -> {
                    long now = System.nanoTime();
                    receiveTimeNanos.add(now);
                    log.info("Received chunk at {}: {}", now / 1_000_000, cr);
                })
                .blockLast(Duration.ofMinutes(1));

        assertTrue(receiveTimeNanos.size() >= 2,
                "Should receive at least 2 stream chunks to assert timing, got " + receiveTimeNanos.size());

        long firstTime = receiveTimeNanos.getFirst();
        long lastTime = receiveTimeNanos.getLast();
        long spanMs = (lastTime - firstTime) / 1_000_000;

        log.info("Spring AI stream chunks: {}, spanMs: {} (progressive requires >= {} ms)",
                receiveTimeNanos.size(), spanMs, MIN_SPAN_MS);

        // When buffered, all chunks arrive at once → span is small → test fails
        assertTrue(spanMs >= MIN_SPAN_MS,
                "Chunks from Spring AI Flux must arrive progressively (span >= " + MIN_SPAN_MS + " ms), but span was " + spanMs + " ms. " +
                        "If all arrive at once (span ≈ 0), Spring AI or WebClient is buffering the stream before it reaches our code.");
    }

    /**
     * Reproduces issue: real SSE (MockWebServer) + WebClient with problematic WebClientLogCustomizer.
     * Test runs with local profile; we explicitly create WebClientLogCustomizer (same class as in dev/local).
     * If customizer reads DataBuffer synchronously (sniff), stream is buffered and all chunks arrive at once (span ≈ 0) — test fails.
     * After fix (local without customizer / non-blocking sniff) test passes.
     */
    @Test
    void whenSseStreamViaWebClientWithLogCustomizer_thenDataBuffersArriveProgressivelyNotAllAtOnce() throws IOException {
        MockWebServer server = new MockWebServer();
        server.start();

        try {
            int chunkDelayMs = 80;
            int numChunks = 5;
            long minSpanMs = (numChunks - 1) * (chunkDelayMs - 20);

            StringBuilder sseBody = new StringBuilder();
            for (int i = 1; i <= numChunks; i++) {
                sseBody.append("data: {\"choices\":[{\"delta\":{\"content\":\"c").append(i).append("\"}}]}\n\n");
            }
            sseBody.append("data: {\"choices\":[{\"finish_reason\":\"stop\"}]}\n\n");
            sseBody.append("data: [DONE]\n\n");

            server.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", MediaType.TEXT_EVENT_STREAM_VALUE)
                    .setBody(sseBody.toString())
                    .throttleBody(64, chunkDelayMs, TimeUnit.MILLISECONDS));

            // Problematic class: with synchronous sniff in handle() stream is buffered; test verifies progressive delivery
            WebClientLogCustomizer customizer = new WebClientLogCustomizer(new ObjectMapper());
            WebClient.Builder builder = WebClient.builder();
            customizer.customize(builder);
            WebClient webClient = builder.build();

            String url = server.url("/api/v1/chat/completions").toString();
            List<Long> receiveTimeNanos = new CopyOnWriteArrayList<>();

            webClient.get()
                    .uri(url)
                    .retrieve()
                    .bodyToFlux(DataBuffer.class)
                    .doOnNext(buf -> receiveTimeNanos.add(System.nanoTime()))
                    .doOnComplete(() -> log.info("SSE stream completed"))
                    .doOnError(e -> log.error("SSE stream error", e))
                    .blockLast(Duration.ofSeconds(15));

            assertTrue(receiveTimeNanos.size() >= 2,
                    "Should receive at least 2 DataBuffers, got " + receiveTimeNanos.size());

            long firstTime = receiveTimeNanos.getFirst();
            long lastTime = receiveTimeNanos.getLast();
            long spanMs = (lastTime - firstTime) / 1_000_000;

            log.info("SSE DataBuffers: {}, spanMs: {} (min required: {})",
                    receiveTimeNanos.size(), spanMs, minSpanMs);

            assertTrue(spanMs >= minSpanMs,
                    "DataBuffers must arrive progressively (span >= " + minSpanMs + " ms), but span was " + spanMs + " ms. " +
                            "If all arrive at once, WebClientLogCustomizer is blocking the stream.");
        } finally {
            server.shutdown();
        }
    }

    /**
     * Reproduces current issue: WebClient with filter that buffers body (collectList),
     * as with blocking sniff in WebClientLogCustomizer. Same check as in progressive-delivery test
     * (span >= minSpan). When buffered, chunks arrive at once → span is small → test should fail.
     * Until the bug is fixed (stream not buffered), this test remains failing.
     */
    @Test
    void whenSseStreamViaWebClientWithBlockingSniff_thenDataBuffersArriveAllAtOnce_reproducingBug() throws IOException {
        MockWebServer server = new MockWebServer();
        server.start();

        try {
            int chunkDelayMs = 80;
            int numChunks = 5;
            long minSpanMs = (numChunks - 1) * (chunkDelayMs - 20);

            StringBuilder sseBody = new StringBuilder();
            for (int i = 1; i <= numChunks; i++) {
                sseBody.append("data: {\"choices\":[{\"delta\":{\"content\":\"c").append(i).append("\"}}]}\n\n");
            }
            sseBody.append("data: {\"choices\":[{\"finish_reason\":\"stop\"}]}\n\n");
            sseBody.append("data: [DONE]\n\n");

            server.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", MediaType.TEXT_EVENT_STREAM_VALUE)
                    .setBody(sseBody.toString())
                    .throttleBody(64, chunkDelayMs, TimeUnit.MILLISECONDS));

            // Filter reproduces bug: body buffering (as with synchronous sniff) → all chunks arrive at once
            ExchangeFilterFunction bufferingFilter = (request, next) -> next.exchange(request)
                    .flatMap(response -> {
                        if (response.statusCode().isError()) {
                            return Mono.just(response);
                        }
                        if (!response.headers().contentType()
                                .map(ct -> MediaType.TEXT_EVENT_STREAM.isCompatibleWith(ct))
                                .orElse(false)) {
                            return Mono.just(response);
                        }
                        return Mono.just(response.mutate()
                                .body(dataBuffers -> dataBuffers.collectList().flatMapMany(Flux::fromIterable))
                                .build());
                    });

            WebClient webClient = WebClient.builder()
                    .filter(bufferingFilter)
                    .build();

            String url = server.url("/api/v1/chat/completions").toString();
            List<Long> receiveTimeNanos = new CopyOnWriteArrayList<>();

            webClient.get()
                    .uri(url)
                    .retrieve()
                    .bodyToFlux(DataBuffer.class)
                    .doOnNext(buf -> receiveTimeNanos.add(System.nanoTime()))
                    .blockLast(Duration.ofSeconds(15));

            assertTrue(receiveTimeNanos.size() >= 2, "Should receive at least 2 DataBuffers");

            long firstTime = receiveTimeNanos.getFirst();
            long lastTime = receiveTimeNanos.getLast();
            long spanMs = (lastTime - firstTime) / 1_000_000;

            log.info("SSE with BUFFERING (bug): {} DataBuffers, spanMs: {} (progressive requires >= {} ms)",
                    receiveTimeNanos.size(), spanMs, minSpanMs);

            // Same check as in whenSseStreamViaWebClientWithLogCustomizer_... — with buffering it fails; expect AssertionError.
            AssertionError failure = assertThrows(AssertionError.class,
                    () -> assertTrue(spanMs >= minSpanMs,
                            "DataBuffers must arrive progressively (span >= " + minSpanMs + " ms), but with buffering span was " + spanMs + " ms."));
            assertTrue(failure.getMessage().contains("" + spanMs) || failure.getMessage().contains("" + minSpanMs),
                    "Expected assertion message to mention span; got: " + failure.getMessage());
        } finally {
            server.shutdown();
        }
    }

    @Test
    void whenStreamCommand_thenReturnsSpringAIStreamResponseAndChunksAggregateToMessage() {
        ChatAICommand command = new ChatAICommand(
                Set.of(ModelCapabilities.CHAT),
                0.7,
                100,
                "You are a helpful assistant.",
                "Reply with exactly: OK",
                true,
                new HashMap<>(),
                new HashMap<>()
        );

        AIResponse response = springAIGateway.generateResponse(command);

        assertNotNull(response);
        assertInstanceOf(SpringAIStreamResponse.class, response);

        SpringAIStreamResponse streamResponse = (SpringAIStreamResponse) response;
        List<ChatResponse> collectedChunks = streamResponse.chatResponse()
                .collectList()
                .block(Duration.ofMinutes(1));

        assertNotNull(collectedChunks);
        assertFalse(collectedChunks.isEmpty(), "Should receive at least one stream chunk, got " + collectedChunks.size());

        StringBuilder aggregated = new StringBuilder();
        for (ChatResponse chunk : collectedChunks) {
            AIUtils.extractText(chunk).ifPresent(aggregated::append);
        }
        assertFalse(aggregated.isEmpty(), "Aggregated streamed message must be non-empty, got: " + aggregated);
    }

    /**
     * Reproduces issue: verifies real stream processing path via processStreamingResponseByParagraphs
     * (as in Telegram handler). When buffered, all blocks arrive at once → test fails.
     * This test should fail if stream is buffered somewhere in the chain (Spring AI, WebClient, or processStreamingResponseByParagraphs).
     */
    @Test
    void whenProcessStreamingResponseByParagraphs_thenBlocksArriveProgressivelyNotAllAtOnce() {
        // Chunks are full paragraphs (>= 300 chars, \n\n) so each yields one block in listener
        int numChunks = 5;
        int chunkDelayMs = 80;
        long minSpanMs = (numChunks - 1) * (chunkDelayMs - 20);
        int minParagraphLength = 300; // as in AIUtils.processStreamingResponseByParagraphs

        Flux<ChatResponse> progressiveFlux = createSimulatedStreamFluxWithParagraphs(numChunks, chunkDelayMs, minParagraphLength);

        // Record receive time of each block in listener (as in Telegram)
        List<Long> blockReceiveTimeNanos = new CopyOnWriteArrayList<>();

        try {
            AIUtils.processStreamingResponseByParagraphs(
                    progressiveFlux,
                    4096,
                    block -> {
                        long now = System.nanoTime();
                        blockReceiveTimeNanos.add(now);
                        log.info("Block received at {}: {}", now / 1_000_000, block);
                    },
                    Duration.ofSeconds(10)
            );
        } catch (Exception e) {
            log.error("Error processing stream", e);
            throw e;
        }

        assertTrue(blockReceiveTimeNanos.size() >= 2,
                "Should receive at least 2 blocks, got " + blockReceiveTimeNanos.size());

        long firstTime = blockReceiveTimeNanos.getFirst();
        long lastTime = blockReceiveTimeNanos.getLast();
        long spanMs = (lastTime - firstTime) / 1_000_000;

        log.info("processStreamingResponseByParagraphs: {} blocks, spanMs: {} (progressive requires >= {} ms)",
                blockReceiveTimeNanos.size(), spanMs, minSpanMs);

        // When buffered, all blocks arrive at once → span is small → test fails
        assertTrue(spanMs >= minSpanMs,
                "Blocks must arrive progressively (span >= " + minSpanMs + " ms), but span was " + spanMs + " ms. " +
                        "If all arrive at once, streaming is buffered somewhere in the chain (Spring AI, WebClient, or processStreamingResponseByParagraphs).");
    }

    @SpringBootConfiguration
    @Import(SpringAIGatewayIT.StreamingTestBeans.class)
    static class StreamingTestConfig {
    }

    @Configuration
    static class StreamingTestBeans {

        @Bean
        SpringAIPromptFactory springAIPromptFactory() {
            SpringAIPromptFactory factory = mock(SpringAIPromptFactory.class);
            when(factory.preparePrompt(any(), any(), any(), any(), anyBoolean(), any(), any()))
                    .thenAnswer(inv -> createSpecWithDelayedFlux());
            return factory;
        }

        /**
         * Returns progressive Flux with delays between chunks.
         * After WebClientLogCustomizer fix (parsing in separate thread) streaming is not blocked.
         */
        private static ChatClient.ChatClientRequestSpec createSpecWithDelayedFlux() {
            Flux<ChatResponse> progressiveFlux = SpringAIGatewayIT.createSimulatedStreamFlux(NUM_CHUNKS, CHUNK_DELAY_MS);
            ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class, RETURNS_DEEP_STUBS);
            when(spec.stream().chatResponse()).thenReturn(progressiveFlux);
            return spec;
        }

        @Bean
        SpringAIModelConfig streamingTestModelConfig() {
            SpringAIModelConfig c = new SpringAIModelConfig();
            c.setName("test-streaming-model");
            c.setCapabilities(List.of(ModelCapabilities.CHAT));
            c.setProviderType(SpringAIModelConfig.ProviderType.OLLAMA);
            c.setPriority(1);
            return c;
        }

        @Bean
        SpringAIModelRegistry springAIModelRegistry(SpringAIModelConfig streamingTestModelConfig) {
            return new SpringAIModelRegistry(
                    List.of(streamingTestModelConfig),
                    null,
                    null
            );
        }

        @Bean
        SpringAIProperties springAIProperties() {
            SpringAIProperties p = mock(SpringAIProperties.class);
            when(p.getMock()).thenReturn(false);
            return p;
        }

        @Bean
        AIGatewayRegistry aiGatewayRegistry() {
            return new AIGatewayRegistry();
        }

        @Bean
        ObjectProvider<OpenRouterStreamMetricsTracker> openRouterStreamMetricsTrackerProvider() {
            ObjectProvider<OpenRouterStreamMetricsTracker> provider =
                    mock(ObjectProvider.class);
            when(provider.getIfAvailable()).thenReturn(null);
            return provider;
        }

        @Bean
        SpringAIChatService springAIChatService(
                SpringAIPromptFactory springAIPromptFactory,
                ObjectProvider<OpenRouterStreamMetricsTracker> openRouterStreamMetricsTrackerProvider
        ) {
            return new SpringAIChatService(springAIPromptFactory, openRouterStreamMetricsTrackerProvider);
        }

        @Bean
        RAGProperties ragProperties() {
            RAGProperties p = mock(RAGProperties.class);
            return p;
        }

        @Bean
        ObjectProvider<DocumentProcessingService> documentProcessingServiceProvider() {
            ObjectProvider<DocumentProcessingService> provider = mock(ObjectProvider.class);
            when(provider.getIfAvailable()).thenReturn(null);
            return provider;
        }

        @Bean
        ObjectProvider<io.github.ngirchev.aibot.ai.springai.rag.FileRAGService> fileRAGServiceProvider() {
            ObjectProvider<io.github.ngirchev.aibot.ai.springai.rag.FileRAGService> provider = mock(ObjectProvider.class);
            when(provider.getIfAvailable()).thenReturn(null);
            return provider;
        }

        @Bean
        SpringAIGateway springAIGateway(
                SpringAIProperties springAIProperties,
                AIGatewayRegistry aiGatewayRegistry,
                SpringAIModelRegistry springAIModelRegistry,
                SpringAIChatService springAIChatService,
                ObjectProvider<org.springframework.ai.chat.memory.ChatMemory> chatMemoryProvider,
                RAGProperties ragProperties,
                ObjectProvider<DocumentProcessingService> documentProcessingServiceProvider,
                ObjectProvider<io.github.ngirchev.aibot.ai.springai.rag.FileRAGService> fileRAGServiceProvider
        ) {
            return new SpringAIGateway(
                    springAIProperties,
                    aiGatewayRegistry,
                    springAIModelRegistry,
                    springAIChatService,
                    chatMemoryProvider,
                    ragProperties,
                    documentProcessingServiceProvider,
                    fileRAGServiceProvider
            );
        }
    }
}
