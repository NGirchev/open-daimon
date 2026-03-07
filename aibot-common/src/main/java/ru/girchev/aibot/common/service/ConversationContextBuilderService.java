package ru.girchev.aibot.common.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import ru.girchev.aibot.common.config.CoreCommonProperties;
import ru.girchev.aibot.common.model.AssistantRole;
import ru.girchev.aibot.common.model.ConversationThread;
import ru.girchev.aibot.common.model.AIBotMessage;
import ru.girchev.aibot.common.model.MessageRole;
import ru.girchev.aibot.common.repository.AIBotMessageRepository;
import ru.girchev.aibot.common.storage.service.FileStorageService;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Сервис для построения контекста для AI запроса с учетом token budget.
 * Формирует "скользящее окно" истории диалога, включая summary и memory bullets.
 * Для USER-сообщений с вложениями (message.attachments) подтягивает изображения из MinIO до истечения TTL.
 */
@Slf4j
public class ConversationContextBuilderService {

    private static final String ROLE = "role";
    private static final String CONTENT = "content";
    private static final String TYPE = "type";
    private static final String TEXT = "text";
    private static final String IMAGE_URL = "image_url";
    private static final String URL = "url";
    
    private final AIBotMessageRepository messageRepository;
    private final TokenCounter tokenCounter;
    private final CoreCommonProperties coreCommonProperties;
    private final ObjectProvider<FileStorageService> fileStorageServiceProvider;
    
    public ConversationContextBuilderService(
            AIBotMessageRepository messageRepository,
            TokenCounter tokenCounter,
            CoreCommonProperties coreCommonProperties,
            ObjectProvider<FileStorageService> fileStorageServiceProvider) {
        this.messageRepository = messageRepository;
        this.tokenCounter = tokenCounter;
        this.coreCommonProperties = coreCommonProperties;
        this.fileStorageServiceProvider = fileStorageServiceProvider;
    }
    
    /**
     * Строит контекст для AI запроса с учетом token budget.
     * Content может быть строкой или списком content parts (text + image_url) для multimodal.
     *
     * @param thread текущий conversation thread
     * @param currentUserMessage новый запрос пользователя
     * @param assistantRole роль ассистента (AssistantRole Entity)
     * @return список сообщений: каждый Map содержит "role" и "content" (String или List of parts)
     */
    public List<Map<String, Object>> buildContext(
            ConversationThread thread, 
            String currentUserMessage,
            AssistantRole assistantRole) {
        
        List<Map<String, Object>> context = new ArrayList<>();
        CoreCommonProperties.ManualConversationHistoryProperties historyConfig = coreCommonProperties.getManualConversationHistory();
        int promptBudget = coreCommonProperties.getMaxTotalPromptTokens() - historyConfig.getMaxResponseTokens();
        int remainingTokens = promptBudget;

        // 1. Добавляем system prompt из AssistantRole (обязательно)
        if (historyConfig.getIncludeSystemPrompt() && assistantRole != null) {
            String systemPrompt = assistantRole.getContent();
            int systemTokens = tokenCounter.estimateTokens(systemPrompt);
            context.add(Map.of(ROLE, "system", CONTENT, systemPrompt));
            remainingTokens -= systemTokens;
            log.debug("Added system prompt from AssistantRole {}: {} tokens", 
                assistantRole.getId(), systemTokens);
        }
        
        // 2. Добавляем summary (если есть)
        if (thread.getSummary() != null && !thread.getSummary().isEmpty()) {
            String summaryContent = "Краткое содержание предыдущей беседы:\n" + thread.getSummary();
            if (thread.getMemoryBullets() != null && !thread.getMemoryBullets().isEmpty()) {
                summaryContent += "\n\nКлючевые моменты:\n" + 
                    String.join("\n", thread.getMemoryBullets());
            }
            int summaryTokens = tokenCounter.estimateTokens(summaryContent);
            context.add(Map.of(ROLE, "system", CONTENT, summaryContent));
            remainingTokens -= summaryTokens;
            log.debug("Added summary: {} tokens", summaryTokens);
        }
        
        // 3. Загружаем историю из Message (уже отсортированы по sequence_number)
        List<AIBotMessage> messages = messageRepository.findByThreadOrderBySequenceNumberAsc(thread);
        FileStorageService fileStorage = fileStorageServiceProvider.getIfAvailable();
        
        // 4. Добавляем сообщения, пока не упремся в лимит
        List<Map<String, Object>> historyMessages = new ArrayList<>();
        int historyTokens = 0;
        int currentMessageTokens = tokenCounter.estimateTokens(currentUserMessage);
        OffsetDateTime now = OffsetDateTime.now();
        
        for (AIBotMessage message : messages) {
            if (message.getRole() == MessageRole.SYSTEM) {
                continue;
            }
            
            String content = message.getContent();
            if (content == null || content.isEmpty()) {
                log.warn("Skipping Message {} with null or empty content", message.getId());
                continue;
            }
            
            int messageTokens = tokenCounter.estimateTokens(content);
            if (remainingTokens - messageTokens - currentMessageTokens <
                historyConfig.getMaxResponseTokens()) {
                log.debug("Token budget exceeded, stopping at {} messages", historyMessages.size());
                break;
            }
            
            String role = message.getRole() == MessageRole.USER ? "user" : "assistant";
            Object messageContent = content;
            if (message.getRole() == MessageRole.USER && fileStorage != null
                    && message.getAttachments() != null && !message.getAttachments().isEmpty()) {
                Object withMedia = buildContentWithAttachments(content, message.getAttachments(), now, fileStorage);
                if (withMedia != null) {
                    messageContent = withMedia;
                }
            }
            historyMessages.add(Map.of(ROLE, role, CONTENT, messageContent));
            historyTokens += messageTokens;
            remainingTokens -= messageTokens;
        }
        
        context.addAll(historyMessages);
        log.info("Added {} history messages ({} tokens), remaining budget: {}", 
            historyMessages.size(), historyTokens, remainingTokens);
        
        // Текущий запрос пользователя НЕ добавляем в context: его добавит SpringAIGateway через
        // chatOptions.userRole() и createUserMessage(..., attachments), чтобы текущее сообщение
        // шло с вложениями (фото/документы). Если бы мы добавили его сюда как текст, gateway
        // считал бы alreadyPresent и не добавлял бы сообщение с медиа.
        int currentTokens = tokenCounter.estimateTokens(currentUserMessage);
        remainingTokens -= currentTokens;
        int budgetUsed = promptBudget - remainingTokens;
        log.info("Final context: {} messages (current user message will be added by gateway with attachments), ~{} tokens used, ~{} tokens remaining for response",
            context.size(), budgetUsed, remainingTokens);
        
        return context;
    }
    
    /**
     * Строит content как список parts (text + image_url с data:base64) для сообщений с вложениями.
     * Подтягивает только IMAGE из MinIO, если не истёк TTL.
     */
    private List<Map<String, Object>> buildContentWithAttachments(
            String textContent,
            List<Map<String, Object>> attachments,
            OffsetDateTime now,
            FileStorageService fileStorage) {
        List<Map<String, Object>> parts = new ArrayList<>();
        parts.add(Map.of(TYPE, TEXT, TEXT, textContent != null ? textContent : ""));
        boolean hasImage = false;
        for (Map<String, Object> ref : attachments) {
            String expiresAtStr = (String) ref.get("expiresAt");
            if (expiresAtStr == null) continue;
            OffsetDateTime expiresAt;
            try {
                expiresAt = OffsetDateTime.parse(expiresAtStr);
            } catch (Exception e) {
                log.debug("Invalid expiresAt in attachment ref: {}", expiresAtStr);
                continue;
            }
            if (expiresAt.isBefore(now)) continue;
            String mimeType = (String) ref.get("mimeType");
            if (mimeType == null || !mimeType.toLowerCase().startsWith("image/")) continue;
            String storageKey = (String) ref.get("storageKey");
            if (storageKey == null || storageKey.isBlank()) continue;
            try {
                byte[] data = fileStorage.get(storageKey);
                String b64 = Base64.getEncoder().encodeToString(data);
                String dataUrl = "data:" + mimeType + ";base64," + b64;
                parts.add(Map.of(TYPE, IMAGE_URL, IMAGE_URL, Map.of(URL, dataUrl)));
                hasImage = true;
            } catch (Exception e) {
                log.warn("Could not load attachment from storage key {}: {}", storageKey, e.getMessage());
            }
        }
        if (!hasImage) return null;
        return parts;
    }
}

