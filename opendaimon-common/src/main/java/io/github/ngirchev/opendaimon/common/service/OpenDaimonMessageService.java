package io.github.ngirchev.opendaimon.common.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.annotation.Transactional;
import io.github.ngirchev.opendaimon.common.config.CoreCommonProperties;
import io.github.ngirchev.opendaimon.common.exception.UserMessageTooLongException;
import io.github.ngirchev.opendaimon.common.model.*;
import io.github.ngirchev.opendaimon.common.repository.OpenDaimonMessageRepository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Service for working with messages in dialogs.
 * Replaces UserRequestService and ServiceResponseService.
 */
@Slf4j
@RequiredArgsConstructor
public class OpenDaimonMessageService {

    private final OpenDaimonMessageRepository messageRepository;
    private final ConversationThreadService conversationThreadService;
    private final AssistantRoleService assistantRoleService;
    private final CoreCommonProperties coreCommonProperties;
    private final TokenCounter tokenCounter;
    /** Self-reference for transactional proxy (avoids bypassing @Transactional on internal calls). */
    private final ObjectProvider<OpenDaimonMessageService> selfProvider;

    /**
     * Saves USER message.
     * Automatically gets or creates active thread and role for user.
     *
     * @param assistantRoleContent optional assistant role content (if null, default is used)
     */
    @Transactional
    public OpenDaimonMessage saveUserMessage(
            User user,
            String content,
            RequestType requestType,
            String assistantRoleContent,
            Map<String, Object> metadata) {

        // Get or create assistant role for user
        String roleContent = assistantRoleContent != null
                ? assistantRoleContent
                : coreCommonProperties.getAssistantRole();
        AssistantRole assistantRole = assistantRoleService.getOrCreateDefaultRole(user, roleContent);

        return selfProvider.getObject().saveUserMessage(user, content, requestType, assistantRole, metadata, null);
    }

    /**
     * Saves USER message with ready assistant role (no attachments).
     */
    @Transactional
    public OpenDaimonMessage saveUserMessage(
            User user,
            String content,
            RequestType requestType,
            AssistantRole assistantRole,
            Map<String, Object> metadata) {
        return selfProvider.getObject().saveUserMessage(user, content, requestType, assistantRole, metadata, null);
    }

    /**
     * Saves USER message with ready assistant role.
     * Used by specific services (TelegramMessageService, RestMessageService)
     * to reuse common message-saving logic.
     *
     * @param attachmentRefs optional attachment refs (storageKey, expiresAt, mimeType, filename)
     */
    @Transactional
    public OpenDaimonMessage saveUserMessage(
            User user,
            String content,
            RequestType requestType,
            AssistantRole assistantRole,
            Map<String, Object> metadata,
            List<Map<String, Object>> attachmentRefs) {

        int estimatedTokens = tokenCounter.estimateTokens(content);
        int maxAllowed = coreCommonProperties.getMaxUserMessageTokens();
        if (estimatedTokens > maxAllowed) {
            throw new UserMessageTooLongException(estimatedTokens, maxAllowed);
        }

        // Increment role usage counter
        assistantRoleService.incrementUsage(assistantRole);

        // Get or create active thread for user
        ConversationThread thread = conversationThreadService.getOrCreateThread(user);

        OpenDaimonMessage message = new OpenDaimonMessage();
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
     * Saves ASSISTANT message (AI response).
     * Automatically gets or creates active thread and role for user.
     *
     * @param assistantRoleContent optional assistant role content (if null, default is used)
     */
    @Transactional
    public OpenDaimonMessage saveAssistantMessage(
            User user,
            String content,
            String serviceName,
            String assistantRoleContent,
            Integer processingTimeMs,
            Map<String, Object> responseDataMap) {

        // Get or create assistant role for user
        String roleContent = assistantRoleContent != null
                ? assistantRoleContent
                : coreCommonProperties.getAssistantRole();
        AssistantRole assistantRole = assistantRoleService.getOrCreateDefaultRole(user, roleContent);

        return selfProvider.getObject().saveAssistantMessage(user, content, serviceName, assistantRole, processingTimeMs, responseDataMap);
    }

    /**
     * Saves ASSISTANT message with ready assistant role.
     * Used by specific services to reuse common logic.
     */
    @Transactional
    public OpenDaimonMessage saveAssistantMessage(
            User user,
            String content,
            String serviceName,
            AssistantRole assistantRole,
            Integer processingTimeMs,
            Map<String, Object> responseDataMap) {

        // Get or create active thread for user
        ConversationThread thread = conversationThreadService.getOrCreateThread(user);

        OpenDaimonMessage message = new OpenDaimonMessage();
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
     * Saves ASSISTANT message with error.
     * Automatically gets or creates active thread and role for user.
     *
     * @param assistantRoleContent optional assistant role content (if null, default is used)
     */
    @Transactional
    public OpenDaimonMessage saveAssistantErrorMessage(
            User user,
            String errorMessage,
            String serviceName,
            String assistantRoleContent,
            String errorData) {

        // Get or create assistant role for user
        String roleContent = assistantRoleContent != null
                ? assistantRoleContent
                : coreCommonProperties.getAssistantRole();
        AssistantRole assistantRole = assistantRoleService.getOrCreateDefaultRole(user, roleContent);

        return selfProvider.getObject().saveAssistantErrorMessage(user, errorMessage, serviceName, assistantRole, errorData);
    }

    /**
     * Saves ASSISTANT message with error and ready assistant role.
     * Used by specific services to reuse common logic.
     */
    @Transactional
    public OpenDaimonMessage saveAssistantErrorMessage(
            User user,
            String errorMessage,
            String serviceName,
            AssistantRole assistantRole,
            String errorData) {

        // Get or create active thread for user
        ConversationThread thread = conversationThreadService.getOrCreateThread(user);

        OpenDaimonMessage message = new OpenDaimonMessage();
        message.setUser(user);
        message.setRole(MessageRole.ASSISTANT);
        message.setContent(""); // Empty content for errors
        message.setServiceName(serviceName);
        message.setErrorMessage(errorMessage);
        message.setThread(thread);
        message.setAssistantRole(assistantRole);
        message.setStatus(ResponseStatus.ERROR);
        message.setTokenCount(0); // No tokens for errors

        if (errorData != null && !errorData.trim().isEmpty() && !errorData.equals("{}")) {
            Map<String, Object> dataMap = new HashMap<>();
            dataMap.put("data", errorData);
            message.setResponseData(dataMap);
        }
        // If errorData is null or empty, responseData stays null

        return saveMessageWithSequence(message, thread, false, null);
    }

    /**
     * Saves SYSTEM message (system prompt, summary, etc.).
     * Automatically gets or creates active thread for user.
     */
    @Transactional
    public OpenDaimonMessage saveSystemMessage(
            User user,
            String content) {

        // Get or create active thread for user
        ConversationThread thread = conversationThreadService.getOrCreateThread(user);

        OpenDaimonMessage message = new OpenDaimonMessage();
        message.setUser(user);
        message.setRole(MessageRole.SYSTEM);
        message.setContent(content);
        message.setThread(thread);
        message.setTokenCount(tokenCounter.estimateTokens(content));

        // SYSTEM messages usually have no sequenceNumber or have 0
        message.setSequenceNumber(0);

        return messageRepository.save(message);
    }

    /**
     * Updates message status.
     */
    @Transactional
    public OpenDaimonMessage updateMessageStatus(OpenDaimonMessage message, ResponseStatus status) {
        message.setStatus(status);
        return messageRepository.save(message);
    }

    /**
     * Calculates next sequenceNumber for message in thread.
     */
    private Integer calculateNextSequenceNumber(ConversationThread thread) {
        Optional<OpenDaimonMessage> lastMessage = messageRepository.findLastByThread(thread);
        return lastMessage
                .map(m -> m.getSequenceNumber() != null ? m.getSequenceNumber() + 1 : 1)
                .orElse(1);
    }

    /**
     * Saves message with sequenceNumber and optional thread counter updates.
     *
     * @param message        message to save
     * @param thread         thread the message belongs to
     * @param updateCounters whether to update thread counters
     * @param content        message content (for thread title update, may be null)
     * @return saved message
     */
    private OpenDaimonMessage saveMessageWithSequence(
            OpenDaimonMessage message,
            ConversationThread thread,
            boolean updateCounters,
            String content) {

        // Set sequenceNumber
        Integer nextSequence = calculateNextSequenceNumber(thread);
        message.setSequenceNumber(nextSequence);

        // Save message
        OpenDaimonMessage savedMessage = messageRepository.save(message);

        // Update thread counters after saving message (if needed)
        if (updateCounters) {
            conversationThreadService.updateThreadCounters(thread);
            // Update thread title from first message (if needed)
            if (content != null) {
                conversationThreadService.updateThreadTitleIfNeeded(thread, content);
            }
        }

        return savedMessage;
    }
}

