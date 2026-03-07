package ru.girchev.aibot.common.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import ru.girchev.aibot.common.config.CoreCommonProperties;
import ru.girchev.aibot.common.model.*;
import ru.girchev.aibot.common.repository.AIBotMessageRepository;

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

    /** Лимит токенов пользовательского сообщения для тестов (должен быть больше любого тестового сообщения). */
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

        // Настройка моков для общих случаев
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
        String content = "Привет, как дела?";
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
        assertNotNull(savedMessage.getTokenCount(), "tokenCount должен быть заполнен для USER сообщения");
        assertTrue(savedMessage.getTokenCount() > 0, 
                "tokenCount должен быть больше 0 для непустого сообщения");
        // Проверяем, что tokenCount соответствует ожидаемому значению
        // "Привет, как дела?" = 17 символов, при 4 символах на токен = 5 токенов (округление вверх)
        int expectedTokens = tokenCounter.estimateTokens(content);
        assertEquals(expectedTokens, savedMessage.getTokenCount(),
                "tokenCount должен соответствовать оценке TokenCounter");
        
        verify(messageRepository).save(any(AIBotMessage.class));
    }

    @Test
    void whenSaveUserMessageWithAttachmentRefs_thenAttachmentsAreStored() {
        String content = "Смотри фото";
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
        String content = "Тестовый ответ от AI";

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
        assertNotNull(savedMessage.getTokenCount(), "tokenCount должен быть заполнен для ASSISTANT сообщения");
        assertTrue(savedMessage.getTokenCount() > 0, 
                "tokenCount должен быть больше 0 для непустого сообщения");
        // Проверяем, что tokenCount соответствует ожидаемому значению
        int expectedTokens = tokenCounter.estimateTokens(content);
        assertEquals(expectedTokens, savedMessage.getTokenCount(),
                "tokenCount должен соответствовать оценке TokenCounter");
        
        verify(messageRepository).save(any(AIBotMessage.class));
    }

    @Test
    void whenSaveAssistantErrorMessage_thenTokenCountIsZero() {
        // Arrange
        String errorMessage = "Произошла ошибка";

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
        assertNotNull(savedMessage.getTokenCount(), "tokenCount должен быть заполнен для ASSISTANT сообщения с ошибкой");
        assertEquals(0, savedMessage.getTokenCount(), 
                "tokenCount должен быть 0 для сообщений с ошибкой (пустое содержимое)");
        
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
        assertNotNull(savedMessage.getTokenCount(), "tokenCount должен быть заполнен для SYSTEM сообщения");
        assertTrue(savedMessage.getTokenCount() > 0, 
                "tokenCount должен быть больше 0 для непустого сообщения");
        // Проверяем, что tokenCount соответствует ожидаемому значению
        int expectedTokens = tokenCounter.estimateTokens(content);
        assertEquals(expectedTokens, savedMessage.getTokenCount(),
                "tokenCount должен соответствовать оценке TokenCounter");
        
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
        assertNotNull(savedMessage.getTokenCount(), "tokenCount должен быть заполнен даже для пустого сообщения");
        assertEquals(0, savedMessage.getTokenCount(), 
                "tokenCount должен быть 0 для пустого сообщения");
        
        verify(messageRepository).save(any(AIBotMessage.class));
    }

    @Test
    void whenSaveUserMessageWithLongContent_thenTokenCountIsCalculatedCorrectly() {
        // Arrange
        // Создаем длинное сообщение для проверки расчета токенов
        StringBuilder longContent = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            longContent.append("Тест ");
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
        // Проверяем, что tokenCount соответствует ожидаемому значению
        int expectedTokens = tokenCounter.estimateTokens(content);
        assertEquals(expectedTokens, savedMessage.getTokenCount(),
                "tokenCount должен соответствовать оценке TokenCounter для длинного сообщения");
        assertTrue(savedMessage.getTokenCount() > 0, 
                "tokenCount должен быть больше 0 для длинного сообщения");
        
        verify(messageRepository).save(any(AIBotMessage.class));
    }
}

