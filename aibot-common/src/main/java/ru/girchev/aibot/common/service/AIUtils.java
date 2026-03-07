package ru.girchev.aibot.common.service;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClientException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import ru.girchev.aibot.common.exception.DocumentContentNotExtractableException;
import reactor.core.publisher.Flux;
import ru.girchev.aibot.common.ai.response.AIResponse;
import ru.girchev.aibot.common.ai.response.SpringAIResponse;
import ru.girchev.aibot.common.ai.response.SpringAIStreamResponse;

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

import static ru.girchev.aibot.common.ai.LlmParamNames.*;

@UtilityClass
@Slf4j
public class AIUtils {

    /**
     * Имя класса ожидаемой ошибки пустого стрима (ретрай), чтобы не печатать длинный стек.
     */
    private static final String OPENROUTER_EMPTY_STREAM_EXCEPTION_CLASS =
            "ru.girchev.aibot.ai.springai.retry.OpenRouterEmptyStreamException";
    private static final AtomicInteger extractTextEmptyLogCount = new AtomicInteger(0);
    private static final int EXTRACT_TEXT_EMPTY_LOG_LIMIT = 3;

    /**
     * Проверяет, есть ли в цепочке причин OpenRouterEmptyStreamException (ожидаемая ошибка ретрая).
     * Используется, чтобы логировать такие ошибки без стека и не забивать лог.
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
     * Ошибки, для которых не нужно печатать полный стектрейс (реактивный/HTTP), чтобы не забивать лог.
     * true: OpenRouterEmptyStreamException или WebClientResponseException в цепочке cause.
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
     * Сообщение корневой причины для краткого лога.
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
     * Извлекает сообщение из AIResponse.
     *
     * @param aiResponse ответ от AI провайдера
     * @return Optional с текстом сообщения
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
     * Извлекает полезные данные из AIResponse.
     *
     * @param aiResponse ответ от AI провайдера
     * @return Map с полезными данными или null, если нет полезных данных
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

                // Если content пустой, логируем предупреждение
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
     * Извлекает полезные данные из ответа AI провайдера.
     * Сохраняет только данные, которых нет в таблице message:
     * - usage.prompt_tokens - реальное количество токенов в промпте (у нас только оценка)
     * - usage.completion_tokens - реальное количество токенов в ответе (у нас только оценка)
     * - usage.total_tokens - общее количество токенов (у нас только оценка)
     * - finish_reason - причина завершения (stop, length, content_filter и т.д.)
     * - model - реальная модель, которая использовалась (может отличаться от запрошенной)
     *
     * @param aiRawResponse сырой ответ от AI провайдера
     * @return Map с полезными данными или null, если нет полезных данных
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

        // Извлекаем usage данные (реальные токены от провайдера)
        Object usageObj = aiRawResponse.get(USAGE);
        log.debug("Usage object: type={}, value={}", usageObj != null ? usageObj.getClass() : "null", usageObj);
        if (usageObj != null) {
            try {
                // Пытаемся преобразовать в Map, если это не Map напрямую
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

        // Извлекаем finish_reason из корня Map (для Spring AI) или из первого choice (для OpenRouter/DeepSeek)
        Object finishReasonObj = aiRawResponse.get(FINISH_REASON);
        if (finishReasonObj != null) {
            usefulData.put(FINISH_REASON, finishReasonObj);
            hasUsefulData = true;
            log.debug("Extracted finish_reason from root: {}", finishReasonObj);
        } else {
            // Fallback: извлекаем finish_reason из первого choice (для обратной совместимости с OpenRouter/DeepSeek)
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

        // Извлекаем реальную модель, которая использовалась (может отличаться от запрошенной)
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
     * Обрабатывает streaming ответ от AI, разбивая его по абзацам (двойные переносы строк \n\n)
     * и отправляя посимвольно (с корректной обработкой emoji через codePoints).
     *
     * @param responseFlux поток ответов от AI
     * @param listener     обработчик для каждого символа
     * @return итоговый ChatResponse с полным текстом и метаданными
     * @throws WebClientException если произошла ошибка при обработке стрима
     */
    public static ChatResponse processStreamingResponseByParagraphs(
            Flux<ChatResponse> responseFlux,
            Consumer<String> listener
    ) throws WebClientException {
        // Дефолтный таймаут: 10 минут (600 секунд), дефолтный лимит: 4096 символов
        return processStreamingResponseByParagraphs(responseFlux, 4096, listener, Duration.ofMinutes(10));
    }

    /**
     * Обрабатывает streaming ответ от AI, разбивая его по абзацам (двойные переносы строк \n\n)
     * и отправляя посимвольно (с корректной обработкой emoji через codePoints).
     *
     * @param responseFlux поток ответов от AI
     * @param maxMessageLength максимальная длина сообщения (символов). При превышении сообщение разбивается по границам абзацев.
     * @param listener     обработчик для каждого символа
     * @return итоговый ChatResponse с полным текстом и метаданными
     * @throws WebClientException если произошла ошибка при обработке стрима
     */
    public static ChatResponse processStreamingResponseByParagraphs(
            Flux<ChatResponse> responseFlux,
            int maxMessageLength,
            Consumer<String> listener
    ) throws WebClientException {
        // Дефолтный таймаут: 10 минут (600 секунд)
        return processStreamingResponseByParagraphs(responseFlux, maxMessageLength, listener, Duration.ofMinutes(10));
    }

    /**
     * Обрабатывает streaming ответ от AI, разбивая его по абзацам (двойные переносы строк \n\n)
     * и отправляя посимвольно (с корректной обработкой emoji через codePoints).
     *
     * @param responseFlux поток ответов от AI
     * @param listener     обработчик для каждого символа
     * @param timeout      таймаут ожидания завершения стрима
     * @return итоговый ChatResponse с полным текстом и метаданными
     * @throws WebClientException если произошла ошибка при обработке стрима
     */
    public static ChatResponse processStreamingResponseByParagraphs(
            Flux<ChatResponse> responseFlux,
            Consumer<String> listener,
            Duration timeout
    ) throws WebClientException {
        // Дефолтный лимит: 4096 символов
        return processStreamingResponseByParagraphs(responseFlux, 4096, listener, timeout);
    }

    /**
     * Обрабатывает streaming ответ от AI, разбивая его по абзацам (двойные переносы строк \n\n)
     * и отправляя посимвольно (с корректной обработкой emoji через codePoints).
     * Если блок превышает maxMessageLength, он разбивается по границам абзацев.
     *
     * @param responseFlux поток ответов от AI
     * @param maxMessageLength максимальная длина сообщения (символов). При превышении сообщение разбивается по границам абзацев.
     * @param listener     обработчик для каждого символа
     * @param timeout      таймаут ожидания завершения стрима
     * @return итоговый ChatResponse с полным текстом и метаданными
     * @throws WebClientException если произошла ошибка при обработке стрима
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
            // Используем ОДИН поток chatResponse() - из него извлекаем и текст, и метаданные
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
                    // concurrency=1, prefetch=1 — не запрашивать у источника много чанков сразу, иначе стрим буферизуется и все сообщения в Telegram приходят разом
                    .flatMap(chunk -> {
                        try {
                            // Объединяем хвост с новым чанком
                            String text = tail.get() + chunk;

                            // Разбиваем на абзацы по двойным переносам строк
                            String[] paragraphs = text.split("\n\n", -1);

                            // Проверяем, заканчивается ли текст на \n\n
                            boolean endsWithParagraph = text.endsWith("\n\n");

                            if (endsWithParagraph) {
                                // Текст заканчивается разделителем - обрабатываем все параграфы
                                tail.set("");
                                return Flux.fromArray(paragraphs);
                            } else {
                                // Последний кусок может быть незавершённым абзацем - оставляем в хвосте
                                tail.set(paragraphs[paragraphs.length - 1]);
                                return Flux.fromArray(Arrays.copyOfRange(paragraphs, 0, paragraphs.length - 1));
                            }
                        } catch (Exception e) {
                            log.debug("Error processing chunk: {}", e.getMessage());
                            return Flux.empty();
                        }
                    }, 1, 1)
                    // Фильтруем пустые абзацы
                    .filter(paragraph -> !paragraph.trim().isEmpty())
                    // Обрабатываем параграфы с учётом минимальной длины (prefetch 1 — не буферизовать)
                    .flatMap(paragraph -> {
                        String trimmed = paragraph.trim();

                        // Если параграф короткий (< 100 символов), накапливаем его
                        if (trimmed.length() < MIN_PARAGRAPH_LENGTH) {
                            String toSend = accumulatedShortParagraphs.get();
                            toSend = toSend.isEmpty() ? trimmed : toSend + "\n\n" + trimmed;
                            accumulatedShortParagraphs.set(toSend);

                            // Если накопленный текст достиг минимальной длины, отправляем его
                            if (toSend.length() >= MIN_PARAGRAPH_LENGTH) {
                                accumulatedShortParagraphs.set("");
                                return Flux.just(toSend);
                            }
                            return Flux.empty();
                        } else {
                            // Параграф достаточно длинный
                            String accumulated = accumulatedShortParagraphs.get();
                            if (!accumulated.isEmpty()) {
                                // Отправляем накопленные короткие параграфы вместе с текущим
                                accumulatedShortParagraphs.set("");
                                return Flux.just(accumulated + "\n\n" + trimmed);
                            }
                            return Flux.just(trimmed);
                        }
                    }, 1, 1)
                    // Разбиваем блоки по лимиту maxMessageLength (prefetch 1 — не буферизовать)
                    .flatMap(block -> {
                        String blockToProcess = overflowBuffer.get() + block;
                        overflowBuffer.set("");
                        
                        // Если блок не превышает лимит, отправляем его целиком
                        if (blockToProcess.length() <= maxMessageLength) {
                            return Flux.just(blockToProcess);
                        }
                        
                        // Блок превышает лимит - разбиваем по абзацам
                        List<String> parts = new ArrayList<>();
                        String[] paragraphs = blockToProcess.split("\n\n", -1);
                        StringBuilder currentPart = new StringBuilder();
                        
                        for (String paragraph : paragraphs) {
                            String paragraphWithSeparator = currentPart.isEmpty()
                                    ? paragraph 
                                    : currentPart + "\n\n" + paragraph;
                            
                            // Если добавление абзаца не превышает лимит, добавляем его
                            if (paragraphWithSeparator.length() <= maxMessageLength) {
                                currentPart = new StringBuilder(paragraphWithSeparator);
                            } else {
                                // Текущая часть готова к отправке
                                if (!currentPart.isEmpty()) {
                                    parts.add(currentPart.toString());
                                    currentPart = new StringBuilder(paragraph);
                                } else {
                                    // Даже один абзац превышает лимит - находим место разрыва по границам предложений
                                    int splitPoint = findSplitPoint(paragraph, maxMessageLength);
                                    parts.add(paragraph.substring(0, splitPoint));
                                    overflowBuffer.set(paragraph.substring(splitPoint));
                                }
                            }
                        }
                        
                        // Добавляем оставшуюся часть, если она есть
                        if (!currentPart.isEmpty()) {
                            if (currentPart.length() <= maxMessageLength) {
                                parts.add(currentPart.toString());
                            } else {
                                // Последняя часть тоже превышает лимит - находим место разрыва
                                int splitPoint = findSplitPoint(currentPart.toString(), maxMessageLength);
                                parts.add(currentPart.substring(0, splitPoint));
                                overflowBuffer.set(currentPart.substring(splitPoint));
                            }
                        }
                        
                        return parts.isEmpty() ? Flux.empty() : Flux.fromIterable(parts);
                    }, 1, 1)
                    // Отправляем каждый блок целиком
                    .doOnNext(block -> {
                        fullResponse.updateAndGet(current -> current + block + "\n\n");
                        listener.accept(block + "\n\n");
                    })
                    .blockLast(timeout);

            // Получаем последний ChatResponse для метаданных
            ChatResponse finalResponse = lastResponse.get();

            // Обрабатываем оставшийся хвост и overflow buffer
            String remainingTail = tail.get().trim();
            String overflow = overflowBuffer.get();
            String finalTail = overflow.isEmpty() ? remainingTail : (remainingTail.isEmpty() ? overflow : remainingTail + "\n\n" + overflow);
            
            if (!finalTail.isEmpty()) {
                // Проверяем длину финального хвоста с учетом лимита
                String accumulated = accumulatedShortParagraphs.get().trim();
                String combined = accumulated.isEmpty() ? finalTail : accumulated + "\n\n" + finalTail;
                
                // Если комбинированный текст превышает лимит, разбиваем его
                if (combined.length() > maxMessageLength) {
                    // Отправляем накопленные короткие параграфы отдельно, если они есть
                    if (!accumulated.isEmpty()) {
                        fullResponse.updateAndGet(current -> current + accumulated);
                        listener.accept(accumulated);
                        accumulatedShortParagraphs.set("");
                    }
                    
                    // Разбиваем финальный хвост по абзацам
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
                                // Даже один абзац превышает лимит - находим место разрыва
                                if (paragraph.length() > maxMessageLength) {
                                    int splitPoint = findSplitPoint(paragraph, maxMessageLength);
                                    fullResponse.updateAndGet(current -> current + paragraph.substring(0, splitPoint));
                                    listener.accept(paragraph.substring(0, splitPoint));
                                    // Остаток добавляем к следующей части
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
                    
                    // Отправляем последнюю часть
                    if (!currentPart.isEmpty()) {
                        String currentPartStr = currentPart.toString();
                        if (currentPartStr.length() <= maxMessageLength) {
                            fullResponse.updateAndGet(current -> current + currentPartStr);
                            listener.accept(currentPartStr);
                        } else {
                            // Последняя часть превышает лимит - находим место разрыва
                            int splitPoint = findSplitPoint(currentPartStr, maxMessageLength);
                            String partToSend = currentPartStr.substring(0, splitPoint);
                            fullResponse.updateAndGet(current -> current + partToSend);
                            listener.accept(partToSend);
                            // Остаток теряется (это финальная часть)
                        }
                    }
                } else {
                    // Комбинированный текст помещается в лимит
                    if (finalTail.length() < MIN_PARAGRAPH_LENGTH && !accumulated.isEmpty()) {
                        // Хвост короткий, добавляем к накопленным
                        accumulatedShortParagraphs.set(combined);
                    } else {
                        // Отправляем комбинированный текст
                        if (!accumulated.isEmpty()) {
                            accumulatedShortParagraphs.set("");
                        }
                        fullResponse.updateAndGet(current -> current + combined);
                        listener.accept(combined);
                    }
                }
            } else {
                // Отправляем накопленные короткие параграфы, если остались
                String accumulated = accumulatedShortParagraphs.get().trim();
                if (!accumulated.isEmpty()) {
                    fullResponse.updateAndGet(current -> current + accumulated);
                    listener.accept(accumulated);
                }
            }
            
            // Отправляем накопленные короткие параграфы, если остались (на случай если они не были отправлены выше)
            String accumulated = accumulatedShortParagraphs.get().trim();
            if (!accumulated.isEmpty()) {
                fullResponse.updateAndGet(current -> current + accumulated);
                listener.accept(accumulated);
            }

            // Получаем финальный текст
            String finalText = fullResponse.get().trim();

            // Если получили данные из стрима, возвращаем их
            if (!finalText.isEmpty() && finalResponse != null) {
                AssistantMessage fullMessage = new AssistantMessage(finalText);
                Generation generation = new Generation(fullMessage);

                return ChatResponse.builder()
                        .generations(List.of(generation))
                        .metadata(finalResponse.getMetadata())
                        .build();
            }

            // Если данных нет, но есть finalResponse, возвращаем его (пустой контент — логируем диагностику)
            if (finalResponse != null) {
                String finishReason = extractFinishReason(finalResponse);
                log.debug(
                        "[processStreamingResponseByParagraphs] Empty finalText diagnostic: totalChunks={}, chunksWithNonEmptyText={}, fullResponseLength={}, tailLength={}, accumulatedShortLength={}",
                        totalChunks.get(), chunksWithNonEmptyText.get(),
                        fullResponse.get().length(), tail.get().length(), accumulatedShortParagraphs.get().length());
                logEmptyContentDiagnostics(finalResponse, finishReason, "processStreamingResponseByParagraphs");
                return finalResponse;
            }

            // Если ничего не получили, это будет обработано в catch блоке
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
     * Обрабатывает streaming ответ от AI, отправляя каждый символ отдельно
     * (с корректной обработкой emoji через codePoints).
     *
     * @param responseFlux поток ответов от AI
     * @param listener     обработчик для каждого символа
     * @return итоговый ChatResponse с полным текстом и метаданными
     * @throws WebClientException если произошла ошибка при обработке стрима
     */
    public static ChatResponse processStreamingResponse(
            Flux<ChatResponse> responseFlux,
            Consumer<String> listener
    ) throws WebClientException {
        // Дефолтный таймаут: 10 минут (600 секунд)
        return processStreamingResponse(responseFlux, listener, Duration.ofMinutes(10));
    }

    /**
     * Обрабатывает streaming ответ от AI, отправляя каждый символ отдельно
     * (с корректной обработкой emoji через codePoints).
     *
     * @param responseFlux поток ответов от AI
     * @param listener     обработчик для каждого символа
     * @param timeout      таймаут ожидания завершения стрима
     * @return итоговый ChatResponse с полным текстом и метаданными
     * @throws WebClientException если произошла ошибка при обработке стрима
     */
    public static ChatResponse processStreamingResponse(
            Flux<ChatResponse> responseFlux,
            Consumer<String> listener,
            Duration timeout
    ) throws WebClientException {
        AtomicReference<ChatResponse> lastResponse = new AtomicReference<>(null);
        StringBuilder fullResponse = new StringBuilder();

        try {
            // Используем ОДИН поток chatResponse() - из него извлекаем и текст, и метаданные
            responseFlux
                    .doOnNext(lastResponse::set)
                    .map(AIUtils::extractText)
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .doOnNext(fullResponse::append)
                    // режем каждый chunk на символы (codepoints, не ломает эмодзи)
                    .flatMap(chunk -> Flux.fromStream(chunk.codePoints().mapToObj(cp -> new String(Character.toChars(cp)))))
                    .doOnNext(listener)
                    .blockLast(timeout);

            // Получаем последний ChatResponse для метаданных
            ChatResponse finalResponse = lastResponse.get();

            // Получаем финальный текст
            String finalText = fullResponse.toString().trim();

            // Если получили данные из стрима, возвращаем их
            if (!finalText.isEmpty() && finalResponse != null) {
                AssistantMessage fullMessage = new AssistantMessage(finalText);
                Generation generation = new Generation(fullMessage);

                return ChatResponse.builder()
                        .generations(List.of(generation))
                        .metadata(finalResponse.getMetadata())
                        .build();
            }

            // Если данных нет, но есть finalResponse, возвращаем его (пустой контент — логируем диагностику)
            if (finalResponse != null) {
                if (finalText.isEmpty()) {
                    String finishReason = extractFinishReason(finalResponse);
                    logEmptyContentDiagnostics(finalResponse, finishReason, "processStreamingResponse");
                }
                return finalResponse;
            }

            // Если ничего не получили, это будет обработано в catch блоке
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
                // Диагностика причины пустого контента при стриминге (например OpenRouter): логируем первые разы
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
     * Конвертирует Markdown разметку в HTML теги для Telegram Bot API.
     * Поддерживает: жирный курсив (***текст***), жирный (**текст**), курсив (*текст*),
     * код (`текст`), зачеркнутый (~~текст~~).
     * Также экранирует HTML символы для безопасной отправки.
     *
     * @param text исходный текст с Markdown разметкой
     * @return текст с HTML тегами, готовый для отправки с parse_mode="HTML"
     */
    public static String convertMarkdownToHtml(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }

        // Сначала экранируем HTML символы
        String escaped = text
                .replace("&", "&amp;")  // Сначала &, чтобы не экранировать уже экранированные символы
                .replace("<", "&lt;")
                .replace(">", "&gt;");

        // Конвертируем Markdown в HTML (важен порядок - сначала тройные звездочки)
        // ***текст*** -> <b><i>текст</i></b> (жирный курсив)
        String html = escaped.replaceAll("\\*\\*\\*(.+?)\\*\\*\\*", "<b><i>$1</i></b>");

        // **текст** -> <b>текст</b> (жирный)
        html = html.replaceAll("\\*\\*(.+?)\\*\\*", "<b>$1</b>");

        // *текст* -> <i>текст</i> (курсив)
        // После обработки тройных и двойных звездочек, оставшиеся одинарные - это курсив
        html = html.replaceAll("\\*([^*]+?)\\*", "<i>$1</i>");

        // `текст` -> <code>текст</code> (код)
        html = html.replaceAll("`(.+?)`", "<code>$1</code>");

        // ~~текст~~ -> <s>текст</s> (зачеркнутый)
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
            // Проверяем наличие result
            chatResponse.getResult();

            // Извлекаем content
            String content = null;
            try {
                content = chatResponse.getResult().getOutput().getText();
            } catch (Exception e) {
                log.debug("Could not extract content from ChatResponse: {}", e.getMessage());
            }

            // Если content пустой, извлекаем finishReason для определения причины и логируем диагностику
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
     * Логирует диагностику при пустом контенте ответа (DEBUG).
     * WARN-диагноз для OpenRouter пустого стрима печатается на уровне SSE в WebClientLogCustomizer.
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
     * Находит оптимальное место для разрыва текста, чтобы не резать посередине предложения или слова.
     * Ищет последнюю границу предложения (точка, восклицательный знак, вопросительный знак с пробелом после)
     * или последний пробел перед лимитом.
     *
     * @param text текст для разрыва
     * @param maxLength максимальная длина (лимит)
     * @return позиция разрыва (индекс символа после границы)
     */
    private static int findSplitPoint(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text != null ? text.length() : 0;
        }
        
        // Ищем последнюю границу предложения перед лимитом (точка, восклицательный знак, вопросительный знак + пробел)
        // Ищем в обратном направлении от лимита, но не дальше чем на 200 символов назад (чтобы не искать слишком далеко)
        int searchStart = Math.max(0, maxLength - 200);
        int bestSplit = maxLength;
        
        // Паттерны для границ предложений: точка/восклицательный/вопросительный знак + пробел или конец строки
        for (int i = maxLength - 1; i >= searchStart; i--) {
            if (i < text.length()) {
                char c = text.charAt(i);
                // Проверяем границы предложений: . ! ? с пробелом или концом строки после
                if ((c == '.' || c == '!' || c == '?') && (i + 1 >= text.length() || Character.isWhitespace(text.charAt(i + 1)))) {
                    bestSplit = i + 1;
                    break;
                }
            }
        }
        
        // Если не нашли границу предложения, ищем последний пробел перед лимитом
        if (bestSplit == maxLength) {
            for (int i = maxLength - 1; i >= searchStart; i--) {
                if (i < text.length() && Character.isWhitespace(text.charAt(i))) {
                    bestSplit = i + 1;
                    break;
                }
            }
        }
        
        // Если даже пробел не найден, возвращаем лимит (обрезаем по лимиту)
        // Но убеждаемся, что не возвращаем 0
        return Math.max(1, bestSplit);
    }

    public record ChatResponseMetadata(String id, Usage usage, String model, int responseCalls,
                                       List<GenerationResult> generationResults) {
    }

    public record GenerationResult(int toolCalls, String finishReason) {
    }
}
