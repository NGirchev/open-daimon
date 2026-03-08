package io.github.ngirchev.aibot.common.service;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClientException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import io.github.ngirchev.aibot.common.exception.DocumentContentNotExtractableException;
import reactor.core.publisher.Flux;
import io.github.ngirchev.aibot.common.ai.response.AIResponse;
import io.github.ngirchev.aibot.common.ai.response.SpringAIResponse;
import io.github.ngirchev.aibot.common.ai.response.SpringAIStreamResponse;

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

import static io.github.ngirchev.aibot.common.ai.LlmParamNames.*;

@UtilityClass
@Slf4j
public class AIUtils {

    /**
     * Class name of expected empty-stream exception (retry), to avoid printing long stack trace.
     */
    private static final String OPENROUTER_EMPTY_STREAM_EXCEPTION_CLASS =
            "io.github.ngirchev.aibot.ai.springai.retry.OpenRouterEmptyStreamException";
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
            case DEEPSEEK, OPENROUTER, MOCK -> extractError(aiResponse.toMap());
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
                    log.warn("Content is empty in response");
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
        boolean hasUsefulData = false;

        // Extract usage data (actual tokens from provider)
        Object usageObj = aiRawResponse.get(USAGE);
        log.debug("Usage object: type={}, value={}", usageObj != null ? usageObj.getClass() : "null", usageObj);
        if (usageObj != null) {
            try {
                // Try to convert to Map if not already a Map
                Map<String, Object> usage;
                if (usageObj instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> usageMap = (Map<String, Object>) usageObj;
                    usage = usageMap;
                } else {
                    log.warn("Usage is not a Map, skipping. Type: {}", usageObj.getClass());
                    usage = null;
                }

                if (usage != null) {
                    Object promptTokens = usage.get(PROMPT_TOKENS);
                    Object completionTokens = usage.get(COMPLETION_TOKENS);
                    Object totalTokens = usage.get(TOTAL_TOKENS);


                    if (promptTokens != null) {
                        usefulData.put(PROMPT_TOKENS, promptTokens);
                        hasUsefulData = true;
                    }
                    if (completionTokens != null) {
                        usefulData.put(COMPLETION_TOKENS, completionTokens);
                        hasUsefulData = true;
                    }
                    if (totalTokens != null) {
                        usefulData.put(TOTAL_TOKENS, totalTokens);
                        hasUsefulData = true;
                    }
                }
            } catch (Exception e) {
                log.error("Error extracting usage data: {}", e.getMessage(), e);
            }
        } else {
            log.warn("Usage data not found in response");
        }

        // Extract finish_reason from root Map (Spring AI) or from first choice (OpenRouter/DeepSeek)
        Object finishReasonObj = aiRawResponse.get(FINISH_REASON);
        if (finishReasonObj != null) {
            usefulData.put(FINISH_REASON, finishReasonObj);
            hasUsefulData = true;
            log.debug("Extracted finish_reason from root: {}", finishReasonObj);
        } else {
            // Fallback: extract finish_reason from first choice (for OpenRouter/DeepSeek compatibility)
            Object choicesObj = aiRawResponse.get(CHOICES);
            if (choicesObj != null) {
                try {
                    if (choicesObj instanceof List) {
                        @SuppressWarnings("unchecked")
                        List<?> choices = (List<?>) choicesObj;
                        if (!choices.isEmpty()) {
                            Object firstChoiceObj = choices.getFirst();
                            if (firstChoiceObj instanceof Map) {
                                @SuppressWarnings("unchecked")
                                Map<String, Object> firstChoice = (Map<String, Object>) firstChoiceObj;
                                Object finishReason = firstChoice.get(FINISH_REASON);
                                if (finishReason != null) {
                                    usefulData.put(FINISH_REASON, finishReason);
                                    hasUsefulData = true;
                                    log.debug("Extracted finish_reason from choices[0]: {}", finishReason);
                                } else {
                                    log.debug("finish_reason not found in first choice. Available keys: {}", firstChoice.keySet());
                                }
                            } else {
                                log.debug("First choice is not a Map. Type: {}", firstChoiceObj.getClass());
                            }
                        } else {
                            log.debug("Choices list is empty");
                        }
                    } else {
                        log.debug("Choices is not a List. Type: {}", choicesObj.getClass());
                    }
                } catch (Exception e) {
                    log.error("Error extracting finish_reason from choices: {}", e.getMessage(), e);
                }
            } else {
                log.debug("finish_reason not found in root and choices not found in response");
            }
        }

        // Extract actual model used (may differ from requested)
        Object modelObj = aiRawResponse.get(MODEL);
        if (modelObj != null) {
            String actualModel = modelObj.toString();
            usefulData.put(ACTUAL_MODEL, actualModel);
            hasUsefulData = true;
            log.debug("Extracted actual_model: {}", actualModel);
        } else {
            log.debug("Model not found in response");
        }

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

        final int MIN_PARAGRAPH_LENGTH = 300;

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
                        log.debug("Received chunk: {}", cr);
                        lastResponse.set(cr);
                        totalChunks.incrementAndGet();
                    })
                    .map(AIUtils::extractText)
                    .filter(Optional::isPresent)
                    .doOnNext(ignored -> chunksWithNonEmptyText.incrementAndGet())
                    .map(Optional::get)
                    // concurrency=1, prefetch=1 — do not request many chunks at once from source, or stream buffers and all Telegram messages arrive at once
                    .flatMap(chunk -> {
                        try {
                            // Append tail to new chunk
                            String text = tail.get() + chunk;

                            // Split into paragraphs by double newlines
                            String[] paragraphs = text.split("\n\n", -1);

                            // Check if text ends with \n\n
                            boolean endsWithParagraph = text.endsWith("\n\n");

                            if (endsWithParagraph) {
                                // Text ends with separator - process all paragraphs
                                tail.set("");
                                return Flux.fromArray(paragraphs);
                            } else {
                                // Last piece may be incomplete paragraph - keep in tail
                                tail.set(paragraphs[paragraphs.length - 1]);
                                return Flux.fromArray(Arrays.copyOfRange(paragraphs, 0, paragraphs.length - 1));
                            }
                        } catch (Exception e) {
                            log.debug("Error processing chunk: {}", e.getMessage());
                            return Flux.empty();
                        }
                    }, 1, 1)
                    // Filter empty paragraphs
                    .filter(paragraph -> !paragraph.trim().isEmpty())
                    // Process paragraphs with minimum length (prefetch 1 — do not buffer)
                    .flatMap(paragraph -> {
                        String trimmed = paragraph.trim();

                        // If paragraph is short (< MIN_PARAGRAPH_LENGTH), accumulate it
                        if (trimmed.length() < MIN_PARAGRAPH_LENGTH) {
                            String toSend = accumulatedShortParagraphs.get();
                            toSend = toSend.isEmpty() ? trimmed : toSend + "\n\n" + trimmed;
                            accumulatedShortParagraphs.set(toSend);

                            // When accumulated text reaches minimum length, send it
                            if (toSend.length() >= MIN_PARAGRAPH_LENGTH) {
                                accumulatedShortParagraphs.set("");
                                return Flux.just(toSend);
                            }
                            return Flux.empty();
                        } else {
                            // Paragraph is long enough
                            String accumulated = accumulatedShortParagraphs.get();
                            if (!accumulated.isEmpty()) {
                                // Send accumulated short paragraphs together with current one
                                accumulatedShortParagraphs.set("");
                                return Flux.just(accumulated + "\n\n" + trimmed);
                            }
                            return Flux.just(trimmed);
                        }
                    }, 1, 1)
                    // Split blocks by maxMessageLength limit (prefetch 1 — do not buffer)
                    .flatMap(block -> {
                        String blockToProcess = overflowBuffer.get() + block;
                        overflowBuffer.set("");
                        
                        // If block does not exceed limit, send it as-is
                        if (blockToProcess.length() <= maxMessageLength) {
                            return Flux.just(blockToProcess);
                        }
                        
                        // Block exceeds limit - split by paragraphs
                        List<String> parts = new ArrayList<>();
                        String[] paragraphs = blockToProcess.split("\n\n", -1);
                        StringBuilder currentPart = new StringBuilder();
                        
                        for (String paragraph : paragraphs) {
                            String paragraphWithSeparator = currentPart.isEmpty()
                                    ? paragraph 
                                    : currentPart + "\n\n" + paragraph;
                            
                            // If adding paragraph does not exceed limit, add it
                            if (paragraphWithSeparator.length() <= maxMessageLength) {
                                currentPart = new StringBuilder(paragraphWithSeparator);
                            } else {
                                // Current part is ready to send
                                if (!currentPart.isEmpty()) {
                                    parts.add(currentPart.toString());
                                    currentPart = new StringBuilder(paragraph);
                                } else {
                                    // Even single paragraph exceeds limit - find split point at sentence boundaries
                                    int splitPoint = findSplitPoint(paragraph, maxMessageLength);
                                    parts.add(paragraph.substring(0, splitPoint));
                                    overflowBuffer.set(paragraph.substring(splitPoint));
                                }
                            }
                        }
                        
                        // Add remaining part if any
                        if (!currentPart.isEmpty()) {
                            if (currentPart.length() <= maxMessageLength) {
                                parts.add(currentPart.toString());
                            } else {
                                // Last part also exceeds limit - find split point
                                int splitPoint = findSplitPoint(currentPart.toString(), maxMessageLength);
                                parts.add(currentPart.substring(0, splitPoint));
                                overflowBuffer.set(currentPart.substring(splitPoint));
                            }
                        }
                        
                        return parts.isEmpty() ? Flux.empty() : Flux.fromIterable(parts);
                    }, 1, 1)
                    // Send each block as a whole
                    .doOnNext(block -> {
                        fullResponse.updateAndGet(current -> current + block + "\n\n");
                        listener.accept(block + "\n\n");
                    })
                    .blockLast(timeout);

            // Get last ChatResponse for metadata
            ChatResponse finalResponse = lastResponse.get();

            // Process remaining tail and overflow buffer
            String remainingTail = tail.get().trim();
            String overflow = overflowBuffer.get();
            String finalTail = overflow.isEmpty() ? remainingTail : (remainingTail.isEmpty() ? overflow : remainingTail + "\n\n" + overflow);
            
            if (!finalTail.isEmpty()) {
                // Check final tail length against limit
                String accumulated = accumulatedShortParagraphs.get().trim();
                String combined = accumulated.isEmpty() ? finalTail : accumulated + "\n\n" + finalTail;
                
                // If combined text exceeds limit, split it
                if (combined.length() > maxMessageLength) {
                    // Send accumulated short paragraphs separately if any
                    if (!accumulated.isEmpty()) {
                        fullResponse.updateAndGet(current -> current + accumulated);
                        listener.accept(accumulated);
                        accumulatedShortParagraphs.set("");
                    }
                    
                    // Split final tail by paragraphs
                    String[] paragraphs = finalTail.split("\n\n", -1);
                    StringBuilder currentPart = new StringBuilder();
                    
                    for (String paragraph : paragraphs) {
                        String paragraphWithSeparator = currentPart.isEmpty()
                                ? paragraph 
                                : currentPart + "\n\n" + paragraph;
                        
                        if (paragraphWithSeparator.length() <= maxMessageLength) {
                            currentPart = new StringBuilder(paragraphWithSeparator);
                        } else {
                            if (!currentPart.isEmpty()) {
                                String currentPartStr = currentPart.toString();
                                fullResponse.updateAndGet(current -> current + currentPartStr);
                                listener.accept(currentPartStr);
                                currentPart = new StringBuilder(paragraph);
                            } else {
                                // Even single paragraph exceeds limit - find split point
                                if (paragraph.length() > maxMessageLength) {
                                    int splitPoint = findSplitPoint(paragraph, maxMessageLength);
                                    fullResponse.updateAndGet(current -> current + paragraph.substring(0, splitPoint));
                                    listener.accept(paragraph.substring(0, splitPoint));
                                    // Add remainder to next part
                                    String remainder = paragraph.substring(splitPoint);
                                    if (!remainder.isEmpty()) {
                                        currentPart = new StringBuilder(remainder);
                                    }
                                } else {
                                    currentPart = new StringBuilder(paragraph);
                                }
                            }
                        }
                    }
                    
                    // Send last part
                    if (!currentPart.isEmpty()) {
                        String currentPartStr = currentPart.toString();
                        if (currentPartStr.length() <= maxMessageLength) {
                            fullResponse.updateAndGet(current -> current + currentPartStr);
                            listener.accept(currentPartStr);
                        } else {
                            // Last part exceeds limit - find split point
                            int splitPoint = findSplitPoint(currentPartStr, maxMessageLength);
                            String partToSend = currentPartStr.substring(0, splitPoint);
                            fullResponse.updateAndGet(current -> current + partToSend);
                            listener.accept(partToSend);
                            // Remainder is dropped (this is the final part)
                        }
                    }
                } else {
                    // Combined text fits within limit
                    if (finalTail.length() < MIN_PARAGRAPH_LENGTH && !accumulated.isEmpty()) {
                        // Tail is short, add to accumulated
                        accumulatedShortParagraphs.set(combined);
                    } else {
                        // Send combined text
                        if (!accumulated.isEmpty()) {
                            accumulatedShortParagraphs.set("");
                        }
                        fullResponse.updateAndGet(current -> current + combined);
                        listener.accept(combined);
                    }
                }
            } else {
                // Send accumulated short paragraphs if any remain
                String accumulated = accumulatedShortParagraphs.get().trim();
                if (!accumulated.isEmpty()) {
                    fullResponse.updateAndGet(current -> current + accumulated);
                    listener.accept(accumulated);
                }
            }
            
            // Send accumulated short paragraphs if any remain (in case they were not sent above)
            String accumulated = accumulatedShortParagraphs.get().trim();
            if (!accumulated.isEmpty()) {
                fullResponse.updateAndGet(current -> current + accumulated);
                listener.accept(accumulated);
            }

            // Get final text
            String finalText = fullResponse.get().trim();

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
                String finishReason = extractFinishReason(finalResponse);
                log.debug(
                        "[processStreamingResponseByParagraphs] Empty finalText diagnostic: totalChunks={}, chunksWithNonEmptyText={}, fullResponseLength={}, tailLength={}, accumulatedShortLength={}",
                        totalChunks.get(), chunksWithNonEmptyText.get(),
                        fullResponse.get().length(), tail.get().length(), accumulatedShortParagraphs.get().length());
                logEmptyContentDiagnostics(finalResponse, finishReason, "processStreamingResponseByParagraphs");
                return finalResponse;
            }

            // If we got nothing, it will be handled in catch block
            throw new RuntimeException("No data received from streaming response");

        } catch (Exception e) {
            if (shouldLogWithoutStacktrace(e)) {
                log.error("Error processing streaming response: {}", getRootCauseMessage(e));
            } else {
                log.error("Error processing streaming response: {}", e.getMessage(), e);
            }
            throw e;
        }
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
                log.error("Error processing streaming response: {}", getRootCauseMessage(e));
            } else {
                log.error("Error processing streaming response: {}", e.getMessage(), e);
            }
            throw e;
        }
    }

    public static Optional<String> extractText(ChatResponse response) {
        try {
            Optional<String> result = Optional.ofNullable(response.getResult().getOutput().getText()).filter(StringUtils::hasLength);
            if (result.isEmpty()) {
                boolean resultNull = response == null || response.getResult() == null;
                boolean outputNull = !resultNull && response.getResult().getOutput() == null;
                Object output = !resultNull && !outputNull ? response.getResult().getOutput() : null;
                String text = output != null ? response.getResult().getOutput().getText() : null;
                log.debug(
                        "extractText empty: resultNull={}, outputNull={}, textNull={}, textLength={}",
                        resultNull, outputNull, text == null, text != null ? text.length() : 0);
                // Diagnose cause of empty content when streaming (e.g. OpenRouter): log first few times
                if (output != null && extractTextEmptyLogCount.incrementAndGet() <= EXTRACT_TEXT_EMPTY_LOG_LIMIT) {
                    String outputClass = output.getClass().getName();
                    String getContentHint = "";
                    try {
                        Method m = output.getClass().getMethod("getContent");
                        Object content = m.invoke(output);
                        if (content == null) {
                            getContentHint = ", getContent()=null";
                        } else {
                            int len = content instanceof CharSequence cs ? cs.length() : -1;
                            getContentHint = ", getContent()=" + content.getClass().getSimpleName() + "(len=" + len + ")";
                        }
                    } catch (Exception ignored) {
                        getContentHint = ", getContent() not available";
                    }
                    log.debug(
                            "extractText empty diagnostic [{}]: outputClass={}, getText() null/empty{}",
                            extractTextEmptyLogCount.get(), outputClass, getContentHint);
                }
            }
            return result;
        } catch (Exception e) {
            log.debug("Could not extract content from stream chunk: {}", e.getMessage());
            return Optional.empty();
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

        // Escape HTML characters first
        String escaped = text
                .replace("&", "&amp;")  // & first so we don't double-escape
                .replace("<", "&lt;")
                .replace(">", "&gt;");

        // Convert Markdown to HTML (order matters - triple stars first)
        // ***text*** -> <b><i>text</i></b> (bold italic)
        String html = escaped.replaceAll("\\*\\*\\*(.+?)\\*\\*\\*", "<b><i>$1</i></b>");

        // **text** -> <b>text</b> (bold)
        html = html.replaceAll("\\*\\*(.+?)\\*\\*", "<b>$1</b>");

        // *text* -> <i>text</i> (italic)
        // After triple and double stars, remaining single stars are italic
        html = html.replaceAll("\\*([^*]+?)\\*", "<i>$1</i>");

        // `text` -> <code>text</code> (code)
        html = html.replaceAll("`(.+?)`", "<code>$1</code>");

        // ~~text~~ -> <s>text</s> (strikethrough)
        html = html.replaceAll("~~(.+?)~~", "<s>$1</s>");

        return html;
    }

    private Optional<String> extractError(Map<String, Object> responseMap) {
        if (responseMap == null) {
            return Optional.of("Response is null");
        }

        Object choicesObj = responseMap.get(CHOICES);
        if (choicesObj instanceof List<?> choices) {
            if (choices.isEmpty()) {
                return Optional.of("Response contains empty choices list");
            }

            Object firstChoiceObj = choices.getFirst();
            if (firstChoiceObj instanceof Map<?, ?> firstChoice) {
                Object finishReasonObj = firstChoice.get(FINISH_REASON);
                String finishReason = finishReasonObj != null ? finishReasonObj.toString() : null;

                Object messageObj = firstChoice.get(MESSAGE);
                if (messageObj instanceof Map<?, ?> message) {
                    Object contentObj = message.get(CONTENT);
                    String content = contentObj != null ? contentObj.toString() : null;

                    if (content == null || content.isEmpty()) {
                        return Optional.of(getEmptyContentReasonText(finishReason));
                    }
                }
            }
        } else if (choicesObj == null) {
            return Optional.of("Response does not contain choices field");
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
            return "Content is empty";
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
            default -> "Content is empty (finish_reason: " + finishReason + ")";
        };
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
        
        // Look for last sentence boundary before limit (period, exclamation, question mark + space)
        // Search backwards from limit, but no more than 200 chars (to avoid searching too far)
        int searchStart = Math.max(0, maxLength - 200);
        int bestSplit = maxLength;
        
        // Sentence boundary patterns: . ! ? followed by space or end of line
        for (int i = maxLength - 1; i >= searchStart; i--) {
            if (i < text.length()) {
                char c = text.charAt(i);
                // Check sentence boundaries: . ! ? with space or end of line after
                if ((c == '.' || c == '!' || c == '?') && (i + 1 >= text.length() || Character.isWhitespace(text.charAt(i + 1)))) {
                    bestSplit = i + 1;
                    break;
                }
            }
        }
        
        // If no sentence boundary found, look for last space before limit
        if (bestSplit == maxLength) {
            for (int i = maxLength - 1; i >= searchStart; i--) {
                if (i < text.length() && Character.isWhitespace(text.charAt(i))) {
                    bestSplit = i + 1;
                    break;
                }
            }
        }
        
        // If not even a space found, return limit (cut at limit)
        // But ensure we don't return 0
        return Math.max(1, bestSplit);
    }

    public record ChatResponseMetadata(String id, Usage usage, String model, int responseCalls,
                                       List<GenerationResult> generationResults) {
    }

    public record GenerationResult(int toolCalls, String finishReason) {
    }
}
