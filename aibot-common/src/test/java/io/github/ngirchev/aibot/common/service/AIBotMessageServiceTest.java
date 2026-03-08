package io.github.ngirchev.aibot.common.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import io.github.ngirchev.aibot.common.config.CoreCommonProperties;
import io.github.ngirchev.aibot.common.model.*;
import io.github.ngirchev.aibot.common.repository.AIBotMessageRepository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AIBotMessageServiceTest {

    @Mock
    private AIBotMessageRepository messageRepository;

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

    private TokenCounter tokenCounter;
    private AIBotMessageService messageService;

    /** Max user message tokens for tests (must be greater than any test message). */
    private static final int TEST_MAX_USER_MESSAGE_TOKENS = 10000;

    @BeforeEach
    void setUp() {
        when(coreCommonProperties.getManualConversationHistory()).thenReturn(manualHistoryProperties);
        doReturn(TEST_MAX_USER_MESSAGE_TOKENS).when(coreCommonProperties).getMaxUserMessageTokens();
        when(manualHistoryProperties.getTokenEstimationCharsPerToken()).thenReturn(4);
        tokenCounter = new TokenCounter(coreCommonProperties);
        messageService = new AIBotMessageService(
                messageRepository,
                conversationThreadService,
                assistantRoleService,
                coreCommonProperties,
                tokenCounter
        );

        // Setup mocks for common cases
        when(assistantRole.getId()).thenReturn(1L);
        when(assistantRole.getContent()).thenReturn("You are a helpful assistant.");
        when(assistantRole.getVersion()).thenReturn(1);
        when(thread.getId()).thenReturn(1L);
        when(thread.getThreadKey()).thenReturn("test-thread-key");
        when(conversationThreadService.getOrCreateThread(any(User.class))).thenReturn(thread);
        when(messageRepository.findLastByThread(any(ConversationThread.class)))
                .thenReturn(Optional.empty());
        when(messageRepository.save(any(AIBotMessage.class))).thenAnswer(invocation -> {
            AIBotMessage msg = invocation.getArgument(0);
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
        AIBotMessage savedMessage = messageService.saveUserMessage(
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
        
        verify(messageRepository).save(any(AIBotMessage.class));
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

        AIBotMessage savedMessage = messageService.saveUserMessage(
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
        verify(messageRepository).save(any(AIBotMessage.class));
    }

    @Test
    void whenSaveAssistantMessage_thenTokenCountIsSet() {
        // Arrange
        String content = "Test response from AI";

        // Act
        AIBotMessage savedMessage = messageService.saveAssistantMessage(
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
        
        verify(messageRepository).save(any(AIBotMessage.class));
    }

    @Test
    void whenSaveAssistantErrorMessage_thenTokenCountIsZero() {
        // Arrange
        String errorMessage = "An error occurred";

        // Act
        AIBotMessage savedMessage = messageService.saveAssistantErrorMessage(
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
        
        verify(messageRepository).save(any(AIBotMessage.class));
    }

    @Test
    void whenSaveSystemMessage_thenTokenCountIsSet() {
        // Arrange
        String content = "You are a helpful assistant. Always be polite.";

        // Act
        AIBotMessage savedMessage = messageService.saveSystemMessage(user, content);

        // Assert
        assertNotNull(savedMessage);
        assertNotNull(savedMessage.getTokenCount(), "tokenCount must be set for SYSTEM message");
        assertTrue(savedMessage.getTokenCount() > 0, 
                "tokenCount must be > 0 for non-empty message");
        // Verify tokenCount matches expected
        int expectedTokens = tokenCounter.estimateTokens(content);
        assertEquals(expectedTokens, savedMessage.getTokenCount(),
                "tokenCount must match TokenCounter estimate");
        
        verify(messageRepository).save(any(AIBotMessage.class));
    }

    @Test
    void whenSaveUserMessageWithEmptyContent_thenTokenCountIsZero() {
        // Arrange
        String content = "";

        // Act
        AIBotMessage savedMessage = messageService.saveUserMessage(
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
        
        verify(messageRepository).save(any(AIBotMessage.class));
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
        AIBotMessage savedMessage = messageService.saveUserMessage(
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
        
        verify(messageRepository).save(any(AIBotMessage.class));
    }
}

