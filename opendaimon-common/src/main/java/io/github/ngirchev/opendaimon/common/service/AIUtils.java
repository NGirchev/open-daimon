package io.github.ngirchev.opendaimon.common.service;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClientException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import io.github.ngirchev.opendaimon.common.exception.DocumentContentNotExtractableException;
import reactor.core.publisher.Flux;
import io.github.ngirchev.opendaimon.common.ai.response.AIResponse;
import io.github.ngirchev.opendaimon.common.ai.response.SpringAIResponse;
import io.github.ngirchev.opendaimon.common.ai.response.SpringAIStreamResponse;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static io.github.ngirchev.opendaimon.common.ai.LlmParamNames.*;

@UtilityClass
@Slf4j
public class AIUtils {

    /** Default message when AI response has no text content. Used in REST, Telegram, and stream handling. */
    public static final String CONTENT_IS_EMPTY = "Content is empty";
    /** Message when no gateway supports the given AI command. */
    public static final String NO_SUPPORTED_AI_GATEWAY = "No supported AI gateway found";

    /**
     * Class name of expected empty-stream exception (retry), to avoid printing long stack trace.
     */
    private static final String OPENROUTER_EMPTY_STREAM_EXCEPTION_CLASS =
            "io.github.ngirchev.opendaimon.ai.springai.retry.OpenRouterEmptyStreamException";
    private static final String LOG_ERROR_PROCESSING_STREAMING_RESPONSE = "Error processing streaming response: {}";
    private static final AtomicInteger extractTextEmptyLogCount = new AtomicInteger(0);
    private static final int EXTRACT_TEXT_EMPTY_LOG_LIMIT = 3;

    /**
     * Checks whether the cause chain contains OpenRouterEmptyStreamException (expected retry error).
     * Used to log such errors without stack trace and avoid log noise.
     */
    public static boolean isOpenRouterEmptyStreamInChain(Throwable t) {
        while (t != null) {
            if (OPENROUTER_EMPTY_STREAM_EXCEPTION_CLASS.equals(t.getClass().getName())) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    /**
     * Errors for which full stack trace should not be printed (reactive/HTTP), to avoid log noise.
     * true: OpenRouterEmptyStreamException or WebClientResponseException in cause chain.
     */
    public static boolean shouldLogWithoutStacktrace(Throwable t) {
        while (t != null) {
            if (OPENROUTER_EMPTY_STREAM_EXCEPTION_CLASS.equals(t.getClass().getName())) {
                return true;
            }
            if (t instanceof WebClientResponseException) {
                return true;
            }
            if (t instanceof DocumentContentNotExtractableException) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    /**
     * Root cause message for brief logging.
     */
    public static String getRootCauseMessage(Throwable t) {
        if (t == null) {
            return "null";
        }
        Throwable root = t;
        while (t != null) {
            root = t;
            t = t.getCause();
        }
        String msg = root.getMessage();
        return msg != null ? msg : root.getClass().getSimpleName();
    }

    /**
     * Extracts message from AIResponse.
     *
     * @param aiResponse response from AI provider
     * @return Optional with message text
     */
    public static Optional<String> retrieveMessage(AIResponse aiResponse) {
        if (aiResponse == null) {
            return Optional.empty();
        }

        return switch (aiResponse.gatewaySource()) {
            case SPRINGAI -> {
                if (aiResponse instanceof SpringAIResponse springAIStreamResponse) {
                    yield retrieveMessageFromSpringAI(springAIStreamResponse);
                } else if (aiResponse instanceof SpringAIStreamResponse) {
                    throw new UnsupportedOperationException("Use AIUtils.processStreamingResponse first, because it's streaming response");
                } else {
                    throw new UnsupportedOperationException("Can't handle this class: " + aiResponse.getClass());
                }
            }
            case DEEPSEEK, OPENROUTER, MOCK -> retrieveMessage(aiResponse.toMap());
        };
    }

    /**
     * Extracts useful data from AIResponse.
     *
     * @param aiResponse response from AI provider
     * @return Map with useful data or null if none
     */
    public static Map<String, Object> extractUsefulData(AIResponse aiResponse) {
        if (aiResponse == null) {
            return null;
        }

        return switch (aiResponse.gatewaySource()) {
            case SPRINGAI -> {
                if (aiResponse instanceof SpringAIResponse springAIStreamResponse) {
                    yield extractSpringAiUsefulData(springAIStreamResponse.chatResponse());
                } else if (aiResponse instanceof SpringAIStreamResponse) {
                    throw new UnsupportedOperationException("Use AIUtils.processStreamingResponse first, because it's streaming response");
                } else {
                    throw new UnsupportedOperationException("Can't handle this class: " + aiResponse.getClass());
                }
            }
            case DEEPSEEK, OPENROUTER, MOCK -> extractUsefulData(aiResponse.toMap());
        };
    }

    public static Optional<String> extractError(AIResponse aiResponse) {
        if (aiResponse == null) {
            return Optional.empty();
        }

        return switch (aiResponse.gatewaySource()) {
            case SPRINGAI -> {
                if (aiResponse instanceof SpringAIResponse springAIStreamResponse) {
                    yield extractError(springAIStreamResponse.chatResponse());
                } else if (aiResponse instanceof SpringAIStreamResponse) {
                    throw new UnsupportedOperationException("Use AIUtils.processStreamingResponse first, because it's streaming response");
                } else {
                    throw new UnsupportedOperationException("Can't handle this class: " + aiResponse.getClass());
                }
            }
            case DEEPSEEK, OPENROUTER, MOCK -> extractErrorFromMap(aiResponse.toMap());
        };
    }

    public static Map<String, Object> extractSpringAiUsefulData(ChatResponse chatResponse) {
        if (chatResponse == null) {
            log.debug("SpringAIResponse or ChatResponse is null, returning null");
            return null;
        }

        Map<String, Object> usefulData;

        try {
            var chatResponseMetadata = chatResponse.getMetadata();
            var id = chatResponseMetadata.getId();
            var usage = chatResponseMetadata.getUsage();
            var model = chatResponseMetadata.getModel();
            var responseCalls = chatResponse.getResults().size();
            var generationResults = chatResponse.getResults().stream().map(r -> new GenerationResult(
                    r.getOutput().getToolCalls().size(),
                    r.getMetadata().getFinishReason()
            )).toList();

            usefulData = convertChatResponseMetadataToMap(new ChatResponseMetadata(id, usage, model, responseCalls, generationResults));
        } catch (Exception e) {
            log.error("Error extracting useful data from SpringAIResponse: {}", e.getMessage(), e);
            return null;
        }

        if (!usefulData.isEmpty()) {
            log.debug("Extracted useful data from SpringAIResponse: {}", usefulData);
            return usefulData;
        } else {
            log.debug("No useful data found in SpringAIResponse");
            return null;
        }
    }

    private static Map<String, Object> convertChatResponseMetadataToMap(ChatResponseMetadata chatResponseMetadata) {
        return Map.of(
                ID, chatResponseMetadata.id(),
                USAGE, chatResponseMetadata.usage(),
                MODEL, chatResponseMetadata.model(),
                RESPONSE_CALLS, chatResponseMetadata.responseCalls(),
                GENERATION_RESULTS, chatResponseMetadata.generationResults()
        );
    }

    public static Optional<String> retrieveMessage(Map<String, Object> aiRawResponse) {
        Optional<String> answer = Optional.empty();
        if (aiRawResponse != null && aiRawResponse.containsKey(CHOICES)) {
            List<Map<String, Object>> choices = (List<Map<String, Object>>) aiRawResponse.get(CHOICES);
            if (!choices.isEmpty()) {
                Map<String, Object> firstChoice = choices.getFirst();
                Map<String, Object> message = (Map<String, Object>) firstChoice.get(MESSAGE);
                String content = (String) message.get(CONTENT);

                answer = Optional.ofNullable(content);

                // If content is empty, log warning
                if (content == null || content.isEmpty()) {
                    log.warn("{} in response", CONTENT_IS_EMPTY);
                }
            } else {
                log.error("Response is incorrect. Choices is empty. Response: {}", aiRawResponse);
            }
        } else {
            log.error("Ai response null or doesn't contains choices. Response: {}", aiRawResponse);
        }
        return answer.filter(StringUtils::hasLength);
    }

    private static Optional<String> retrieveMessageFromSpringAI(SpringAIResponse response) {
        try {
            String content = response.chatResponse().getResult().getOutput().getText();
            return Optional.ofNullable(content).filter(StringUtils::hasLength);
        } catch (Exception e) {
            log.error("Error extracting message from SpringAIResponse", e);
            return Optional.empty();
        }
    }

    /**
     * Extracts useful data from AI provider response.
     * Keeps only data not stored in message table:
     * - usage.prompt_tokens - actual prompt token count (we only have estimate)
     * - usage.completion_tokens - actual completion token count (we only have estimate)
     * - usage.total_tokens - total token count (we only have estimate)
     * - finish_reason - completion reason (stop, length, content_filter, etc.)
     * - model - actual model used (may differ from requested)
     *
     * @param aiRawResponse raw response from AI provider
     * @return Map with useful data or null if none
     */

    public static Map<String, Object> extractUsefulData(Map<String, Object> aiRawResponse) {
        if (aiRawResponse == null || aiRawResponse.isEmpty()) {
            log.debug("AI response is null or empty, returning null");
            return null;
        }

        log.info("Full response structure - usage: {}, model: {}, choices: {}",
                aiRawResponse.get(USAGE),
                aiRawResponse.get(MODEL),
                aiRawResponse.get(CHOICES));

        Map<String, Object> usefulData = new HashMap<>();
        boolean hasUsefulData = putUsageIntoMap(aiRawResponse.get(USAGE), usefulData);
        hasUsefulData = extractFinishReasonFromChoices(aiRawResponse, usefulData) || hasUsefulData;
        hasUsefulData = putModelIntoMap(aiRawResponse.get(MODEL), usefulData) || hasUsefulData;

        if (hasUsefulData) {
            log.debug("Extracted useful data: {}", usefulData);
        } else {
            log.warn("No useful data found in AI response. Response structure: usage={}, choices={}, model={}",
                    aiRawResponse.get(USAGE) != null ? "present" : "null",
                    aiRawResponse.get(CHOICES) != null ? "present" : "null",
                    aiRawResponse.get(MODEL) != null ? aiRawResponse.get(MODEL) : "null");
        }

        return hasUsefulData ? usefulData : null;
    }

    @SuppressWarnings("unchecked")
    private static boolean putUsageIntoMap(Object usageObj, Map<String, Object> usefulData) {
        log.debug("Usage object: type={}, value={}", usageObj != null ? usageObj.getClass() : "null", usageObj);
        if (usageObj == null) {
            log.warn("Usage data not found in response");
            return false;
        }
        try {
            Map<String, Object> usage = usageObj instanceof Map
                    ? (Map<String, Object>) usageObj
                    : null;
            if (usage == null) {
                log.warn("Usage is not a Map, skipping. Type: {}", usageObj.getClass());
                return false;
            }
            boolean added = false;
            if (usage.get(PROMPT_TOKENS) != null) {
                usefulData.put(PROMPT_TOKENS, usage.get(PROMPT_TOKENS));
                added = true;
            }
            if (usage.get(COMPLETION_TOKENS) != null) {
                usefulData.put(COMPLETION_TOKENS, usage.get(COMPLETION_TOKENS));
                added = true;
            }
            if (usage.get(TOTAL_TOKENS) != null) {
                usefulData.put(TOTAL_TOKENS, usage.get(TOTAL_TOKENS));
                added = true;
            }
            return added;
        } catch (Exception e) {
            log.error("Error extracting usage data: {}", e.getMessage(), e);
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private static boolean extractFinishReasonFromChoices(Map<String, Object> aiRawResponse, Map<String, Object> usefulData) {
        Object finishReasonObj = aiRawResponse.get(FINISH_REASON);
        if (finishReasonObj != null) {
            usefulData.put(FINISH_REASON, finishReasonObj);
            log.debug("Extracted finish_reason from root: {}", finishReasonObj);
            return true;
        }
        Object choicesObj = aiRawResponse.get(CHOICES);
        if (choicesObj == null || !(choicesObj instanceof List<?> choices) || choices.isEmpty()) {
            log.debug("finish_reason not found in root and choices not found in response");
            return false;
        }
        try {
            Object firstChoiceObj = choices.getFirst();
            if (!(firstChoiceObj instanceof Map<?, ?> firstChoice)) {
                log.debug("First choice is not a Map. Type: {}", firstChoiceObj.getClass());
                return false;
            }
            Object finishReason = ((Map<String, Object>) firstChoice).get(FINISH_REASON);
            if (finishReason != null) {
                usefulData.put(FINISH_REASON, finishReason);
                log.debug("Extracted finish_reason from choices[0]: {}", finishReason);
                return true;
            }
            log.debug("finish_reason not found in first choice. Available keys: {}", firstChoice.keySet());
        } catch (Exception e) {
            log.error("Error extracting finish_reason from choices: {}", e.getMessage(), e);
        }
        return false;
    }

    private static boolean putModelIntoMap(Object modelObj, Map<String, Object> usefulData) {
        if (modelObj == null) {
            log.debug("Model not found in response");
            return false;
        }
        String actualModel = modelObj.toString();
        usefulData.put(ACTUAL_MODEL, actualModel);
        log.debug("Extracted actual_model: {}", actualModel);
        return true;
    }

    /**
     * Processes streaming response from AI, splitting by paragraphs (double newlines \n\n)
     * and sending character-by-character (with correct emoji handling via codePoints).
     *
     * @param responseFlux flux of responses from AI
     * @param listener     handler for each character
     * @return final ChatResponse with full text and metadata
     * @throws WebClientException if an error occurs while processing the stream
     */
    public static ChatResponse processStreamingResponseByParagraphs(
            Flux<ChatResponse> responseFlux,
            Consumer<String> listener
    ) throws WebClientException {
        // Default timeout: 10 minutes (600 seconds), default limit: 4096 characters
        return processStreamingResponseByParagraphs(responseFlux, 4096, listener, Duration.ofMinutes(10));
    }

    /**
     * Processes streaming response from AI, splitting by paragraphs (double newlines \n\n)
     * and sending character-by-character (with correct emoji handling via codePoints).
     *
     * @param responseFlux flux of responses from AI
     * @param maxMessageLength maximum message length (characters). When exceeded, message is split at paragraph boundaries.
     * @param listener     handler for each character
     * @return final ChatResponse with full text and metadata
     * @throws WebClientException if an error occurs while processing the stream
     */
    public static ChatResponse processStreamingResponseByParagraphs(
            Flux<ChatResponse> responseFlux,
            int maxMessageLength,
            Consumer<String> listener
    ) throws WebClientException {
        // Default timeout: 10 minutes (600 seconds)
        return processStreamingResponseByParagraphs(responseFlux, maxMessageLength, listener, Duration.ofMinutes(10));
    }

    /**
     * Processes streaming response from AI, splitting by paragraphs (double newlines \n\n)
     * and sending character-by-character (with correct emoji handling via codePoints).
     *
     * @param responseFlux flux of responses from AI
     * @param listener     handler for each character
     * @param timeout      timeout for stream completion
     * @return final ChatResponse with full text and metadata
     * @throws WebClientException if an error occurs while processing the stream
     */
    public static ChatResponse processStreamingResponseByParagraphs(
            Flux<ChatResponse> responseFlux,
            Consumer<String> listener,
            Duration timeout
    ) throws WebClientException {
        // Default limit: 4096 characters
        return processStreamingResponseByParagraphs(responseFlux, 4096, listener, timeout);
    }

    /**
     * Processes streaming response from AI, splitting by paragraphs (double newlines \n\n)
     * and sending character-by-character (with correct emoji handling via codePoints).
     * If a block exceeds maxMessageLength, it is split at paragraph boundaries.
     *
     * @param responseFlux flux of responses from AI
     * @param maxMessageLength maximum message length (characters). When exceeded, message is split at paragraph boundaries.
     * @param listener     handler for each character
     * @param timeout      timeout for stream completion
     * @return final ChatResponse with full text and metadata
     * @throws WebClientException if an error occurs while processing the stream
     */
    public static ChatResponse processStreamingResponseByParagraphs(
            Flux<ChatResponse> responseFlux,
            int maxMessageLength,
            Consumer<String> listener,
            Duration timeout
    ) throws WebClientException {
        AtomicReference<String> fullResponse = new AtomicReference<>("");
        AtomicReference<String> tail = new AtomicReference<>("");
        AtomicReference<String> accumulatedShortParagraphs = new AtomicReference<>("");
        AtomicReference<String> overflowBuffer = new AtomicReference<>("");
        AtomicReference<ChatResponse> lastResponse = new AtomicReference<>(null);
        AtomicInteger totalChunks = new AtomicInteger(0);
        AtomicInteger chunksWithNonEmptyText = new AtomicInteger(0);

        final int minParagraphLength = maxMessageLength > 0 ? Math.min(300, maxMessageLength) : 300;

        try {
            // Use single chatResponse() flux - we extract both text and metadata from it
            responseFlux
                    .doOnError(error -> {
                        if (shouldLogWithoutStacktrace(error)) {
                            log.warn("Error in streaming response: {}", getRootCauseMessage(error));
                        } else {
                            log.warn("Error in streaming response: {}", error.getMessage());
                        }
                    })
                    .doOnNext(cr -> {
                        log.debug("Received streaming chunk: {}", cr);
                        lastResponse.set(cr);
                        totalChunks.incrementAndGet();
                    })
                    .map(AIUtils::extractText)
                    .filter(Optional::isPresent)
                    .doOnNext(ignored -> chunksWithNonEmptyText.incrementAndGet())
                    .map(Optional::get)
                    // maxMessageLength=0: pass each chunk through as-is, no paragraph buffering
                    .transform(chunks -> maxMessageLength == 0 ? chunks : chunks
                            // concurrency=1, prefetch=1 — do not request many chunks at once from source, or stream buffers and all Telegram messages arrive at once
                            .flatMap(chunk -> splitChunkIntoParagraphs(chunk, tail, maxMessageLength), 1, 1)
                            // Filter empty paragraphs
                            .filter(paragraph -> !paragraph.trim().isEmpty())
                            // Process paragraphs with minimum length (prefetch 1 — do not buffer)
                            .flatMap(paragraph -> processParagraphByMinLength(paragraph.trim(), accumulatedShortParagraphs, minParagraphLength), 1, 1)
                            // Split blocks by maxMessageLength limit (prefetch 1 — do not buffer)
                            .flatMap(block -> splitBlockByMaxLength(block, overflowBuffer, maxMessageLength), 1, 1)
                    )
                    // Send each block as a whole
                    .doOnNext(block -> {
                        fullResponse.updateAndGet(current -> current + block);
                        listener.accept(block);
                    })
                    .blockLast(timeout);

            // Get last ChatResponse for metadata
            ChatResponse finalResponse = lastResponse.get();

            // Process remaining tail and overflow buffer
            String remainingTail = tail.get().trim();
            String overflow = overflowBuffer.get();
            String finalTail = overflow.isEmpty() ? remainingTail : (remainingTail.isEmpty() ? overflow : remainingTail + "\n\n" + overflow);
            processFinalTailAndAccumulated(finalTail, accumulatedShortParagraphs, fullResponse, listener, maxMessageLength, minParagraphLength);

            // Send accumulated short paragraphs if any remain (in case they were not sent above)
            String accumulated = accumulatedShortParagraphs.get().trim();
            if (!accumulated.isEmpty()) {
                fullResponse.updateAndGet(current -> current + accumulated);
                listener.accept(accumulated);
            }

            String finalText = fullResponse.get().trim();
            return buildStreamingResponseResult(finalText, finalResponse, totalChunks, chunksWithNonEmptyText,
                    fullResponse, tail, accumulatedShortParagraphs);

        } catch (Exception e) {
            if (shouldLogWithoutStacktrace(e)) {
                log.error(LOG_ERROR_PROCESSING_STREAMING_RESPONSE, getRootCauseMessage(e));
            } else {
                log.error(LOG_ERROR_PROCESSING_STREAMING_RESPONSE, e.getMessage(), e);
            }
            throw e;
        }
    }

    private static ChatResponse buildStreamingResponseResult(String finalText, ChatResponse finalResponse,
                                                            AtomicInteger totalChunks, AtomicInteger chunksWithNonEmptyText,
                                                            AtomicReference<String> fullResponse,
                                                            AtomicReference<String> tail,
                                                            AtomicReference<String> accumulatedShortParagraphs) {
        if (!finalText.isEmpty() && finalResponse != null) {
            AssistantMessage fullMessage = new AssistantMessage(finalText);
            Generation generation = new Generation(fullMessage);
            return ChatResponse.builder()
                    .generations(List.of(generation))
                    .metadata(finalResponse.getMetadata())
                    .build();
        }
        if (finalResponse != null) {
            String finishReason = extractFinishReason(finalResponse);
            log.debug(
                    "[processStreamingResponseByParagraphs] Empty finalText diagnostic: totalChunks={}, chunksWithNonEmptyText={}, fullResponseLength={}, tailLength={}, accumulatedShortLength={}",
                    totalChunks.get(), chunksWithNonEmptyText.get(), fullResponse.get().length(), tail.get().length(), accumulatedShortParagraphs.get().length());
            logEmptyContentDiagnostics(finalResponse, finishReason, "processStreamingResponseByParagraphs");
            return finalResponse;
        }
        throw new RuntimeException("No data received from streaming response");
    }

    private static Flux<String> splitChunkIntoParagraphs(String chunk, AtomicReference<String> tail, int maxMessageLength) {
        try {
            String text = tail.get() + chunk;
            String[] paragraphs = text.split("\n\n", -1);
            if (text.endsWith("\n\n")) {
                tail.set("");
                return Flux.fromArray(paragraphs);
            }
            String incomplete = paragraphs[paragraphs.length - 1];
            Flux<String> complete = Flux.fromArray(Arrays.copyOfRange(paragraphs, 0, paragraphs.length - 1));
            // Emit incomplete part eagerly when it has reached maxMessageLength — no need to wait for \n\n
            // Split at the next word boundary AFTER maxMessageLength (never cut mid-word)
            if (maxMessageLength > 0 && incomplete.length() >= maxMessageLength) {
                int boundary = findNextWordBoundary(incomplete, maxMessageLength - 1);
                if (boundary <= incomplete.length()) {
                    String toEmit = incomplete.substring(0, boundary);
                    tail.set(incomplete.substring(boundary));
                    return complete.concatWith(Flux.just(toEmit));
                }
                // Word not finished yet — keep accumulating
            }
            tail.set(incomplete);
            return complete;
        } catch (Exception e) {
            log.debug("Error processing chunk: {}", e.getMessage());
            return Flux.empty();
        }
    }

    private static Flux<String> processParagraphByMinLength(String trimmed,
                                                           AtomicReference<String> accumulatedShortParagraphs,
                                                           int minParagraphLength) {
        if (trimmed.length() < minParagraphLength) {
            String toSend = accumulatedShortParagraphs.get();
            toSend = toSend.isEmpty() ? trimmed : toSend + "\n\n" + trimmed;
            accumulatedShortParagraphs.set(toSend);
            if (toSend.length() >= minParagraphLength) {
                accumulatedShortParagraphs.set("");
                return Flux.just(toSend);
            }
            return Flux.empty();
        }
        String accumulated = accumulatedShortParagraphs.get();
        if (!accumulated.isEmpty()) {
            accumulatedShortParagraphs.set("");
            return Flux.just(accumulated + "\n\n" + trimmed);
        }
        return Flux.just(trimmed);
    }

    /**
     * Processes streaming response from AI, sending each character separately
     * (with correct emoji handling via codePoints).
     *
     * @param responseFlux flux of responses from AI
     * @param listener     handler for each character
     * @return final ChatResponse with full text and metadata
     * @throws WebClientException if an error occurs while processing the stream
     */
    public static ChatResponse processStreamingResponse(
            Flux<ChatResponse> responseFlux,
            Consumer<String> listener
    ) throws WebClientException {
        // Default timeout: 10 minutes (600 seconds)
        return processStreamingResponse(responseFlux, listener, Duration.ofMinutes(10));
    }

    /**
     * Processes streaming response from AI, sending each character separately
     * (with correct emoji handling via codePoints).
     *
     * @param responseFlux flux of responses from AI
     * @param listener     handler for each character
     * @param timeout      timeout for stream completion
     * @return final ChatResponse with full text and metadata
     * @throws WebClientException if an error occurs while processing the stream
     */
    public static ChatResponse processStreamingResponse(
            Flux<ChatResponse> responseFlux,
            Consumer<String> listener,
            Duration timeout
    ) throws WebClientException {
        AtomicReference<ChatResponse> lastResponse = new AtomicReference<>(null);
        StringBuilder fullResponse = new StringBuilder();

        try {
            // Use single chatResponse() flux - we extract both text and metadata from it
            responseFlux
                    .doOnNext(lastResponse::set)
                    .map(AIUtils::extractText)
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .doOnNext(fullResponse::append)
                    // Split each chunk into characters (codePoints, does not break emoji)
                    .flatMap(chunk -> Flux.fromStream(chunk.codePoints().mapToObj(cp -> new String(Character.toChars(cp)))))
                    .doOnNext(listener)
                    .blockLast(timeout);

            // Get last ChatResponse for metadata
            ChatResponse finalResponse = lastResponse.get();

            // Get final text
            String finalText = fullResponse.toString().trim();

            // If we got data from stream, return it
            if (!finalText.isEmpty() && finalResponse != null) {
                AssistantMessage fullMessage = new AssistantMessage(finalText);
                Generation generation = new Generation(fullMessage);

                return ChatResponse.builder()
                        .generations(List.of(generation))
                        .metadata(finalResponse.getMetadata())
                        .build();
            }

            // If no data but we have finalResponse, return it (empty content — log diagnostics)
            if (finalResponse != null) {
                if (finalText.isEmpty()) {
                    String finishReason = extractFinishReason(finalResponse);
                    logEmptyContentDiagnostics(finalResponse, finishReason, "processStreamingResponse");
                }
                return finalResponse;
            }

            // If we got nothing, it will be handled in catch block
            throw new RuntimeException("No data received from streaming response");

        } catch (Exception e) {
            if (shouldLogWithoutStacktrace(e)) {
                log.error(LOG_ERROR_PROCESSING_STREAMING_RESPONSE, getRootCauseMessage(e));
            } else {
                log.error(LOG_ERROR_PROCESSING_STREAMING_RESPONSE, e.getMessage(), e);
            }
            throw e;
        }
    }

    public static Optional<String> extractText(ChatResponse response) {
        try {
            Optional<String> result = Optional.ofNullable(response.getResult().getOutput().getText()).filter(StringUtils::hasLength);
            if (result.isEmpty()) {
                logExtractTextEmptyDiagnostic(response);
            }
            return result;
        } catch (Exception e) {
            log.debug("Could not extract content from stream chunk: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private static void logExtractTextEmptyDiagnostic(ChatResponse response) {
        boolean resultNull = response == null || response.getResult() == null;
        boolean outputNull = !resultNull && response.getResult().getOutput() == null;
        Object output = !resultNull && !outputNull ? response.getResult().getOutput() : null;
        String text = output != null ? response.getResult().getOutput().getText() : null;
        log.debug(
                "extractText empty: resultNull={}, outputNull={}, textNull={}, textLength={}",
                resultNull, outputNull, text == null, text != null ? text.length() : 0);
        if (output == null || extractTextEmptyLogCount.incrementAndGet() > EXTRACT_TEXT_EMPTY_LOG_LIMIT) {
            return;
        }
        String outputClass = output.getClass().getName();
        String getContentHint = getContentHintForDiagnostic(output);
        log.debug(
                "extractText empty diagnostic [{}]: outputClass={}, getText() null/empty{}",
                extractTextEmptyLogCount.get(), outputClass, getContentHint);
    }

    private static String getContentHintForDiagnostic(Object output) {
        try {
            Method m = output.getClass().getMethod("getContent");
            Object content = m.invoke(output);
            if (content == null) {
                return ", getContent()=null";
            }
            int len = content instanceof CharSequence cs ? cs.length() : -1;
            return ", getContent()=" + content.getClass().getSimpleName() + "(len=" + len + ")";
        } catch (Exception ignored) {
            return ", getContent() not available";
        }
    }

    /**
     * Converts Markdown to HTML tags for Telegram Bot API.
     * Supports: bold italic (***text***), bold (**text**), italic (*text*),
     * code (`text`), strikethrough (~~text~~).
     * Also escapes HTML characters for safe sending.
     *
     * @param text source text with Markdown
     * @return text with HTML tags, ready for parse_mode="HTML"
     */
    public static String convertMarkdownToHtml(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String escaped = text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
        return applyMarkdownReplacements(escaped);
    }

    private static String applyMarkdownReplacements(String escaped) {
        String html = escaped.replaceAll("\\*\\*\\*(.+?)\\*\\*\\*", "<b><i>$1</i></b>");
        html = html.replaceAll("\\*\\*(.+?)\\*\\*", "<b>$1</b>");
        html = html.replaceAll("\\*([^*]+?)\\*", "<i>$1</i>");
        html = html.replaceAll("`(.+?)`", "<code>$1</code>");
        html = html.replaceAll("~~(.+?)~~", "<s>$1</s>");
        return html;
    }

    private static Optional<String> extractErrorFromMap(Map<String, Object> responseMap) {
        if (responseMap == null) {
            return Optional.of("Response is null");
        }
        Object choicesObj = responseMap.get(CHOICES);
        if (choicesObj == null) {
            return Optional.of("Response does not contain choices field");
        }
        if (!(choicesObj instanceof List<?> choices)) {
            return Optional.empty();
        }
        if (choices.isEmpty()) {
            return Optional.of("Response contains empty choices list");
        }
        Object firstChoiceObj = choices.getFirst();
        if (!(firstChoiceObj instanceof Map<?, ?> firstChoice)) {
            return Optional.empty();
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> firstChoiceMap = (Map<String, Object>) firstChoice;
        Object finishReasonObj = firstChoiceMap.get(FINISH_REASON);
        String finishReason = finishReasonObj != null ? finishReasonObj.toString() : null;
        Object messageObj = firstChoiceMap.get(MESSAGE);
        if (!(messageObj instanceof Map<?, ?> message)) {
            return Optional.empty();
        }
        Object contentObj = ((Map<?, ?>) message).get(CONTENT);
        String content = contentObj != null ? contentObj.toString() : null;
        if (content == null || content.isEmpty()) {
            return Optional.of(getEmptyContentReasonText(finishReason));
        }
        return Optional.empty();
    }

    public static Optional<String> extractError(ChatResponse chatResponse) {
        if (chatResponse == null) {
            return Optional.of("ChatResponse is null");
        }

        try {
            // Check result presence
            chatResponse.getResult();

            // Extract content
            String content = null;
            try {
                content = chatResponse.getResult().getOutput().getText();
            } catch (Exception e) {
                log.debug("Could not extract content from ChatResponse: {}", e.getMessage());
            }

            // If content is empty, extract finishReason to determine cause and log diagnostics
            if (content == null || content.isEmpty()) {
                String finishReason = extractFinishReason(chatResponse);
                logEmptyContentDiagnostics(chatResponse, finishReason, "extractError");
                return Optional.of(getEmptyContentReasonText(finishReason));
            }

            return Optional.empty();
        } catch (Exception e) {
            log.warn("Error extracting error from ChatResponse: {}", e.getMessage(), e);
            return Optional.of("Failed to extract error from response: " + e.getMessage());
        }
    }

    public static String extractFinishReason(ChatResponse chatResponse) {
        if (chatResponse == null) {
            return null;
        }
        try {
            return chatResponse.getResult().getMetadata().getFinishReason();
        } catch (Exception e) {
            log.debug("Could not extract finishReason from ChatResponse: {}", e.getMessage());
        }

        return null;
    }

    /**
     * Logs diagnostics when response content is empty (DEBUG).
     * WARN diagnostic for OpenRouter empty stream is printed at SSE level in WebClientLogCustomizer.
     */
    public static void logEmptyContentDiagnostics(ChatResponse chatResponse, String finishReason, String context) {
        if (chatResponse == null) {
            log.debug("[{}] Empty content: ChatResponse is null", context);
            return;
        }
        try {
            Object metadata = chatResponse.getMetadata();
            Object resultMeta = null;
            String resultMetaStr = null;
            try {
                resultMeta = chatResponse.getResult().getMetadata();
                resultMetaStr = resultMeta != null ? resultMeta.toString() : "null";
            } catch (Exception e) {
                resultMetaStr = "N/A: " + e.getMessage();
            }
            log.debug(
                    "[{}] Empty content from model. finish_reason={}, response_metadata={}, result_metadata={}",
                    context,
                    finishReason != null ? finishReason : "(not provided)",
                    metadata != null ? metadata : "null",
                    resultMetaStr
            );
        } catch (Exception e) {
            log.debug("[{}] Empty content. finish_reason={}, could not log full metadata: {}", context, finishReason, e.getMessage());
        }
    }

    public static String getEmptyContentReasonText(String finishReason) {
        if (finishReason == null || finishReason.isBlank()) {
            return CONTENT_IS_EMPTY;
        }

        String upper = finishReason.toUpperCase();
        return switch (upper) {
            case "LENGTH" ->
                    "Token limit reached (finish_reason: length). Model hit max_tokens limit and response was truncated.";
            case "CONTENT_FILTER" ->
                    "Content was filtered by safety system (finish_reason: content_filter). Response may contain unsafe content.";
            case "STOP" -> "Model stopped naturally but content is empty (finish_reason: stop). This is unexpected.";
            case "FUNCTION_CALL" ->
                    "Model requested function call instead of text response (finish_reason: function_call).";
            case "TOOL_CALLS" -> "Model requested tool calls instead of text response (finish_reason: tool_calls).";
            default -> CONTENT_IS_EMPTY + " (finish_reason: " + finishReason + ")";
        };
    }

    /**
     * Splits a block (possibly with overflow from previous chunk) by maxMessageLength at paragraph boundaries.
     * Updates overflowBuffer with any remainder.
     */
    private static Flux<String> splitBlockByMaxLength(String block, AtomicReference<String> overflowBuffer, int maxMessageLength) {
        String blockToProcess = overflowBuffer.get() + block;
        overflowBuffer.set("");
        if (blockToProcess.length() <= maxMessageLength) {
            return Flux.just(blockToProcess);
        }
        List<String> parts = new ArrayList<>();
        String[] paragraphs = blockToProcess.split("\n\n", -1);
        StringBuilder currentPart = new StringBuilder();
        for (String paragraph : paragraphs) {
            currentPart = appendParagraphToBlockParts(paragraph, currentPart, parts, maxMessageLength);
        }
        addRemainingBlockPart(currentPart, parts, maxMessageLength);
        return parts.isEmpty() ? Flux.empty() : Flux.fromIterable(parts);
    }

    private static StringBuilder appendParagraphToBlockParts(String paragraph, StringBuilder currentPart,
                                                             List<String> parts,
                                                             int maxMessageLength) {
        String paragraphWithSeparator = currentPart.isEmpty() ? paragraph : currentPart + "\n\n" + paragraph;
        if (maxMessageLength > 0 && paragraphWithSeparator.length() <= maxMessageLength) {
            return new StringBuilder(paragraphWithSeparator);
        }
        if (!currentPart.isEmpty()) {
            parts.add(currentPart.toString());
            return appendParagraphToBlockParts(paragraph, new StringBuilder(), parts, maxMessageLength);
        }
        // paragraph itself is too long — split fully into parts, none lost
        String remaining = paragraph;
        while (remaining.length() > maxMessageLength) {
            int splitPoint = findSplitPoint(remaining, maxMessageLength);
            parts.add(remaining.substring(0, splitPoint));
            remaining = remaining.substring(splitPoint);
        }
        return new StringBuilder(remaining);
    }

    private static void addRemainingBlockPart(StringBuilder currentPart, List<String> parts, int maxMessageLength) {
        if (currentPart.isEmpty()) {
            return;
        }
        String remaining = currentPart.toString();
        while (remaining.length() > maxMessageLength) {
            int splitPoint = findSplitPoint(remaining, maxMessageLength);
            parts.add(remaining.substring(0, splitPoint));
            remaining = remaining.substring(splitPoint);
        }
        if (!remaining.isEmpty()) {
            parts.add(remaining);
        }
    }

    /**
     * Processes final tail and accumulated short paragraphs: sends or merges with tail, respects maxMessageLength.
     */
    private static void processFinalTailAndAccumulated(
            String finalTail,
            AtomicReference<String> accumulatedShortParagraphs,
            AtomicReference<String> fullResponse,
            Consumer<String> listener,
            int maxMessageLength,
            int minParagraphLength) {
        if (finalTail.isEmpty()) {
            flushAccumulated(accumulatedShortParagraphs, fullResponse, listener);
            return;
        }
        String accumulated = accumulatedShortParagraphs.get().trim();
        String combined = accumulated.isEmpty() ? finalTail : accumulated + "\n\n" + finalTail;
        if (combined.length() > maxMessageLength) {
            if (!accumulated.isEmpty()) {
                fullResponse.updateAndGet(current -> current + accumulated);
                listener.accept(accumulated);
                accumulatedShortParagraphs.set("");
            }
            sendFinalTailSplitByParagraphs(finalTail, fullResponse, listener, maxMessageLength);
        } else {
            if (finalTail.length() < minParagraphLength && !accumulated.isEmpty()) {
                accumulatedShortParagraphs.set(combined);
            } else {
                if (!accumulated.isEmpty()) {
                    accumulatedShortParagraphs.set("");
                }
                fullResponse.updateAndGet(current -> current + combined);
                listener.accept(combined);
            }
        }
    }

    private static void flushAccumulated(AtomicReference<String> accumulatedShortParagraphs,
                                         AtomicReference<String> fullResponse,
                                         Consumer<String> listener) {
        String accumulated = accumulatedShortParagraphs.get().trim();
        if (!accumulated.isEmpty()) {
            fullResponse.updateAndGet(current -> current + accumulated);
            listener.accept(accumulated);
        }
    }

    private static void sendFinalTailSplitByParagraphs(
            String finalTail,
            AtomicReference<String> fullResponse,
            Consumer<String> listener,
            int maxMessageLength) {
        String[] paragraphs = finalTail.split("\n\n", -1);
        StringBuilder currentPart = new StringBuilder();
        for (String paragraph : paragraphs) {
            currentPart = appendParagraphToPart(currentPart, paragraph, fullResponse, listener, maxMessageLength);
        }
        flushRemainingPart(currentPart, fullResponse, listener, maxMessageLength);
    }

    private static StringBuilder appendParagraphToPart(StringBuilder currentPart, String paragraph,
                                                       AtomicReference<String> fullResponse,
                                                       Consumer<String> listener, int maxMessageLength) {
        String paragraphWithSeparator = currentPart.isEmpty() ? paragraph : currentPart + "\n\n" + paragraph;
        if (maxMessageLength > 0 && paragraphWithSeparator.length() <= maxMessageLength) {
            return new StringBuilder(paragraphWithSeparator);
        }
        if (!currentPart.isEmpty()) {
            String currentPartStr = currentPart.toString();
            fullResponse.updateAndGet(current -> current + currentPartStr);
            listener.accept(currentPartStr);
            return appendParagraphToPart(new StringBuilder(), paragraph, fullResponse, listener, maxMessageLength);
        }
        // paragraph itself is too long — split fully, none lost
        String remaining = paragraph;
        while (remaining.length() > maxMessageLength) {
            int splitPoint = findSplitPoint(remaining, maxMessageLength);
            String part = remaining.substring(0, splitPoint);
            fullResponse.updateAndGet(current -> current + part);
            listener.accept(part);
            remaining = remaining.substring(splitPoint);
        }
        return new StringBuilder(remaining);
    }

    private static void flushRemainingPart(StringBuilder currentPart, AtomicReference<String> fullResponse,
                                           Consumer<String> listener, int maxMessageLength) {
        if (currentPart.isEmpty()) return;
        String remaining = currentPart.toString();
        while (remaining.length() > maxMessageLength) {
            int splitPoint = findSplitPoint(remaining, maxMessageLength);
            String part = remaining.substring(0, splitPoint);
            fullResponse.updateAndGet(current -> current + part);
            listener.accept(part);
            remaining = remaining.substring(splitPoint);
        }
        if (!remaining.isEmpty()) {
            String tail = remaining;
            fullResponse.updateAndGet(current -> current + tail);
            listener.accept(tail);
        }
    }

    /**
     * Finds the next word boundary at or after {@code from}.
     * Returns the index after the boundary character (space or sentence punctuation),
     * or {@code text.length() + 1} if no boundary is found within the text.
     */
    private static int findNextWordBoundary(String text, int from) {
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                return i + 1;
            }
            if ((c == '.' || c == '!' || c == '?' || c == ',') && i + 1 < text.length()) {
                return i + 1;
            }
        }
        return text.length() + 1;
    }

    /**
     * Finds optimal split point for text so we don't cut in the middle of sentence or word.
     * Looks for last sentence boundary (period, exclamation, question mark with space after)
     * or last space before limit.
     *
     * @param text text to split
     * @param maxLength maximum length (limit)
     * @return split position (index of character after boundary)
     */
    private static int findSplitPoint(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text != null ? text.length() : 0;
        }
        int searchStart = Math.max(0, maxLength - 200);
        int bestSplit = findSentenceBoundaryBackward(text, maxLength, searchStart);
        if (bestSplit == maxLength) {
            bestSplit = findLastSpaceBackward(text, maxLength, searchStart);
        }
        return Math.max(1, bestSplit);
    }

    private static int findSentenceBoundaryBackward(String text, int maxLength, int searchStart) {
        for (int i = maxLength - 1; i >= searchStart; i--) {
            if (i >= text.length()) continue;
            char c = text.charAt(i);
            if ((c == '.' || c == '!' || c == '?') && (i + 1 >= text.length() || Character.isWhitespace(text.charAt(i + 1)))) {
                return i + 1;
            }
        }
        return maxLength;
    }

    private static int findLastSpaceBackward(String text, int maxLength, int searchStart) {
        for (int i = maxLength - 1; i >= searchStart; i--) {
            if (i < text.length() && Character.isWhitespace(text.charAt(i))) {
                return i + 1;
            }
        }
        return maxLength;
    }

    public record ChatResponseMetadata(String id, Usage usage, String model, int responseCalls,
                                       List<GenerationResult> generationResults) {
    }

    public record GenerationResult(int toolCalls, String finishReason) {
    }
}
