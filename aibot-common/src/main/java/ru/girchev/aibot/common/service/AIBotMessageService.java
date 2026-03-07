package ru.girchev.aibot.common.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import ru.girchev.aibot.common.config.CoreCommonProperties;
import ru.girchev.aibot.common.exception.UserMessageTooLongException;
import ru.girchev.aibot.common.model.*;
import ru.girchev.aibot.common.repository.AIBotMessageRepository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Сервис для работы с сообщениями в диалогах.
 * Заменяет UserRequestService и ServiceResponseService.
 */
@Slf4j
@RequiredArgsConstructor
public class AIBotMessageService {

    private final AIBotMessageRepository messageRepository;
    private final ConversationThreadService conversationThreadService;
    private final AssistantRoleService assistantRoleService;
    private final CoreCommonProperties coreCommonProperties;
    private final TokenCounter tokenCounter;

    /**
     * Сохраняет USER сообщение
     * Автоматически получает или создает активный thread и роль для пользователя
     *
     * @param assistantRoleContent опциональное содержание роли ассистента (если null, используется дефолтная)
     */
    @Transactional
    public AIBotMessage saveUserMessage(
            User user,
            String content,
            RequestType requestType,
            String assistantRoleContent,
            Map<String, Object> metadata) {

        // Получаем или создаем роль ассистента для пользователя
        String roleContent = assistantRoleContent != null
                ? assistantRoleContent
                : coreCommonProperties.getAssistantRole();
        AssistantRole assistantRole = assistantRoleService.getOrCreateDefaultRole(user, roleContent);

        return saveUserMessage(user, content, requestType, assistantRole, metadata, null);
    }

    /**
     * Сохраняет USER сообщение с готовой ролью ассистента (без вложений).
     */
    @Transactional
    public AIBotMessage saveUserMessage(
            User user,
            String content,
            RequestType requestType,
            AssistantRole assistantRole,
            Map<String, Object> metadata) {
        return saveUserMessage(user, content, requestType, assistantRole, metadata, null);
    }

    /**
     * Сохраняет USER сообщение с готовой ролью ассистента
     * Используется специфичными сервисами (TelegramMessageService, RestMessageService)
     * для переиспользования общей логики сохранения сообщений
     *
     * @param attachmentRefs опциональные ссылки на вложения (storageKey, expiresAt, mimeType, filename)
     */
    @Transactional
    public AIBotMessage saveUserMessage(
            User user,
            String content,
            RequestType requestType,
            AssistantRole assistantRole,
            Map<String, Object> metadata,
            List<Map<String, Object>> attachmentRefs) {

        int estimatedTokens = tokenCounter.estimateTokens(content);
        int maxAllowed = coreCommonProperties.getMaxUserMessageTokens();
        if (estimatedTokens > maxAllowed) {
            throw new UserMessageTooLongException(
                    "Сообщение слишком длинное: примерно " + estimatedTokens + " токенов. Лимит: " + maxAllowed + ". Сократите сообщение.");
        }

        // Увеличиваем счетчик использования роли
        assistantRoleService.incrementUsage(assistantRole);

        // Получаем или создаем активный thread для пользователя
        ConversationThread thread = conversationThreadService.getOrCreateThread(user);

        AIBotMessage message = new AIBotMessage();
        message.setUser(user);
        message.setRole(MessageRole.USER);
        message.setContent(content);
        message.setRequestType(requestType);
        message.setAssistantRole(assistantRole);
        message.setThread(thread);
        message.setTokenCount(tokenCounter.estimateTokens(content));

        if (metadata != null) {
            message.setMetadata(metadata);
        }
        if (attachmentRefs != null && !attachmentRefs.isEmpty()) {
            message.setAttachments(attachmentRefs);
        }

        return saveMessageWithSequence(message, thread, true, content);
    }

    /**
     * Сохраняет ASSISTANT сообщение (ответ от AI)
     * Автоматически получает или создает активный thread и роль для пользователя
     *
     * @param assistantRoleContent опциональное содержание роли ассистента (если null, используется дефолтная)
     */
    @Transactional
    public AIBotMessage saveAssistantMessage(
            User user,
            String content,
            String serviceName,
            String assistantRoleContent,
            Integer processingTimeMs,
            Map<String, Object> responseDataMap) {

        // Получаем или создаем роль ассистента для пользователя
        String roleContent = assistantRoleContent != null
                ? assistantRoleContent
                : coreCommonProperties.getAssistantRole();
        AssistantRole assistantRole = assistantRoleService.getOrCreateDefaultRole(user, roleContent);

        return saveAssistantMessage(user, content, serviceName, assistantRole, processingTimeMs, responseDataMap);
    }

    /**
     * Сохраняет ASSISTANT сообщение с готовой ролью ассистента
     * Используется специфичными сервисами для переиспользования общей логики
     */
    @Transactional
    public AIBotMessage saveAssistantMessage(
            User user,
            String content,
            String serviceName,
            AssistantRole assistantRole,
            Integer processingTimeMs,
            Map<String, Object> responseDataMap) {

        // Получаем или создаем активный thread для пользователя
        ConversationThread thread = conversationThreadService.getOrCreateThread(user);

        AIBotMessage message = new AIBotMessage();
        message.setUser(user);
        message.setRole(MessageRole.ASSISTANT);
        message.setContent(content);
        message.setServiceName(serviceName);
        message.setThread(thread);
        message.setAssistantRole(assistantRole);
        message.setProcessingTimeMs(processingTimeMs);
        message.setStatus(ResponseStatus.PENDING);
        message.setTokenCount(tokenCounter.estimateTokens(content));

        if (responseDataMap != null && !responseDataMap.isEmpty()) {
            message.setResponseData(responseDataMap);
        } else {
            log.info("✗ response_data NOT set (responseDataMap={})", responseDataMap);
        }
        return saveMessageWithSequence(message, thread, true, null);
    }

    /**
     * Сохраняет ASSISTANT сообщение с ошибкой
     * Автоматически получает или создает активный thread и роль для пользователя
     *
     * @param assistantRoleContent опциональное содержание роли ассистента (если null, используется дефолтная)
     */
    @Transactional
    public AIBotMessage saveAssistantErrorMessage(
            User user,
            String errorMessage,
            String serviceName,
            String assistantRoleContent,
            String errorData) {

        // Получаем или создаем роль ассистента для пользователя
        String roleContent = assistantRoleContent != null
                ? assistantRoleContent
                : coreCommonProperties.getAssistantRole();
        AssistantRole assistantRole = assistantRoleService.getOrCreateDefaultRole(user, roleContent);

        return saveAssistantErrorMessage(user, errorMessage, serviceName, assistantRole, errorData);
    }

    /**
     * Сохраняет ASSISTANT сообщение с ошибкой и готовой ролью ассистента
     * Используется специфичными сервисами для переиспользования общей логики
     */
    @Transactional
    public AIBotMessage saveAssistantErrorMessage(
            User user,
            String errorMessage,
            String serviceName,
            AssistantRole assistantRole,
            String errorData) {

        // Получаем или создаем активный thread для пользователя
        ConversationThread thread = conversationThreadService.getOrCreateThread(user);

        AIBotMessage message = new AIBotMessage();
        message.setUser(user);
        message.setRole(MessageRole.ASSISTANT);
        message.setContent(""); // Пустое содержимое для ошибок
        message.setServiceName(serviceName);
        message.setErrorMessage(errorMessage);
        message.setThread(thread);
        message.setAssistantRole(assistantRole);
        message.setStatus(ResponseStatus.ERROR);
        message.setTokenCount(0); // Для ошибок токенов нет

        if (errorData != null && !errorData.trim().isEmpty() && !errorData.equals("{}")) {
            Map<String, Object> dataMap = new HashMap<>();
            dataMap.put("data", errorData);
            message.setResponseData(dataMap);
        }
        // Если errorData null или пустой, responseData остается null

        return saveMessageWithSequence(message, thread, false, null);
    }

    /**
     * Сохраняет SYSTEM сообщение (system prompt, summary и т.д.)
     * Автоматически получает или создает активный thread для пользователя
     */
    @Transactional
    public AIBotMessage saveSystemMessage(
            User user,
            String content) {

        // Получаем или создаем активный thread для пользователя
        ConversationThread thread = conversationThreadService.getOrCreateThread(user);

        AIBotMessage message = new AIBotMessage();
        message.setUser(user);
        message.setRole(MessageRole.SYSTEM);
        message.setContent(content);
        message.setThread(thread);
        message.setTokenCount(tokenCounter.estimateTokens(content));

        // SYSTEM сообщения обычно не имеют sequenceNumber или имеют 0
        message.setSequenceNumber(0);

        return messageRepository.save(message);
    }

    /**
     * Обновляет статус сообщения
     */
    @Transactional
    public AIBotMessage updateMessageStatus(AIBotMessage message, ResponseStatus status) {
        message.setStatus(status);
        return messageRepository.save(message);
    }

    /**
     * Вычисляет следующий sequenceNumber для сообщения в thread
     */
    private Integer calculateNextSequenceNumber(ConversationThread thread) {
        Optional<AIBotMessage> lastMessage = messageRepository.findLastByThread(thread);
        return lastMessage
                .map(m -> m.getSequenceNumber() != null ? m.getSequenceNumber() + 1 : 1)
                .orElse(1);
    }

    /**
     * Сохраняет сообщение с установкой sequenceNumber и опциональным обновлением счетчиков thread
     *
     * @param message        сообщение для сохранения
     * @param thread         thread, к которому относится сообщение
     * @param updateCounters нужно ли обновлять счетчики thread
     * @param content        содержимое сообщения (для обновления title thread, может быть null)
     * @return сохраненное сообщение
     */
    private AIBotMessage saveMessageWithSequence(
            AIBotMessage message,
            ConversationThread thread,
            boolean updateCounters,
            String content) {

        // Устанавливаем sequenceNumber
        Integer nextSequence = calculateNextSequenceNumber(thread);
        message.setSequenceNumber(nextSequence);

        // Сохраняем сообщение
        AIBotMessage savedMessage = messageRepository.save(message);

        // Обновляем счетчики thread после сохранения сообщения (если нужно)
        if (updateCounters) {
            conversationThreadService.updateThreadCounters(thread);
            // Обновляем title thread на основе первого сообщения (если нужно)
            if (content != null) {
                conversationThreadService.updateThreadTitleIfNeeded(thread, content);
            }
        }

        return savedMessage;
    }
}

