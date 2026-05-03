package io.github.ngirchev.opendaimon.telegram.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.annotation.Transactional;
import io.github.ngirchev.opendaimon.common.config.CoreCommonProperties;
import io.github.ngirchev.opendaimon.common.model.Attachment;
import io.github.ngirchev.opendaimon.common.model.AssistantRole;
import io.github.ngirchev.opendaimon.common.model.ConversationThread;
import io.github.ngirchev.opendaimon.common.model.OpenDaimonMessage;
import io.github.ngirchev.opendaimon.common.model.RequestType;
import io.github.ngirchev.opendaimon.common.model.ThreadScopeKind;
import io.github.ngirchev.opendaimon.common.model.User;
import io.github.ngirchev.opendaimon.common.service.ChatOwnerLookup;
import io.github.ngirchev.opendaimon.common.service.ConversationThreadService;
import io.github.ngirchev.opendaimon.common.service.MessageLocalizationService;
import io.github.ngirchev.opendaimon.common.service.OpenDaimonMessageService;
import io.github.ngirchev.opendaimon.common.storage.config.StorageProperties;
import io.github.ngirchev.opendaimon.telegram.model.TelegramUser;
import io.github.ngirchev.opendaimon.telegram.model.TelegramUserSession;

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
    
    private final OpenDaimonMessageService messageService;
    private final TelegramUserService telegramUserService;
    private final CoreCommonProperties coreCommonProperties;
    private final MessageLocalizationService messageLocalizationService;
    private final ObjectProvider<StorageProperties> storagePropertiesProvider;
    private final ConversationThreadService conversationThreadService;
    /** Self-reference for transactional proxy (avoids bypassing @Transactional on internal calls). */
    private final ObjectProvider<TelegramMessageService> selfProvider;
    /** Resolves per-chat settings owner (TelegramGroup for group chats, TelegramUser for privates). */
    private final ChatOwnerLookup chatOwnerLookup;
    private final ChatSettingsService chatSettingsService;
    
    /**
     * Saves USER message from Telegram user with session and conversation thread.
     * If attachments present, saves refs and expiry (TTL) in message.attachments.
     *
     * @param assistantRoleContent optional assistant role content (if null, default is used)
     * @param attachments optional list of attachments (photos/documents) for saving refs to DB
     */
    @Transactional
    public OpenDaimonMessage saveUserMessage(
            TelegramUser telegramUser,
            TelegramUserSession session,
            String content,
            RequestType requestType,
            String assistantRoleContent,
            List<Attachment> attachments) {
        return selfProvider.getObject().saveUserMessage(
                telegramUser,
                session,
                content,
                requestType,
                assistantRoleContent,
                attachments,
                null,
                null);
    }

    /**
     * Saves USER message for Telegram chat scope (without Telegram message ID).
     *
     * @param chatId Telegram chat id; when null, falls back to legacy user scope
     */
    @Transactional
    public OpenDaimonMessage saveUserMessage(
            TelegramUser telegramUser,
            TelegramUserSession session,
            String content,
            RequestType requestType,
            String assistantRoleContent,
            List<Attachment> attachments,
            Long chatId) {
        return selfProvider.getObject().saveUserMessage(
                telegramUser, session, content, requestType,
                assistantRoleContent, attachments, chatId, null);
    }

    /**
     * Saves USER message for Telegram chat scope with Telegram message ID.
     *
     * @param chatId            Telegram chat id; when null, falls back to legacy user scope
     * @param telegramMessageId Telegram message ID for reply-to lookup; may be null
     */
    @Transactional
    public OpenDaimonMessage saveUserMessage(
            TelegramUser telegramUser,
            TelegramUserSession session,
            String content,
            RequestType requestType,
            String assistantRoleContent,
            List<Attachment> attachments,
            Long chatId,
            Integer telegramMessageId) {

        // Get or create assistant role for user via TelegramUserService
        String roleContent = assistantRoleContent != null
                ? assistantRoleContent
                : messageLocalizationService.getMessage(coreCommonProperties.getAssistantRole(), telegramUser.getLanguageCode());
        User assistantRoleOwner = resolveSettingsOwner(telegramUser, chatId);
        AssistantRole assistantRole = chatSettingsService.getOrCreateAssistantRole(assistantRoleOwner, roleContent);

        // Prepare Telegram-specific metadata
        Map<String, Object> metadata = null;
        if (session != null) {
            metadata = new HashMap<>();
            metadata.put("session_id", session.getId());
        }

        List<Map<String, Object>> attachmentRefs = buildAttachmentRefs(attachments);
        ConversationThread thread = chatId != null
                ? conversationThreadService.getOrCreateThread(telegramUser, ThreadScopeKind.TELEGRAM_CHAT, chatId)
                : conversationThreadService.getOrCreateThread(telegramUser);

        Long telegramMsgId = telegramMessageId != null ? telegramMessageId.longValue() : null;
        return messageService.saveUserMessage(
                telegramUser,
                content,
                requestType,
                assistantRole,
                metadata,
                attachmentRefs,
                thread,
                telegramMsgId);
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
    public OpenDaimonMessage saveAssistantMessage(
            TelegramUser telegramUser,
            String content,
            String serviceName,
            String assistantRoleContent,
            Integer processingTimeMs,
            Map<String, Object> responseDataMap) {
        return selfProvider.getObject().saveAssistantMessage(
                telegramUser,
                content,
                serviceName,
                assistantRoleContent,
                processingTimeMs,
                responseDataMap,
                null);
    }

    /**
     * Saves ASSISTANT message to explicit thread.
     */
    @Transactional
    public OpenDaimonMessage saveAssistantMessage(
            TelegramUser telegramUser,
            String content,
            String serviceName,
            String assistantRoleContent,
            Integer processingTimeMs,
            Map<String, Object> responseDataMap,
            ConversationThread thread) {
        
        String roleContent = assistantRoleContent != null
                ? assistantRoleContent 
                : messageLocalizationService.getMessage(coreCommonProperties.getAssistantRole(), telegramUser.getLanguageCode());
        Long chatScopeId = thread != null && thread.getScopeKind() == ThreadScopeKind.TELEGRAM_CHAT
                ? thread.getScopeId() : null;
        User assistantRoleOwner = resolveSettingsOwner(telegramUser, chatScopeId);
        AssistantRole assistantRole = chatSettingsService.getOrCreateAssistantRole(assistantRoleOwner, roleContent);
        return messageService.saveAssistantMessage(
                telegramUser, 
                content, 
                serviceName, 
                assistantRole, 
                processingTimeMs, 
                responseDataMap,
                thread);
    }
    
    /**
     * Saves ASSISTANT message (AI response) for Telegram user.
     * Overload for backward compatibility without responseDataMap.
     */
    @Transactional
    public OpenDaimonMessage saveAssistantMessage(
            TelegramUser telegramUser,
            String content,
            String serviceName,
            String assistantRoleContent,
            Integer processingTimeMs) {
        return selfProvider.getObject().saveAssistantMessage(telegramUser, content, serviceName, assistantRoleContent, processingTimeMs, null, null);
    }
    
    /**
     * Saves ASSISTANT error message for Telegram user.
     * Automatically gets or creates active thread and role for user.
     *
     * @param assistantRoleContent optional assistant role content (if null, default is used)
     */
    @Transactional
    public OpenDaimonMessage saveAssistantErrorMessage(
            TelegramUser telegramUser,
            String errorMessage,
            String serviceName,
            String assistantRoleContent,
            String errorData) {
        return selfProvider.getObject().saveAssistantErrorMessage(
                telegramUser,
                errorMessage,
                serviceName,
                assistantRoleContent,
                errorData,
                null);
    }

    /**
     * Saves ASSISTANT error message to explicit thread.
     */
    @Transactional
    public OpenDaimonMessage saveAssistantErrorMessage(
            TelegramUser telegramUser,
            String errorMessage,
            String serviceName,
            String assistantRoleContent,
            String errorData,
            ConversationThread thread) {
        
        // Get or create assistant role for user via TelegramUserService
        String roleContent = assistantRoleContent != null 
                ? assistantRoleContent 
                : messageLocalizationService.getMessage(coreCommonProperties.getAssistantRole(), telegramUser.getLanguageCode());
        Long chatScopeId = thread != null && thread.getScopeKind() == ThreadScopeKind.TELEGRAM_CHAT
                ? thread.getScopeId() : null;
        User assistantRoleOwner = resolveSettingsOwner(telegramUser, chatScopeId);
        AssistantRole assistantRole = chatSettingsService.getOrCreateAssistantRole(assistantRoleOwner, roleContent);

        // Use base MessageService to save message
        return messageService.saveAssistantErrorMessage(
                telegramUser, 
                errorMessage,
                serviceName,
                assistantRole,
                errorData,
                thread);
    }

    /**
     * Resolves the settings-owner for a save operation: for group chats we want the
     * {@link io.github.ngirchev.opendaimon.telegram.model.TelegramGroup} row so the assistant role
     * comes from the shared group settings; in private chats we fall back to the invoker's
     * {@code TelegramUser}. When {@code chatId} is unknown (legacy user-scope thread) the invoker is used.
     */
    private User resolveSettingsOwner(TelegramUser invoker, Long chatId) {
        if (chatId == null) {
            return invoker;
        }
        return chatOwnerLookup.findByChatId(chatId).orElse(invoker);
    }
}
