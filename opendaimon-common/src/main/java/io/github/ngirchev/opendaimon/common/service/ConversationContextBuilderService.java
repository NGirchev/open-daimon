package io.github.ngirchev.opendaimon.common.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import io.github.ngirchev.opendaimon.common.config.CoreCommonProperties;
import io.github.ngirchev.opendaimon.common.model.AssistantRole;
import io.github.ngirchev.opendaimon.common.model.ConversationThread;
import io.github.ngirchev.opendaimon.common.model.OpenDaimonMessage;
import io.github.ngirchev.opendaimon.common.model.MessageRole;
import io.github.ngirchev.opendaimon.common.repository.OpenDaimonMessageRepository;
import io.github.ngirchev.opendaimon.common.storage.service.FileStorageService;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Service for building context for AI request within token budget.
 * Builds a sliding window of conversation history including summary and memory bullets.
 * For USER messages with attachments (message.attachments), loads images from MinIO until TTL expires.
 */
@Slf4j
public class ConversationContextBuilderService {

    private static final String ROLE = "role";
    private static final String CONTENT = "content";
    private static final String TYPE = "type";
    private static final String TEXT = "text";
    private static final String IMAGE_URL = "image_url";
    private static final String URL = "url";
    
    private final OpenDaimonMessageRepository messageRepository;
    private final TokenCounter tokenCounter;
    private final CoreCommonProperties coreCommonProperties;
    private final ObjectProvider<FileStorageService> fileStorageServiceProvider;
    
    public ConversationContextBuilderService(
            OpenDaimonMessageRepository messageRepository,
            TokenCounter tokenCounter,
            CoreCommonProperties coreCommonProperties,
            ObjectProvider<FileStorageService> fileStorageServiceProvider) {
        this.messageRepository = messageRepository;
        this.tokenCounter = tokenCounter;
        this.coreCommonProperties = coreCommonProperties;
        this.fileStorageServiceProvider = fileStorageServiceProvider;
    }
    
    /**
     * Builds context for AI request within token budget.
     * Content may be a string or list of content parts (text + image_url) for multimodal.
     *
     * @param thread current conversation thread
     * @param currentUserMessage new user request
     * @param assistantRole assistant role (AssistantRole entity)
     * @return list of messages: each Map contains "role" and "content" (String or List of parts)
     */
    public List<Map<String, Object>> buildContext(
            ConversationThread thread,
            String currentUserMessage,
            AssistantRole assistantRole) {
        List<Map<String, Object>> context = new ArrayList<>();
        CoreCommonProperties.ManualConversationHistoryProperties historyConfig = coreCommonProperties.getManualConversationHistory();
        int promptBudget = coreCommonProperties.getMaxTotalPromptTokens() - historyConfig.getMaxResponseTokens();
        int remainingTokens = promptBudget;

        remainingTokens = addSystemPromptIfNeeded(assistantRole, historyConfig, context, remainingTokens);
        remainingTokens = addSummaryIfPresent(thread, context, remainingTokens);

        List<OpenDaimonMessage> messages = messageRepository.findByThreadOrderBySequenceNumberAsc(thread);
        FileStorageService fileStorage = fileStorageServiceProvider.getIfAvailable();
        List<Map<String, Object>> historyMessages = buildHistoryMessages(
                messages, currentUserMessage, historyConfig, remainingTokens, fileStorage);
        int historyTokens = 0;
        for (Map<String, Object> m : historyMessages) {
            int tok = tokenCounter.estimateTokens(contentFromMessageMap(m));
            historyTokens += tok;
            remainingTokens -= tok;
        }
        context.addAll(historyMessages);
        log.info("Added {} history messages ({} tokens), remaining budget: {}",
                historyMessages.size(), historyTokens, remainingTokens);

        int currentTokens = tokenCounter.estimateTokens(currentUserMessage);
        remainingTokens -= currentTokens;
        log.info("Final context: {} messages (current user message will be added by gateway with attachments), ~{} tokens used, ~{} tokens remaining for response",
                context.size(), promptBudget - remainingTokens, remainingTokens);
        return context;
    }

    private int addSystemPromptIfNeeded(
            AssistantRole assistantRole,
            CoreCommonProperties.ManualConversationHistoryProperties historyConfig,
            List<Map<String, Object>> context,
            int remainingTokens) {
        if (!historyConfig.getIncludeSystemPrompt() || assistantRole == null) {
            return remainingTokens;
        }
        String systemPrompt = assistantRole.getContent();
        int systemTokens = tokenCounter.estimateTokens(systemPrompt);
        context.add(Map.of(ROLE, "system", CONTENT, systemPrompt));
        log.debug("Added system prompt from AssistantRole {}: {} tokens", assistantRole.getId(), systemTokens);
        return remainingTokens - systemTokens;
    }

    private int addSummaryIfPresent(
            ConversationThread thread,
            List<Map<String, Object>> context,
            int remainingTokens) {
        if (thread.getSummary() == null || thread.getSummary().isEmpty()) {
            return remainingTokens;
        }
        String summaryContent = "Summary of previous conversation:\n" + thread.getSummary();
        if (thread.getMemoryBullets() != null && !thread.getMemoryBullets().isEmpty()) {
            summaryContent += "\n\nKey points:\n" + String.join("\n", thread.getMemoryBullets());
        }
        int summaryTokens = tokenCounter.estimateTokens(summaryContent);
        context.add(Map.of(ROLE, "system", CONTENT, summaryContent));
        log.debug("Added summary: {} tokens", summaryTokens);
        return remainingTokens - summaryTokens;
    }

    private static String contentFromMessageMap(Map<String, Object> m) {
        Object c = m.get(CONTENT);
        if (c instanceof String s) return s;
        return c != null ? c.toString() : "";
    }

    private List<Map<String, Object>> buildHistoryMessages(
            List<OpenDaimonMessage> messages,
            String currentUserMessage,
            CoreCommonProperties.ManualConversationHistoryProperties historyConfig,
            int remainingTokens,
            FileStorageService fileStorage) {
        List<Map<String, Object>> historyMessages = new ArrayList<>();
        int currentMessageTokens = tokenCounter.estimateTokens(currentUserMessage);
        OffsetDateTime now = OffsetDateTime.now();
        for (OpenDaimonMessage message : messages) {
            Map<String, Object> entry = toHistoryEntryOrNull(message, now, fileStorage);
            if (entry == null) continue;
            String content = message.getContent();
            int messageTokens = tokenCounter.estimateTokens(content != null ? content : "");
            if (remainingTokens - messageTokens - currentMessageTokens < historyConfig.getMaxResponseTokens()) {
                log.debug("Token budget exceeded, stopping at {} messages", historyMessages.size());
                break;
            }
            historyMessages.add(entry);
            remainingTokens -= messageTokens;
        }
        return historyMessages;
    }

    private Map<String, Object> toHistoryEntryOrNull(OpenDaimonMessage message, OffsetDateTime now, FileStorageService fileStorage) {
        if (message.getRole() == MessageRole.SYSTEM) return null;
        String content = message.getContent();
        if (content == null || content.isEmpty()) {
            log.warn("Skipping Message {} with null or empty content", message.getId());
            return null;
        }
        String role = message.getRole() == MessageRole.USER ? "user" : "assistant";
        Object messageContent = content;
        if (message.getRole() == MessageRole.USER && fileStorage != null
                && message.getAttachments() != null && !message.getAttachments().isEmpty()) {
            Object withMedia = buildContentWithAttachments(content, message.getAttachments(), now, fileStorage);
            if (withMedia != null) messageContent = withMedia;
        }
        return Map.of(ROLE, role, CONTENT, messageContent);
    }
    
    /**
     * Builds content as list of parts (text + image_url with data:base64) for messages with attachments.
     * Loads only IMAGE from MinIO if TTL has not expired.
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
            if (addImagePartIfValid(ref, now, fileStorage, parts)) {
                hasImage = true;
            }
        }
        return hasImage ? parts : null;
    }

    private boolean addImagePartIfValid(
            Map<String, Object> ref,
            OffsetDateTime now,
            FileStorageService fileStorage,
            List<Map<String, Object>> parts) {
        String expiresAtStr = (String) ref.get("expiresAt");
        if (expiresAtStr == null) return false;
        try {
            if (OffsetDateTime.parse(expiresAtStr).isBefore(now)) return false;
        } catch (Exception e) {
            log.debug("Invalid expiresAt in attachment ref: {}", expiresAtStr);
            return false;
        }
        String mimeType = (String) ref.get("mimeType");
        if (mimeType == null || !mimeType.toLowerCase().startsWith("image/")) return false;
        String storageKey = (String) ref.get("storageKey");
        if (storageKey == null || storageKey.isBlank()) return false;
        try {
            byte[] data = fileStorage.get(storageKey);
            String b64 = Base64.getEncoder().encodeToString(data);
            parts.add(Map.of(TYPE, IMAGE_URL, IMAGE_URL, Map.of(URL, "data:" + mimeType + ";base64," + b64)));
            return true;
        } catch (Exception e) {
            log.warn("Could not load attachment from storage key {}: {}", storageKey, e.getMessage());
            return false;
        }
    }
}

