package ru.girchev.aibot.telegram.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.annotation.Transactional;
import ru.girchev.aibot.common.config.CoreCommonProperties;
import ru.girchev.aibot.common.model.Attachment;
import ru.girchev.aibot.common.model.AssistantRole;
import ru.girchev.aibot.common.model.AIBotMessage;
import ru.girchev.aibot.common.model.RequestType;
import ru.girchev.aibot.common.service.AIBotMessageService;
import ru.girchev.aibot.common.storage.config.StorageProperties;
import ru.girchev.aibot.telegram.model.TelegramUser;
import ru.girchev.aibot.telegram.model.TelegramUserSession;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Сервис для работы с Telegram сообщениями.
 * Использует базовый Message Entity, сохраняя Telegram-специфичные данные в metadata.
 * Заменяет TelegramUserRequestService.
 */
@Slf4j
@RequiredArgsConstructor
public class TelegramMessageService {
    
    private final AIBotMessageService messageService;
    private final TelegramUserService telegramUserService;
    private final CoreCommonProperties coreCommonProperties;
    private final ObjectProvider<StorageProperties> storagePropertiesProvider;
    
    /**
     * Сохраняет USER сообщение от Telegram пользователя с сессией и conversation thread.
     * При наличии вложений сохраняет ссылки и время истечения (TTL) в message.attachments.
     *
     * @param assistantRoleContent опциональное содержание роли ассистента (если null, используется дефолтная)
     * @param attachments опциональный список вложений (фото/документы) — для сохранения ref в БД
     */
    @Transactional
    public AIBotMessage saveUserMessage(
            TelegramUser telegramUser,
            TelegramUserSession session,
            String content,
            RequestType requestType,
            String assistantRoleContent,
            List<Attachment> attachments) {
        
        // Получаем или создаем роль ассистента для пользователя через TelegramUserService
        String roleContent = assistantRoleContent != null 
                ? assistantRoleContent 
                : coreCommonProperties.getAssistantRole();
        AssistantRole assistantRole = telegramUserService.getOrCreateAssistantRole(telegramUser, roleContent);
        
        // Подготавливаем Telegram-специфичные метаданные
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
     * Строит список ссылок на вложения для сохранения в БД (storageKey, expiresAt, mimeType, filename).
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
     * Сохраняет ASSISTANT сообщение (ответ от AI) для Telegram пользователя
     * Автоматически получает или создает активный thread и роль для пользователя
     * 
     * @param assistantRoleContent опциональное содержание роли ассистента (если null, используется дефолтная)
     * @param responseDataMap полезные данные из ответа AI провайдера (usage tokens, finish_reason и т.д.)
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
     * Сохраняет ASSISTANT сообщение (ответ от AI) для Telegram пользователя
     * Перегрузка для обратной совместимости без responseDataMap
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
     * Сохраняет ASSISTANT сообщение с ошибкой для Telegram пользователя
     * Автоматически получает или создает активный thread и роль для пользователя
     * 
     * @param assistantRoleContent опциональное содержание роли ассистента (если null, используется дефолтная)
     */
    @Transactional
    public AIBotMessage saveAssistantErrorMessage(
            TelegramUser telegramUser,
            String errorMessage,
            String serviceName,
            String assistantRoleContent,
            String errorData) {
        
        // Получаем или создаем роль ассистента для пользователя через TelegramUserService
        String roleContent = assistantRoleContent != null 
                ? assistantRoleContent 
                : coreCommonProperties.getAssistantRole();
        AssistantRole assistantRole = telegramUserService.getOrCreateAssistantRole(telegramUser, roleContent);
        
        // Используем базовый MessageService для сохранения сообщения
        return messageService.saveAssistantErrorMessage(
                telegramUser, 
                errorMessage, 
                serviceName, 
                assistantRole, 
                errorData);
    }
}

