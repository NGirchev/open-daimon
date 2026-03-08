package io.github.ngirchev.aibot.telegram.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.annotation.Transactional;
import io.github.ngirchev.aibot.common.config.CoreCommonProperties;
import io.github.ngirchev.aibot.common.model.Attachment;
import io.github.ngirchev.aibot.common.model.AssistantRole;
import io.github.ngirchev.aibot.common.model.AIBotMessage;
import io.github.ngirchev.aibot.common.model.RequestType;
import io.github.ngirchev.aibot.common.service.AIBotMessageService;
import io.github.ngirchev.aibot.common.storage.config.StorageProperties;
import io.github.ngirchev.aibot.telegram.model.TelegramUser;
import io.github.ngirchev.aibot.telegram.model.TelegramUserSession;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Service for Telegram messages.
 * Uses base Message entity, storing Telegram-specific data in metadata.
 * Replaces TelegramUserRequestService.
 */
@Slf4j
@RequiredArgsConstructor
public class TelegramMessageService {
    
    private final AIBotMessageService messageService;
    private final TelegramUserService telegramUserService;
    private final CoreCommonProperties coreCommonProperties;
    private final ObjectProvider<StorageProperties> storagePropertiesProvider;
    
    /**
     * Saves USER message from Telegram user with session and conversation thread.
     * If attachments present, saves refs and expiry (TTL) in message.attachments.
     *
     * @param assistantRoleContent optional assistant role content (if null, default is used)
     * @param attachments optional list of attachments (photos/documents) for saving refs to DB
     */
    @Transactional
    public AIBotMessage saveUserMessage(
            TelegramUser telegramUser,
            TelegramUserSession session,
            String content,
            RequestType requestType,
            String assistantRoleContent,
            List<Attachment> attachments) {
        
        // Get or create assistant role for user via TelegramUserService
        String roleContent = assistantRoleContent != null 
                ? assistantRoleContent 
                : coreCommonProperties.getAssistantRole();
        AssistantRole assistantRole = telegramUserService.getOrCreateAssistantRole(telegramUser, roleContent);
        
        // Prepare Telegram-specific metadata
        Map<String, Object> metadata = null;
        if (session != null) {
            metadata = new HashMap<>();
            metadata.put("session_id", session.getId());
        }
        
        List<Map<String, Object>> attachmentRefs = buildAttachmentRefs(attachments);
        
        return messageService.saveUserMessage(
                telegramUser, 
                content, 
                requestType, 
                assistantRole, 
                metadata,
                attachmentRefs);
    }
    
    /**
     * Builds list of attachment refs for DB (storageKey, expiresAt, mimeType, filename).
     */
    private List<Map<String, Object>> buildAttachmentRefs(List<Attachment> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return null;
        }
        StorageProperties storage = storagePropertiesProvider.getIfAvailable();
        if (storage == null) {
            log.debug("Storage not enabled, skipping attachment refs");
            return null;
        }
        int ttlHours = storage.getMinio().getTtlHours();
        OffsetDateTime expiresAt = OffsetDateTime.now().plusHours(ttlHours);
        String expiresAtIso = expiresAt.toString();
        List<Map<String, Object>> refs = new ArrayList<>();
        for (Attachment a : attachments) {
            Map<String, Object> ref = new HashMap<>();
            ref.put("storageKey", a.key());
            ref.put("expiresAt", expiresAtIso);
            ref.put("mimeType", a.mimeType());
            ref.put("filename", a.filename());
            refs.add(ref);
        }
        return refs;
    }
    
    /**
     * Saves ASSISTANT message (AI response) for Telegram user.
     * Automatically gets or creates active thread and role for user.
     *
     * @param assistantRoleContent optional assistant role content (if null, default is used)
     * @param responseDataMap useful data from AI provider response (usage tokens, finish_reason, etc.)
     */
    @Transactional
    public AIBotMessage saveAssistantMessage(
            TelegramUser telegramUser,
            String content,
            String serviceName,
            String assistantRoleContent,
            Integer processingTimeMs,
            Map<String, Object> responseDataMap) {
        
        String roleContent = assistantRoleContent != null
                ? assistantRoleContent 
                : coreCommonProperties.getAssistantRole();
        AssistantRole assistantRole = telegramUserService.getOrCreateAssistantRole(telegramUser, roleContent);
        return messageService.saveAssistantMessage(
                telegramUser, 
                content, 
                serviceName, 
                assistantRole, 
                processingTimeMs, 
                responseDataMap);
    }
    
    /**
     * Saves ASSISTANT message (AI response) for Telegram user.
     * Overload for backward compatibility without responseDataMap.
     */
    @Transactional
    public AIBotMessage saveAssistantMessage(
            TelegramUser telegramUser,
            String content,
            String serviceName,
            String assistantRoleContent,
            Integer processingTimeMs) {
        return saveAssistantMessage(telegramUser, content, serviceName, assistantRoleContent, processingTimeMs, null);
    }
    
    /**
     * Saves ASSISTANT error message for Telegram user.
     * Automatically gets or creates active thread and role for user.
     *
     * @param assistantRoleContent optional assistant role content (if null, default is used)
     */
    @Transactional
    public AIBotMessage saveAssistantErrorMessage(
            TelegramUser telegramUser,
            String errorMessage,
            String serviceName,
            String assistantRoleContent,
            String errorData) {
        
        // Get or create assistant role for user via TelegramUserService
        String roleContent = assistantRoleContent != null 
                ? assistantRoleContent 
                : coreCommonProperties.getAssistantRole();
        AssistantRole assistantRole = telegramUserService.getOrCreateAssistantRole(telegramUser, roleContent);
        
        // Use base MessageService to save message
        return messageService.saveAssistantErrorMessage(
                telegramUser, 
                errorMessage, 
                serviceName, 
                assistantRole, 
                errorData);
    }
}

