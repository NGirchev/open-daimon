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

    @RotateOpenRouterModels(stream = true)
    public AIResponse streamChat(
            SpringAIModelConfig modelConfig,
            AICommand command,
            OpenDaimonChatOptions chatOptions,
            List<Message> messages
    ) {
        String modelForStream = resolveModelName(modelConfig, chatOptions != null ? chatOptions.body() : null);
        Object conversationId = command != null ? command.metadata().get(AICommand.THREAD_KEY_FIELD) : null;
        boolean webEnabled = command != null && command.modelCapabilities().contains(ModelCapabilities.WEB);
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
        Flux<ChatResponse> chatResponseFlux = promptBuilder.stream().chatResponse()
                .doOnNext(cr -> {
                    // Log only first chunk — stream start
                    if (firstChunk.compareAndSet(true, false) && cr != null) {
                        log.info("Spring AI stream started - first chunk received");
                    }
                    log.trace("Spring AI stream chunk received: {}", cr);

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
        boolean webEnabled = command != null && command.modelCapabilities().contains(ModelCapabilities.WEB);
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
