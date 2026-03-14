package io.github.ngirchev.opendaimon.common.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import io.github.ngirchev.opendaimon.common.config.CoreCommonProperties;
import io.github.ngirchev.opendaimon.common.model.*;
import io.github.ngirchev.opendaimon.common.repository.OpenDaimonMessageRepository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.github.ngirchev.opendaimon.common.exception.UserMessageTooLongException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OpenDaimonMessageServiceTest {

    @Mock
    private OpenDaimonMessageRepository messageRepository;

    @Mock
    private ConversationThreadService conversationThreadService;

    @Mock
    private AssistantRoleService assistantRoleService;

    @Mock
    private CoreCommonProperties coreCommonProperties;

    @Mock
    private CoreCommonProperties.ManualConversationHistoryProperties manualHistoryProperties;

    @Mock
    private User user;

    @Mock
    private AssistantRole assistantRole;

    @Mock
    private ConversationThread thread;

    @Mock
    private ObjectProvider<OpenDaimonMessageService> messageServiceSelfProvider;

    private TokenCounter tokenCounter;
    private OpenDaimonMessageService messageService;

    /** Max user message tokens for tests (must be greater than any test message). */
    private static final int TEST_MAX_USER_MESSAGE_TOKENS = 10000;

    @BeforeEach
    void setUp() {
        when(coreCommonProperties.getManualConversationHistory()).thenReturn(manualHistoryProperties);
        doReturn(TEST_MAX_USER_MESSAGE_TOKENS).when(coreCommonProperties).getMaxUserMessageTokens();
        when(manualHistoryProperties.getTokenEstimationCharsPerToken()).thenReturn(4);
        tokenCounter = new TokenCounter(coreCommonProperties);
        messageService = new OpenDaimonMessageService(
                messageRepository,
                conversationThreadService,
                assistantRoleService,
                coreCommonProperties,
                tokenCounter,
                messageServiceSelfProvider
        );
        when(messageServiceSelfProvider.getObject()).thenReturn(messageService);

        // Setup mocks for common cases
        when(assistantRole.getId()).thenReturn(1L);
        when(assistantRole.getContent()).thenReturn("You are a helpful assistant.");
        when(assistantRole.getVersion()).thenReturn(1);
        when(thread.getId()).thenReturn(1L);
        when(thread.getThreadKey()).thenReturn("test-thread-key");
        when(conversationThreadService.getOrCreateThread(any(User.class))).thenReturn(thread);
        when(messageRepository.findLastByThread(any(ConversationThread.class)))
                .thenReturn(Optional.empty());
        when(messageRepository.save(any(OpenDaimonMessage.class))).thenAnswer(invocation -> {
            OpenDaimonMessage msg = invocation.getArgument(0);
            if (msg.getId() == null) {
                msg.setId(1L);
            }
            return msg;
        });
    }

    @Test
    void whenSaveUserMessage_thenTokenCountIsSet() {
        // Arrange
        String content = "Hello, how are you?";
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("test", "value");

        // Act
        OpenDaimonMessage savedMessage = messageService.saveUserMessage(
                user,
                content,
                RequestType.TEXT,
                assistantRole,
                metadata
        );

        // Assert
        assertNotNull(savedMessage);
        assertNotNull(savedMessage.getTokenCount(), "tokenCount must be set for USER message");
        assertTrue(savedMessage.getTokenCount() > 0, 
                "tokenCount must be > 0 for non-empty message");
        // Verify tokenCount matches expected
        int expectedTokens = tokenCounter.estimateTokens(content);
        assertEquals(expectedTokens, savedMessage.getTokenCount(),
                "tokenCount must match TokenCounter estimate");
        
        verify(messageRepository).save(any(OpenDaimonMessage.class));
    }

    @Test
    void whenSaveUserMessageWithAttachmentRefs_thenAttachmentsAreStored() {
        String content = "Look at the photo";
        Map<String, Object> metadata = new HashMap<>();
        Map<String, Object> ref = new HashMap<>();
        ref.put("storageKey", "photo/uuid.jpg");
        ref.put("expiresAt", "2025-02-25T12:00:00Z");
        ref.put("mimeType", "image/jpeg");
        ref.put("filename", "photo_xxx.jpg");
        List<Map<String, Object>> attachmentRefs = List.of(ref);

        OpenDaimonMessage savedMessage = messageService.saveUserMessage(
                user,
                content,
                RequestType.TEXT,
                assistantRole,
                metadata,
                attachmentRefs
        );

        assertNotNull(savedMessage);
        assertNotNull(savedMessage.getAttachments());
        assertEquals(1, savedMessage.getAttachments().size());
        assertEquals("photo/uuid.jpg", savedMessage.getAttachments().get(0).get("storageKey"));
        assertEquals("image/jpeg", savedMessage.getAttachments().get(0).get("mimeType"));
        verify(messageRepository).save(any(OpenDaimonMessage.class));
    }

    @Test
    void whenSaveAssistantMessage_thenTokenCountIsSet() {
        // Arrange
        String content = "Test response from AI";

        // Act
        OpenDaimonMessage savedMessage = messageService.saveAssistantMessage(
                user,
                content,
                "testService",
                assistantRole,
                100,
                null
        );

        // Assert
        assertNotNull(savedMessage);
        assertNotNull(savedMessage.getTokenCount(), "tokenCount must be set for ASSISTANT message");
        assertTrue(savedMessage.getTokenCount() > 0, 
                "tokenCount must be > 0 for non-empty message");
        // Verify tokenCount matches expected
        int expectedTokens = tokenCounter.estimateTokens(content);
        assertEquals(expectedTokens, savedMessage.getTokenCount(),
                "tokenCount must match TokenCounter estimate");
        
        verify(messageRepository).save(any(OpenDaimonMessage.class));
    }

    @Test
    void whenSaveAssistantErrorMessage_thenTokenCountIsZero() {
        // Arrange
        String errorMessage = "An error occurred";

        // Act
        OpenDaimonMessage savedMessage = messageService.saveAssistantErrorMessage(
                user,
                errorMessage,
                "testService",
                assistantRole,
                "error data"
        );

        // Assert
        assertNotNull(savedMessage);
        assertNotNull(savedMessage.getTokenCount(), "tokenCount must be set for ASSISTANT error message");
        assertEquals(0, savedMessage.getTokenCount(), 
                "tokenCount must be 0 for error messages (empty content)");
        
        verify(messageRepository).save(any(OpenDaimonMessage.class));
    }

    @Test
    void whenSaveAssistantMessageWithRoleContent_thenResolvesRoleAndDelegates() {
        when(coreCommonProperties.getAssistantRole()).thenReturn("Default role");
        when(assistantRoleService.getOrCreateDefaultRole(user, "Default role")).thenReturn(assistantRole);
        String content = "Reply";

        OpenDaimonMessage saved = messageService.saveAssistantMessage(
                user, content, "svc", (String) null, 50, null);

        assertNotNull(saved);
        verify(messageRepository).save(any(OpenDaimonMessage.class));
        verify(assistantRoleService).getOrCreateDefaultRole(user, "Default role");
        verify(messageServiceSelfProvider).getObject();
    }

    @Test
    void whenSaveSystemMessage_thenTokenCountIsSet() {
        // Arrange
        String content = "You are a helpful assistant. Always be polite.";

        // Act
        OpenDaimonMessage savedMessage = messageService.saveSystemMessage(user, content);

        // Assert
        assertNotNull(savedMessage);
        assertNotNull(savedMessage.getTokenCount(), "tokenCount must be set for SYSTEM message");
        assertTrue(savedMessage.getTokenCount() > 0, 
                "tokenCount must be > 0 for non-empty message");
        // Verify tokenCount matches expected
        int expectedTokens = tokenCounter.estimateTokens(content);
        assertEquals(expectedTokens, savedMessage.getTokenCount(),
                "tokenCount must match TokenCounter estimate");
        
        verify(messageRepository).save(any(OpenDaimonMessage.class));
    }

    @Test
    void whenSaveUserMessageWithEmptyContent_thenTokenCountIsZero() {
        // Arrange
        String content = "";

        // Act
        OpenDaimonMessage savedMessage = messageService.saveUserMessage(
                user,
                content,
                RequestType.TEXT,
                assistantRole,
                null
        );

        // Assert
        assertNotNull(savedMessage);
        assertNotNull(savedMessage.getTokenCount(), "tokenCount must be set even for empty message");
        assertEquals(0, savedMessage.getTokenCount(), 
                "tokenCount must be 0 for empty message");
        
        verify(messageRepository).save(any(OpenDaimonMessage.class));
    }

    @Test
    void whenSaveUserMessageWithLongContent_thenTokenCountIsCalculatedCorrectly() {
        // Arrange
        // Create long message to verify token calculation
        StringBuilder longContent = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            longContent.append("Test ");
        }
        String content = longContent.toString();

        // Act
        OpenDaimonMessage savedMessage = messageService.saveUserMessage(
                user,
                content,
                RequestType.TEXT,
                assistantRole,
                null
        );

        // Assert
        assertNotNull(savedMessage);
        assertNotNull(savedMessage.getTokenCount());
        // Verify tokenCount matches expected
        int expectedTokens = tokenCounter.estimateTokens(content);
        assertEquals(expectedTokens, savedMessage.getTokenCount(),
                "tokenCount must match TokenCounter estimate for long message");
        assertTrue(savedMessage.getTokenCount() > 0, 
                "tokenCount must be > 0 for long message");
        
        verify(messageRepository).save(any(OpenDaimonMessage.class));
    }

    @Test
    void whenSaveUserMessageExceedsTokenLimit_thenThrowsUserMessageTooLongException() {
        doReturn(2).when(coreCommonProperties).getMaxUserMessageTokens();
        when(manualHistoryProperties.getTokenEstimationCharsPerToken()).thenReturn(1);
        TokenCounter strictCounter = new TokenCounter(coreCommonProperties);
        messageService = new OpenDaimonMessageService(
                messageRepository,
                conversationThreadService,
                assistantRoleService,
                coreCommonProperties,
                strictCounter,
                messageServiceSelfProvider
        );
        when(messageServiceSelfProvider.getObject()).thenReturn(messageService);

        String longContent = "This message has more than two tokens";

        UserMessageTooLongException ex = assertThrows(UserMessageTooLongException.class, () ->
                messageService.saveUserMessage(user, longContent, RequestType.TEXT, assistantRole, null));

        assertTrue(ex.getEstimatedTokens() > 2);
        assertEquals(2, ex.getMaxAllowed());
        verify(messageRepository, never()).save(any());
    }

    @Test
    void whenSaveUserMessageWithAssistantRoleContent_thenResolvesRoleAndSaves() {
        when(coreCommonProperties.getAssistantRole()).thenReturn("Default system prompt");
        when(assistantRoleService.getOrCreateDefaultRole(user, "Custom role")).thenReturn(assistantRole);

        OpenDaimonMessage saved = messageService.saveUserMessage(
                user, "Hello", RequestType.TEXT, "Custom role", null);

        assertNotNull(saved);
        verify(assistantRoleService).getOrCreateDefaultRole(user, "Custom role");
        verify(messageRepository).save(any(OpenDaimonMessage.class));
    }
}

