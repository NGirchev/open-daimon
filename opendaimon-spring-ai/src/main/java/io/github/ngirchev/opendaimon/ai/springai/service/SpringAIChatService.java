package io.github.ngirchev.opendaimon.ai.springai.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import io.github.ngirchev.opendaimon.ai.springai.config.SpringAIModelConfig;
import io.github.ngirchev.opendaimon.common.ai.ModelCapabilities;
import io.github.ngirchev.opendaimon.common.ai.command.OpenDaimonChatOptions;
import io.github.ngirchev.opendaimon.common.ai.command.AICommand;
import io.github.ngirchev.opendaimon.common.ai.response.AIResponse;
import io.github.ngirchev.opendaimon.common.ai.response.SpringAIResponse;
import io.github.ngirchev.opendaimon.common.ai.response.SpringAIStreamResponse;
import io.github.ngirchev.opendaimon.common.service.AIUtils;
import io.github.ngirchev.opendaimon.ai.springai.retry.metrics.OpenRouterStreamMetricsTracker;
import io.github.ngirchev.opendaimon.ai.springai.retry.RotateOpenRouterModels;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@RequiredArgsConstructor
public class SpringAIChatService {

    private final SpringAIPromptFactory promptFactory;
    private final ObjectProvider<OpenRouterStreamMetricsTracker> openRouterStreamMetricsTrackerProvider;
    private static final int MAX_ERROR_BODY_CHARS = 4_000;
    private static final int VISION_OCR_SEED = 42;

    @RotateOpenRouterModels(stream = true)
    public AIResponse streamChat(
            SpringAIModelConfig modelConfig,
            AICommand command,
            OpenDaimonChatOptions chatOptions,
            List<Message> messages
    ) {
        String modelForStream = resolveModelName(modelConfig, chatOptions != null ? chatOptions.body() : null);
        Object conversationId = command != null ? command.metadata().get(AICommand.THREAD_KEY_FIELD) : null;
        boolean webEnabled = webToolsEnabled(command);
        var promptBuilder = promptFactory.preparePrompt(
                modelConfig,
                modelForStream,
                chatOptions != null ? chatOptions.body() : null,
                conversationId,
                webEnabled,
                messages,
                chatOptions
        );

        Integer requestedMaxTokens = chatOptions != null ? chatOptions.maxTokens() : null;
        Double requestedTemp = chatOptions != null ? chatOptions.temp() : null;
        log.info("Spring AI stream request. model={}, providerType={}, messages={}, webEnabled={}, maxTokens={}, temp={}",
                modelForStream,
                modelConfig != null ? modelConfig.getProviderType() : null,
                messages != null ? messages.size() : 0,
                webEnabled,
                requestedMaxTokens,
                requestedTemp);

        AtomicBoolean firstChunk = new AtomicBoolean(true);
        StringBuilder reasoningAccumulator = new StringBuilder();
        StringBuilder toolCallDebugBuffer = new StringBuilder();
        Flux<ChatResponse> chatResponseFlux = promptBuilder.stream().chatResponse()
                .doOnNext(cr -> {
                    // Log only first chunk — stream start
                    if (firstChunk.compareAndSet(true, false) && cr != null) {
                        log.info("Spring AI stream started - first chunk received");
                    }
                    log.trace("Spring AI stream chunk received: {}", cr);

                    if (log.isDebugEnabled() && cr != null && cr.getResult() != null) {
                        var meta = cr.getResult().getMetadata();
                        String finishReason = meta != null ? String.valueOf(meta.getFinishReason()) : "null";
                        String content = cr.getResult().getOutput() != null
                                ? cr.getResult().getOutput().getText() : "";
                        if (content != null && !content.isEmpty()) {
                            toolCallDebugBuffer.append(content);
                        }
                        if (!"null".equals(finishReason) && !finishReason.isEmpty()) {
                            log.debug("Stream chunk finishReason={}, bufferedContent=[{}]",
                                    finishReason, normalizeReasoningForLog(toolCallDebugBuffer.toString()));
                            toolCallDebugBuffer.setLength(0);
                        }
                    }

                    // Extract reasoning from metadata (OpenRouter and other providers)
                    // if (cr != null) {
                    //     try {
                    //         var result = cr.getResult();
                    //         if (result == null) {
                    //             log.info("Stream chunk: result is null");
                    //         } else if (result.getMetadata() == null) {
                    //             log.info("Stream chunk: metadata is null");
                    //         } else {
                    //             Object reasoningObj = result.getMetadata().get("reasoningContent");
                    //             if (reasoningObj instanceof String reasoningChunk && !reasoningChunk.isEmpty()) {
                    //                 reasoningAccumulator.append(reasoningChunk);
                    //             } else {
                    //                 logNonEmptyMetadataTextData(result.getMetadata());
                    //             }
                    //         }
                    //     } catch (Exception e) {
                    //         log.info("Stream chunk: exception reading metadata: {}", e.getMessage());
                    //     }
                    // }
                })
                .doOnComplete(() -> log.info("Spring AI stream completed"))
                .doFinally(signalType -> {
                    // Log accumulated reasoning on one line
                    String reasoningText = reasoningAccumulator.toString();
                    if (!reasoningText.isEmpty()) {
                        log.info("OpenRouter reasoning: {}", normalizeReasoningForLog(reasoningText));
                    }
                })
                .doOnError(e -> logStreamError(e, modelForStream, chatOptions != null ? chatOptions.body() : null));
        return new SpringAIStreamResponse(trackStreamIfPossible(modelForStream, chatResponseFlux));
    }

    @RotateOpenRouterModels
    public AIResponse callChat(
            SpringAIModelConfig modelConfig,
            AICommand command,
            OpenDaimonChatOptions chatOptions,
            List<Message> messages
    ) {
        Object conversationId = command != null ? command.metadata().get(AICommand.THREAD_KEY_FIELD) : null;
        boolean webEnabled = webToolsEnabled(command);
        Map<String, Object> body = chatOptions != null ? chatOptions.body() : null;
        return callChatOnce(modelConfig, body, conversationId, webEnabled, messages, chatOptions);
    }

    private AIResponse callChatOnce(
            SpringAIModelConfig modelConfig,
            Map<String, Object> body,
            Object conversationId,
            boolean webEnabled,
            List<Message> messages,
            OpenDaimonChatOptions chatOptions
    ) {
        String modelName = resolveModelName(modelConfig, body);
        var promptBuilder = promptFactory.preparePrompt(
                modelConfig,
                modelName,
                body,
                conversationId,
                webEnabled,
                messages,
                chatOptions
        );

        Integer requestedMaxTokens = chatOptions != null ? chatOptions.maxTokens() : null;
        Double requestedTemp = chatOptions != null ? chatOptions.temp() : null;
        log.info("Spring AI call request. model={}, providerType={}, messages={}, webEnabled={}, maxTokens={}, temp={}",
                modelName,
                modelConfig != null ? modelConfig.getProviderType() : null,
                messages != null ? messages.size() : 0,
                webEnabled,
                requestedMaxTokens,
                requestedTemp);
        try {
            ChatResponse response = promptBuilder.call().chatResponse();
            return new SpringAIResponse(response);
        } catch (WebClientResponseException webClientError) {
            log.error("Spring AI call error. model={}, status={}, bodyKeys={}, body={}",
                    modelName,
                    webClientError.getStatusCode(),
                    body != null ? body.keySet() : null,
                    truncate(webClientError.getResponseBodyAsString()));
            throw webClientError;
        }
    }

    /**
     * Lightweight vision call for internal use (e.g. extracting text from image-only PDF).
     * No ChatMemory, no web tools, no conversationId — pure vision request.
     *
     * @param modelConfig vision-capable model
     * @param messages    messages (typically: UserMessage with text + Media)
     * @return extracted text from the model response
     */
    public String callSimpleVision(SpringAIModelConfig modelConfig, List<Message> messages) {
        String modelName = modelConfig.getName();
        log.info("Vision extraction call. model={}, messages={}", modelName, messages.size());

        // OCR should be deterministic and literal — keep sampling stable.
        // Include max_price for OpenRouter models (auto requires it to find endpoints).
        Map<String, Object> visionOverrides = new java.util.HashMap<>(Map.of(
                "temperature", 0.0d,
                "top_p", 1.0d,
                "seed", VISION_OCR_SEED
        ));
        // Vision OCR is an internal operation — allow paid models up to a reasonable limit.
        // Without max_price, OpenRouter /auto rejects the request with 404.
        visionOverrides.put("max_price", 5.0);

        var promptBuilder = promptFactory.preparePrompt(
                modelConfig, modelName, visionOverrides, null, false, messages, null);

        try {
            ChatResponse response = promptBuilder.call().chatResponse();
            String text = response != null && response.getResult() != null
                    && response.getResult().getOutput() != null
                    ? response.getResult().getOutput().getText()
                    : null;
            log.info("Vision extraction completed. model={}, responseLength={}",
                    modelName, text != null ? text.length() : 0);
            return text;
        } catch (Exception e) {
            log.error("Vision extraction failed. model={}, error={}", modelName, e.getMessage(), e);
            throw new RuntimeException("Vision extraction failed for model " + modelName, e);
        }
    }

    public AIResponse callChatFromBody(
            SpringAIModelConfig modelConfig,
            Map<String, Object> requestBody,
            Object conversationId,
            boolean webEnabled,
            List<Message> messages
    ) {
        return callChatOnce(modelConfig, requestBody, conversationId, webEnabled, messages, null);
    }

    private Flux<ChatResponse> trackStreamIfPossible(String modelId, Flux<ChatResponse> flux) {
        OpenRouterStreamMetricsTracker tracker = openRouterStreamMetricsTrackerProvider.getIfAvailable();
        if (tracker == null) {
            return flux;
        }
        return tracker.track(modelId, flux);
    }

    /**
     * Registers web tools (Serper, fetch_url) when the command requests {@link ModelCapabilities#WEB}
     * in required or optional capabilities (e.g. VIP tier puts WEB in optional for routing).
     */
    private static boolean webToolsEnabled(AICommand command) {
        if (command == null) {
            return false;
        }
        return command.modelCapabilities().contains(ModelCapabilities.WEB)
                || command.optionalCapabilities().contains(ModelCapabilities.WEB);
    }

    private void logStreamError(Throwable error, String modelName, Map<String, Object> body) {
        if (AIUtils.shouldLogWithoutStacktrace(error)) {
            log.error("Spring AI stream error. model={}, cause={}", modelName, AIUtils.getRootCauseMessage(error));
            return;
        }
        if (error instanceof WebClientResponseException webClientError) {
            log.error("Spring AI stream error. model={}, status={}, body={}",
                    modelName,
                    webClientError.getStatusCode(),
                    truncate(webClientError.getResponseBodyAsString()));
        } else {
            log.error("Spring AI stream error. model={}, body={}", modelName, body, error);
        }
    }

    private String resolveModelName(SpringAIModelConfig modelConfig, Map<String, Object> body) {
        if (modelConfig != null && modelConfig.getName() != null) {
            return modelConfig.getName();
        }
        if (body == null || body.isEmpty()) {
            return null;
        }
        Object model = body.get("model");
        if (model instanceof String) {
            return (String) model;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> options = (Map<String, Object>) body.get("options");
        if (options != null) {
            Object optionsModel = options.get("model");
            if (optionsModel instanceof String) {
                return (String) optionsModel;
            }
        }
        return null;
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

    /**
     * Logs all keys and non-empty values from metadata (for debugging).
     */
    private void logNonEmptyMetadataTextData(ChatGenerationMetadata metadata) {
        if (metadata == null) {
            log.info("Metadata dump: metadata is null");
            return;
        }
        log.info("Metadata dump: called, metadata.getClass()={}", metadata.getClass().getName());
        try {
            Set<Map.Entry<String, Object>> entries = metadata.entrySet();
            if (entries == null || entries.isEmpty()) {
                log.info("Metadata dump: entrySet is null or empty. metadata.toString()={}", metadata.toString());
                return;
            }
            for (Map.Entry<String, Object> entry : entries) {
                String key = entry.getKey();
                Object value = entry.getValue();
                if (value != null) {
                    String strValue = value.toString();
                    if (!strValue.isEmpty() && !strValue.equals("null")) {
                        log.info("Metadata {} = {}", key, strValue);
                    }
                }
            }
        } catch (Exception e) {
            log.info("Metadata dump: exception. msg={}, toString={}", e.getMessage(), metadata.toString(), e);
        }
    }
}
