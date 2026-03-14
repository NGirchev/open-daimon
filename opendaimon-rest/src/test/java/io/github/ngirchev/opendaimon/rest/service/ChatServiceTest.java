package io.github.ngirchev.opendaimon.rest.service;

import io.github.ngirchev.opendaimon.bulkhead.model.UserPriority;
import io.github.ngirchev.opendaimon.bulkhead.service.IUserPriorityService;
import io.github.ngirchev.opendaimon.common.model.OpenDaimonMessage;
import io.github.ngirchev.opendaimon.common.model.ConversationThread;
import io.github.ngirchev.opendaimon.common.model.MessageRole;
import io.github.ngirchev.opendaimon.common.repository.OpenDaimonMessageRepository;
import io.github.ngirchev.opendaimon.common.repository.ConversationThreadRepository;
import io.github.ngirchev.opendaimon.common.service.CommandSyncService;
import io.github.ngirchev.opendaimon.common.service.ConversationThreadService;
import io.github.ngirchev.opendaimon.rest.dto.ChatMessageDto;
import io.github.ngirchev.opendaimon.rest.dto.ChatResponseDto;
import io.github.ngirchev.opendaimon.rest.dto.ChatSessionDto;
import io.github.ngirchev.opendaimon.rest.exception.UnauthorizedException;
import io.github.ngirchev.opendaimon.rest.model.RestUser;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatServiceTest {

    @Mock
    private ConversationThreadRepository threadRepository;
    @Mock
    private ConversationThreadService conversationThreadService;
    @Mock
    private OpenDaimonMessageRepository messageRepository;
    @Mock
    private CommandSyncService commandSyncService;
    @Mock
    private HttpServletRequest request;

    private ChatService service;
    @Mock
    private IUserPriorityService userPriorityService;

    private RestUser currentUser;
    private ConversationThread thread;

    @BeforeEach
    void setUp() {
        when(userPriorityService.getUserPriority(any())).thenReturn(UserPriority.REGULAR);
        service = new ChatService(threadRepository, conversationThreadService, messageRepository, commandSyncService, userPriorityService);
        currentUser = new RestUser();
        currentUser.setId(1L);
        currentUser.setEmail("user@test.com");
        thread = new ConversationThread();
        thread.setId(10L);
        thread.setThreadKey("session-123");
        thread.setTitle("Test");
        thread.setUser(currentUser);
        thread.setCreatedAt(OffsetDateTime.now());
    }

    @Nested
    @DisplayName("sendMessageToNewChat")
    class SendMessageToNewChat {

        @Test
        void whenNoActiveThread_createsNewThreadAndSendsMessage() {
            when(threadRepository.findMostRecentActiveThread(currentUser)).thenReturn(Optional.empty());
            when(conversationThreadService.createNewThread(currentUser)).thenReturn(thread);
            when(commandSyncService.syncAndHandle(any(), any())).thenReturn("AI response");

            ChatResponseDto<String> result = service.sendMessageToNewChat("Hello", currentUser, request, false);

            assertEquals("AI response", result.message());
            assertEquals("session-123", result.sessionId());
            verify(conversationThreadService).createNewThread(currentUser);
            verify(commandSyncService).syncAndHandle(any(), any());
        }

        @Test
        void whenActiveThreadExists_closesItThenCreatesNewAndSends() {
            ConversationThread activeThread = new ConversationThread();
            activeThread.setThreadKey("old-session");
            when(threadRepository.findMostRecentActiveThread(currentUser)).thenReturn(Optional.of(activeThread));
            when(conversationThreadService.createNewThread(currentUser)).thenReturn(thread);
            when(commandSyncService.syncAndHandle(any(), any())).thenReturn("OK");

            ChatResponseDto<String> result = service.sendMessageToNewChat("Hi", currentUser, request, false);

            assertEquals("OK", result.message());
            verify(conversationThreadService).closeThread(activeThread);
            verify(conversationThreadService).createNewThread(currentUser);
        }
    }

    @Nested
    @DisplayName("sendMessage")
    class SendMessage {

        @Test
        void whenSessionBelongsToUser_activatesThreadAndSends() {
            when(threadRepository.findByThreadKey("session-123")).thenReturn(Optional.of(thread));
            when(commandSyncService.syncAndHandle(any(), any())).thenReturn("Reply");

            ChatResponseDto<String> result = service.sendMessage("session-123", "Hi", currentUser, request, false);

            assertEquals("Reply", result.message());
            assertEquals("session-123", result.sessionId());
            verify(conversationThreadService).activateThread(currentUser, thread);
        }

        @Test
        void whenSessionBelongsToAnotherUser_throwsUnauthorizedException() {
            RestUser otherUser = new RestUser();
            otherUser.setId(999L);
            thread.setUser(otherUser);
            when(threadRepository.findByThreadKey("session-123")).thenReturn(Optional.of(thread));

            UnauthorizedException ex = assertThrows(UnauthorizedException.class,
                    () -> service.sendMessage("session-123", "Hi", currentUser, request, false));
            assertEquals("Session does not belong to user", ex.getMessage());
        }

        @Test
        void whenSessionNotFound_throwsUnauthorizedException() {
            when(threadRepository.findByThreadKey("missing")).thenReturn(Optional.empty());

            UnauthorizedException ex = assertThrows(UnauthorizedException.class,
                    () -> service.sendMessage("missing", "Hi", currentUser, request, false));
            assertEquals("Session not found: missing", ex.getMessage());
        }
    }

    @Nested
    @DisplayName("getSessions")
    class GetSessions {

        @Test
        void mapsThreadsToChatSessionDtos() {
            thread.setTitle(null);
            when(threadRepository.findByUserOrderByLastActivityAtDesc(currentUser)).thenReturn(List.of(thread));

            List<ChatSessionDto> result = service.getSessions(currentUser);

            assertEquals(1, result.size());
            assertEquals("session-123", result.get(0).sessionId());
            assertEquals("Untitled", result.get(0).name());
            assertEquals(thread.getCreatedAt(), result.get(0).createdAt());
        }

        @Test
        void whenThreadHasTitle_usesIt() {
            when(threadRepository.findByUserOrderByLastActivityAtDesc(currentUser)).thenReturn(List.of(thread));

            List<ChatSessionDto> result = service.getSessions(currentUser);

            assertEquals("Test", result.get(0).name());
        }
    }

    @Nested
    @DisplayName("getChatHistory")
    class GetChatHistory {

        @Test
        void whenSessionBelongsToUser_returnsMessagesExcludingSystem() {
            when(threadRepository.findByThreadKey("session-123")).thenReturn(Optional.of(thread));
            OpenDaimonMessage userMsg = new OpenDaimonMessage();
            userMsg.setRole(MessageRole.USER);
            userMsg.setContent("Hello");
            OpenDaimonMessage assistantMsg = new OpenDaimonMessage();
            assistantMsg.setRole(MessageRole.ASSISTANT);
            assistantMsg.setContent("Hi there");
            OpenDaimonMessage systemMsg = new OpenDaimonMessage();
            systemMsg.setRole(MessageRole.SYSTEM);
            systemMsg.setContent("System");
            when(messageRepository.findByThreadOrderBySequenceNumberAsc(thread))
                    .thenReturn(List.of(systemMsg, userMsg, assistantMsg));

            List<ChatMessageDto> result = service.getChatHistory("session-123", currentUser);

            assertEquals(2, result.size());
            assertEquals("USER", result.get(0).role());
            assertEquals("Hello", result.get(0).content());
            assertEquals("ASSISTANT", result.get(1).role());
            assertEquals("Hi there", result.get(1).content());
        }

        @Test
        void whenSessionBelongsToAnotherUser_throwsUnauthorizedException() {
            RestUser otherUser = new RestUser();
            otherUser.setId(999L);
            thread.setUser(otherUser);
            when(threadRepository.findByThreadKey("session-123")).thenReturn(Optional.of(thread));

            assertThrows(UnauthorizedException.class, () -> service.getChatHistory("session-123", currentUser));
        }

        @Test
        void whenSessionNotFound_throwsUnauthorizedException() {
            when(threadRepository.findByThreadKey("missing")).thenReturn(Optional.empty());

            UnauthorizedException ex = assertThrows(UnauthorizedException.class,
                    () -> service.getChatHistory("missing", currentUser));
            assertEquals("Session not found: missing", ex.getMessage());
        }
    }

    @Nested
    @DisplayName("deleteSession")
    class DeleteSession {

        @Test
        void whenSessionBelongsToUser_deletesMessagesAndThread() {
            when(threadRepository.findByThreadKey("session-123")).thenReturn(Optional.of(thread));
            when(messageRepository.findByThreadOrderBySequenceNumberAsc(thread)).thenReturn(List.of(new OpenDaimonMessage()));

            service.deleteSession("session-123", currentUser);

            verify(messageRepository).deleteAll(any(List.class));
            verify(threadRepository).delete(thread);
        }

        @Test
        void whenSessionBelongsToAnotherUser_throwsUnauthorizedException() {
            RestUser otherUser = new RestUser();
            otherUser.setId(999L);
            thread.setUser(otherUser);
            when(threadRepository.findByThreadKey("session-123")).thenReturn(Optional.of(thread));

            assertThrows(UnauthorizedException.class, () -> service.deleteSession("session-123", currentUser));
        }

        @Test
        void whenSessionNotFound_throwsUnauthorizedException() {
            when(threadRepository.findByThreadKey("missing")).thenReturn(Optional.empty());

            assertThrows(UnauthorizedException.class, () -> service.deleteSession("missing", currentUser));
        }
    }
}
