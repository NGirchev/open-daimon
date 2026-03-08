package io.github.ngirchev.aibot.ai.springai.rest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import org.springframework.boot.web.reactive.function.client.WebClientCustomizer;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.client.reactive.ClientHttpRequest;
import org.springframework.http.client.reactive.ClientHttpRequestDecorator;
import org.springframework.web.reactive.function.BodyInserter;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.springframework.web.reactive.function.client.WebClient.Builder;

@Slf4j
public class WebClientLogCustomizer implements WebClientCustomizer {

    private static final int MAX_ERROR_BODY_CHARS = 4_000;
    private static final int MAX_METADATA_VALUE_LENGTH = 500;

    private final ObjectMapper objectMapper;

    public WebClientLogCustomizer() {
        this(new ObjectMapper());
    }

    public WebClientLogCustomizer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void customize(Builder builder) {
        builder.filter(logRequestsToKnownAiBackends());
    }

    private ExchangeFilterFunction logRequestsToKnownAiBackends() {
        return (request, next) -> {
            if (!log.isDebugEnabled()) {
                return next.exchange(request);
            }

            long startNs = System.nanoTime();
            ClientRequest requestWithBodyLogging = decorateRequestBodyLoggingIfDebug(request);
            return next.exchange(requestWithBodyLogging)
                    .flatMap(response -> logAndBufferErrorsIfNeeded(requestWithBodyLogging, response, startNs));
        };
    }

    private Mono<ClientResponse> logAndBufferErrorsIfNeeded(ClientRequest request, ClientResponse response, long startNs) {
        long latencyMs = (System.nanoTime() - startNs) / 1_000_000L;
        int status = response.statusCode().value();

        if (response.statusCode().isError()) {
            return response.bodyToMono(String.class)
                    .defaultIfEmpty("")
                    .map(body -> {
                        log.error("HTTP <- {} {} status={} latencyMs={} body={}",
                                request.method(),
                                request.url(),
                                status,
                                latencyMs,
                                truncate(body));
                        return ClientResponse.from(response).body(body).build();
                    });
        }

        // OpenRouter SSE: collect reasoning and metadata from raw response.
        log.debug("OpenRouter SSE response: sniffing reasoning");
        StringBuilder reasoningBuffer = new StringBuilder();
        Map<String, String> rawMetadata = new LinkedHashMap<>();
        AtomicReference<String> carry = new AtomicReference<>(null);
        return Mono.just(response.mutate()
                .body(dataBuffers -> dataBuffers
                        .doOnNext(dataBuffer -> collectReasoningAndMetadata(dataBuffer, reasoningBuffer, carry, rawMetadata))
                        .doFinally(signalType -> {
                            String reasoningText = reasoningBuffer.toString();
                            if (!reasoningText.isEmpty()) {
                                log.debug("OpenRouter SSE reasoning: {}", normalizeReasoningForLog(reasoningText));
                            }
                            if (!rawMetadata.isEmpty()) {
                                log.info("OpenRouter SSE raw metadata: {}", rawMetadata);
                            }
                        }))
                .build());
    }

    /**
     * Collects reasoning and metadata from one DataBuffer SSE stream.
     * Reasoning: delta.reasoning / delta.reasoning_content / delta.reasoning_details.summary.
     * Metadata: root JSON fields and choices[0].delta (values truncated for log).
     *
     * 2026-02-19 22:55:55.497 [reactor-http-nio-2] DEBUG r.g.a.a.s.r.WebClientLogCustomizer - OpenRouter SSE response: sniffing reasoning
     * 2026-02-19 22:55:56.806 [boundedElastic-1] INFO  r.g.a.a.s.s.SpringAIChatService - Spring AI stream started - first chunk received
     * 2026-02-19 22:55:56.821 [reactor-http-nio-2] INFO  r.g.a.a.s.r.WebClientLogCustomizer - OpenRouter SSE raw metadata: {id=gen-1771534553-0QhIwCfEXwyf6AWAnFod, provider=OpenAI, model=openai/gpt-5-nano, object=chat.completion.chunk, created=1771534555, delta.role=assistant, delta.reasoning_details=[{"type":"reasoning.encrypted","data":"gAAAAABpl3jdtUeb-r7leqAE91FNOR7V7sNUGWG_75YlrsYrzq7FeVfUjopWfeg1Kdf_QiEzod2D50ovRwlo8WFiC2GUiiilY7aiu8gOJBzjdTO58Z8vbBlNmYveycCiwn0s0xj6xYyCd848agVdR9Kyun6fv2FPDXf1bmxtoqnW_MuXUfCaLqYaEgXGYMC3mC4OYcd6L8Ju9AWjNLGoeyoWdSxOYNfsPscSCCfuiBTMrxFno8IHjN6F1wi6wNUFCtbw8PwGTHUkaLm6kBLg__LZmdDD8rGJITBgFTp6FCCpmYoQXWNlWKhXVT92PxX4xZRNxgJqFYF4l909CG7XPk-Cxqv4fgRxa1Ws_heMBklKI6IvtgN7PFoP_dCkJk3udUYfDeIwPF4B1pqGN7QLwXqOiF6bSxmAEK0v9-12ABxnbZBi4dasr08iNR72n6tEzd1XU1I09AzPS..., usage={"prompt_tokens":1090,"completion_tokens":64,"total_tokens":1154,"cost":8.01E-5,"is_byok":false,"prompt_tokens_details":{"cached_tokens":0},"cost_details":{"upstream_inference_cost":8.01E-5,"upstream_inference_prompt_cost":5.45E-5,"upstream_inference_completions_cost":2.56E-5},"completion_tokens_details":{"reasoning_tokens":64,"image_tokens":0}}}
     * 2026-02-19 22:55:56.836 [boundedElastic-1] INFO  r.g.a.a.s.s.SpringAIChatService - Spring AI stream completed
     * 2026-02-19 22:55:56.836 [boundedElastic-1] INFO  r.g.a.a.s.r.m.OpenRouterStreamMetricsTracker - OpenRouter stream completed. model=openrouter/auto, durationMs=4839
     */
    private void collectReasoningAndMetadata(DataBuffer dataBuffer, StringBuilder reasoningBuffer,
                                              AtomicReference<String> carry, Map<String, String> rawMetadata) {
        if (dataBuffer == null) {
            return;
        }
        String chunk;
        try {
            ByteBuffer byteBuffer = dataBuffer.asByteBuffer().asReadOnlyBuffer();
            if (!byteBuffer.hasRemaining()) {
                return;
            }
            byte[] bytes = new byte[byteBuffer.remaining()];
            byteBuffer.get(bytes);
            chunk = new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return;
        }
        if (chunk.isBlank()) {
            return;
        }
        String combined = (carry.get() != null ? carry.get() : "") + chunk;
        String[] lines = combined.split("\n", -1);
        carry.set(lines[lines.length - 1]);
        for (int i = 0; i < lines.length - 1; i++) {
            String trimmed = lines[i].trim();
            if (!trimmed.startsWith("data:")) {
                continue;
            }
            String data = trimmed.substring("data:".length()).trim();
            if (data.isBlank() || data.equals("[DONE]")) {
                continue;
            }
            try {
                JsonNode root = objectMapper.readTree(data);
                putAllNonEmptyFields(root, "", rawMetadata, "choices");

                JsonNode delta = root.path("choices").path(0).path("delta");
                if (!delta.isMissingNode()) {
                    if (log.isDebugEnabled()) {
                        appendIfText(delta.get("reasoning"), reasoningBuffer);
                        appendIfText(delta.get("reasoningContent"), reasoningBuffer);
                        appendIfText(delta.get("reasoning_content"), reasoningBuffer);
                        JsonNode details = delta.get("reasoning_details");
                        if (details != null && details.isArray()) {
                            for (JsonNode rd : details) {
                                if (rd != null && "reasoning.summary".equals(rd.path("type").asText())) {
                                    appendIfText(rd.get("summary"), reasoningBuffer);
                                }
                            }
                        }
                    }
                    putAllNonEmptyFields(delta, "delta.", rawMetadata, null);
                }
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Adds all non-empty node fields to map (with prefix). Values truncated to MAX_METADATA_VALUE_LENGTH.
     * @param excludeKey root field name to exclude (e.g. "choices"), or null
     */
    private void putAllNonEmptyFields(JsonNode node, String keyPrefix, Map<String, String> out, String excludeKey) {
        if (node == null || !node.isObject()) {
            return;
        }
        node.fields().forEachRemaining(entry -> {
            String fieldName = entry.getKey();
            if (excludeKey != null && excludeKey.equals(fieldName)) {
                return;
            }
            String key = keyPrefix + fieldName;
            JsonNode value = entry.getValue();
            if (value == null) {
                return;
            }
            String str;
            if (value.isTextual()) {
                str = value.asText();
            } else if (value.isNumber() || value.isBoolean()) {
                str = value.asText();
            } else if (value.isArray() || value.isObject()) {
                str = value.toString();
            } else {
                str = value.asText();
            }
            if (!str.isEmpty() && !str.equals("null")) {
                if (str.length() > MAX_METADATA_VALUE_LENGTH) {
                    str = str.substring(0, MAX_METADATA_VALUE_LENGTH) + "...";
                }
                out.put(key, str);
            }
        });
    }

    private void appendIfText(JsonNode node, StringBuilder buffer) {
        if (node != null && node.isTextual()) {
            String text = node.asText();
            if (!text.isEmpty()) {
                buffer.append(text);
            }
        }
    }

    private ClientRequest decorateRequestBodyLoggingIfDebug(ClientRequest request) {
        var originalInserter = request.body();
        var loggingInserter = new BodyInserter<Object, ClientHttpRequest>() {
            @Override
            public Mono<Void> insert(ClientHttpRequest outputMessage, Context context) {
                ByteArrayOutputStream captured = new ByteArrayOutputStream();

                ClientHttpRequestDecorator decorator = new ClientHttpRequestDecorator(outputMessage) {
                    @Override
                    public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
                        Flux<? extends DataBuffer> flux = Flux.from(body)
                                .doOnNext(dataBuffer -> captureChunk(captured, dataBuffer));
                        return super.writeWith(flux);
                    }

                    @Override
                    public Mono<Void> writeAndFlushWith(Publisher<? extends Publisher<? extends DataBuffer>> body) {
                        Flux<? extends Publisher<? extends DataBuffer>> outer = Flux.from(body)
                                .map(inner -> Flux.from(inner)
                                        .doOnNext(dataBuffer -> captureChunk(captured, dataBuffer)));
                        return super.writeAndFlushWith(outer);
                    }
                };

                @SuppressWarnings("unchecked")
                BodyInserter<Object, ClientHttpRequest> typedOriginal =
                        (BodyInserter<Object, ClientHttpRequest>) originalInserter;

                return typedOriginal.insert(decorator, context)
                        .doFinally(signal -> logRequestBodyIfAny(request, captured));
            }
        };

        return ClientRequest.from(request).body(loggingInserter).build();
    }

    private void captureChunk(ByteArrayOutputStream captured, DataBuffer dataBuffer) {
        if (dataBuffer == null) {
            return;
        }
        ByteBuffer byteBuffer = dataBuffer.asByteBuffer().asReadOnlyBuffer();
        int toCopy = byteBuffer.remaining();
        if (toCopy <= 0) {
            return;
        }
        byte[] chunk = new byte[toCopy];
        byteBuffer.get(chunk);
        captured.writeBytes(chunk);
    }

    private void logRequestBodyIfAny(ClientRequest request, ByteArrayOutputStream captured) {
        if (!log.isDebugEnabled()) {
            return;
        }
        if (captured == null || captured.size() == 0) {
            return;
        }
        String body = captured.toString(StandardCharsets.UTF_8);
        String formattedBody = formatBodyForLogs(body, request.headers().getFirst("Content-Type"));
        log.debug("HTTP -> {} {} REQUEST BODY:\n{}",
                request.method(),
                request.url(),
                formattedBody);
    }

    private String formatBodyForLogs(String body, String contentType) {
        if (body == null || body.isBlank()) {
            return body;
        }

        String trimmed = body.trim();
        boolean looksLikeJson = trimmed.startsWith("{") || trimmed.startsWith("[");
        boolean isJsonContentType = contentType != null && contentType.toLowerCase().contains("json");
        if (!looksLikeJson && !isJsonContentType) {
            return body;
        }

        try {
            JsonNode root = objectMapper.readTree(body);
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (JsonProcessingException e) {
            return body;
        }
    }

    private String truncate(String body) {
        if (body == null) {
            return null;
        }
        if (body.length() <= MAX_ERROR_BODY_CHARS) {
            return body;
        }
        return body.substring(0, MAX_ERROR_BODY_CHARS) + "...(truncated)";
    }

    /**
     * Normalizes reasoning text for single-line log: strips newlines and multiple spaces.
     */
    private String normalizeReasoningForLog(String s) {
        if (s == null) {
            return null;
        }
        return s.replaceAll("\\s+", " ").trim();
    }
}
