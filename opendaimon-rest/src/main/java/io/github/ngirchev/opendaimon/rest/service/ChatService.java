package io.github.ngirchev.opendaimon.rest.service;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import io.github.ngirchev.opendaimon.common.model.ConversationThread;
import io.github.ngirchev.opendaimon.common.model.OpenDaimonMessage;
import io.github.ngirchev.opendaimon.common.model.MessageRole;
import io.github.ngirchev.opendaimon.common.repository.ConversationThreadRepository;
import io.github.ngirchev.opendaimon.common.repository.OpenDaimonMessageRepository;
import io.github.ngirchev.opendaimon.bulkhead.model.UserPriority;
import io.github.ngirchev.opendaimon.bulkhead.service.IUserPriorityService;
import io.github.ngirchev.opendaimon.common.service.CommandSyncService;
import io.github.ngirchev.opendaimon.common.service.ConversationThreadService;
import io.github.ngirchev.opendaimon.rest.handler.RestChatCommand;
import io.github.ngirchev.opendaimon.rest.handler.RestChatCommandType;
import io.github.ngirchev.opendaimon.rest.dto.ChatRequestDto;
import io.github.ngirchev.opendaimon.rest.dto.ChatResponseDto;
import io.github.ngirchev.opendaimon.rest.dto.ChatSessionDto;
import io.github.ngirchev.opendaimon.rest.dto.ChatMessageDto;
import io.github.ngirchev.opendaimon.rest.model.RestUser;
import io.github.ngirchev.opendaimon.rest.exception.UnauthorizedException;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Service for chat via UI.
 * Manages sessions (conversation threads) and messages.
 */
@Slf4j
@RequiredArgsConstructor
public class ChatService {

    private static final String SESSION_NOT_FOUND_PREFIX = "Session not found: ";

    private final ConversationThreadRepository threadRepository;
    private final ConversationThreadService conversationThreadService;
    private final OpenDaimonMessageRepository messageRepository;
    private final CommandSyncService commandSyncService;
    private final IUserPriorityService userPriorityService;

    /**
     * Sends message to new chat (creates new session)
     */
    @Transactional
    public <T> ChatResponseDto<T> sendMessageToNewChat(String message, RestUser user, HttpServletRequest request, boolean isStream) {
        // Close current active thread (if any)
        threadRepository.findMostRecentActiveThread(user)
                .ifPresent(conversationThreadService::closeThread);

        // Create new thread
        ConversationThread thread = conversationThreadService.createNewThread(user);

        // Send message
        return new ChatResponseDto<>(
                sendMessageInternal(thread.getThreadKey(), message, user, request, isStream),
                thread.getThreadKey()
        );
    }

    /**
     * Sends message to existing session
     */
    @Transactional
    public <T> ChatResponseDto<T> sendMessage(String sessionId, String message, RestUser user, HttpServletRequest request, boolean isStream) {
        ConversationThread thread = getThreadBySessionId(sessionId);

        // Verify thread belongs to user
        if (!thread.getUser().getId().equals(user.getId())) {
            throw new UnauthorizedException("Session does not belong to user");
        }

        // Activate thread (closes current active and activates selected)
        conversationThreadService.activateThread(user, thread);

        // Send message
        return new ChatResponseDto<>(
                sendMessageInternal(thread.getThreadKey(), message, user, request, isStream),
                thread.getThreadKey()
        );
    }

    /**
     * Internal method to send message
     */
    private <T> T sendMessageInternal(String sessionId, String message, RestUser user, HttpServletRequest request, boolean isStream) {
        // Create ChatRequest and send via existing handler
        ChatRequestDto chatRequestDto = new ChatRequestDto(message, null, null, user.getEmail());
        RestChatCommand command = new RestChatCommand(
                chatRequestDto,
                isStream ? RestChatCommandType.STREAM : RestChatCommandType.MESSAGE,
                request,
                user.getId()
        );

        return commandSyncService.syncAndHandle(command, this::getUserPriority);
    }

    /**
     * Gets list of all user sessions
     */
    @Transactional(readOnly = true)
    public List<ChatSessionDto> getSessions(RestUser user) {
        List<ConversationThread> threads = threadRepository.findByUserOrderByLastActivityAtDesc(user);

        return threads.stream()
                .map(thread -> new ChatSessionDto(
                        thread.getThreadKey(),
                        thread.getTitle() != null ? thread.getTitle() : "Untitled",
                        thread.getCreatedAt()
                ))
                .collect(Collectors.toList());
    }

    /**
     * Gets message history for session
     */
    @Transactional(readOnly = true)
    public List<ChatMessageDto> getChatHistory(String sessionId, RestUser user) {
        ConversationThread thread = getThreadBySessionId(sessionId);

        // Verify thread belongs to user
        if (!thread.getUser().getId().equals(user.getId())) {
            throw new UnauthorizedException("Session does not belong to user");
        }

        List<OpenDaimonMessage> messages = messageRepository.findByThreadOrderBySequenceNumberAsc(thread);

        return messages.stream()
                .filter(msg -> msg.getRole() != MessageRole.SYSTEM) // Exclude system messages
                .map(msg -> new ChatMessageDto(
                        msg.getRole().name(),
                        msg.getContent()
                ))
                .collect(Collectors.toList());
    }

    /**
     * Deletes session
     */
    @Transactional
    public void deleteSession(String sessionId, RestUser user) {
        ConversationThread thread = getThreadBySessionId(sessionId);

        // Verify thread belongs to user
        if (!thread.getUser().getId().equals(user.getId())) {
            throw new UnauthorizedException("Session does not belong to user");
        }

        // Delete all messages
        List<OpenDaimonMessage> messages = messageRepository.findByThreadOrderBySequenceNumberAsc(thread);
        messageRepository.deleteAll(messages);

        // Delete thread
        threadRepository.delete(thread);

        log.info("Deleted session {} for user {}", sessionId, user.getEmail());
    }

    private ConversationThread getThreadBySessionId(String sessionId) {
        return threadRepository.findByThreadKey(sessionId)
                .orElseThrow(() -> new UnauthorizedException(SESSION_NOT_FOUND_PREFIX + sessionId));
    }

    private UserPriority getUserPriority(Long userId) {
        return userPriorityService.getUserPriority(userId);
    }
}

