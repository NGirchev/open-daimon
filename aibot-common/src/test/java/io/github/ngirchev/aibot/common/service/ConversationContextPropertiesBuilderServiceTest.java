package io.github.ngirchev.aibot.common.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import io.github.ngirchev.aibot.common.config.CoreCommonProperties;
import io.github.ngirchev.aibot.common.storage.service.FileStorageService;

import java.time.OffsetDateTime;
import io.github.ngirchev.aibot.common.model.AssistantRole;
import io.github.ngirchev.aibot.common.model.ConversationThread;
import io.github.ngirchev.aibot.common.model.AIBotMessage;
import io.github.ngirchev.aibot.common.model.MessageRole;
import io.github.ngirchev.aibot.common.model.User;
import io.github.ngirchev.aibot.common.repository.AIBotMessageRepository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ConversationContextPropertiesBuilderServiceTest {

    @Mock
    private AIBotMessageRepository messageRepository;

    @Mock
    private TokenCounter tokenCounter;

    @Mock
    private CoreCommonProperties coreCommonProperties;

    @Mock
    private CoreCommonProperties.ManualConversationHistoryProperties historyConfig;

    @Mock
    private User user;

    @Mock
    private ObjectProvider<FileStorageService> fileStorageServiceProvider;

    private ConversationContextBuilderService conversationContextBuilderService;

    @BeforeEach
    void setUp() {
        when(coreCommonProperties.getManualConversationHistory()).thenReturn(historyConfig);
        when(fileStorageServiceProvider.getIfAvailable()).thenReturn(null);
        conversationContextBuilderService = new ConversationContextBuilderService(
            messageRepository,
            tokenCounter,
            coreCommonProperties,
            fileStorageServiceProvider
        );
    }

    @Test
    void whenBuildContextWithSystemPrompt_thenSystemPromptIsIncluded() {
        // Arrange
        ConversationThread thread = createEmptyThread();
        AssistantRole assistantRole = createAssistantRole("You are a helpful assistant.");
        String currentMessage = "Hello";

        when(historyConfig.getIncludeSystemPrompt()).thenReturn(true);
        when(historyConfig.getMaxResponseTokens()).thenReturn(4000);
        when(coreCommonProperties.getMaxTotalPromptTokens()).thenReturn(32000);
        when(messageRepository.findByThreadOrderBySequenceNumberAsc(any())).thenReturn(new ArrayList<>());
        when(tokenCounter.estimateTokens("You are a helpful assistant.")).thenReturn(10);
        when(tokenCounter.estimateTokens(currentMessage)).thenReturn(2);

        // Act
        List<Map<String, Object>> result = conversationContextBuilderService.buildContext(
            thread, currentMessage, assistantRole);

        // Assert: current user message is not in context — gateway adds it with attachments
        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals("system", result.get(0).get("role"));
        assertEquals("You are a helpful assistant.", result.get(0).get("content"));
    }

    @Test
    void whenBuildContextWithoutSystemPrompt_thenSystemPromptIsNotIncluded() {
        // Arrange
        ConversationThread thread = createEmptyThread();
        AssistantRole assistantRole = createAssistantRole("You are a helpful assistant.");
        String currentMessage = "Hello";

        when(historyConfig.getIncludeSystemPrompt()).thenReturn(false);
        when(historyConfig.getMaxResponseTokens()).thenReturn(4000);
        when(coreCommonProperties.getMaxTotalPromptTokens()).thenReturn(32000);
        when(messageRepository.findByThreadOrderBySequenceNumberAsc(any())).thenReturn(new ArrayList<>());
        when(tokenCounter.estimateTokens(currentMessage)).thenReturn(2);

        // Act
        List<Map<String, Object>> result = conversationContextBuilderService.buildContext(
            thread, currentMessage, assistantRole);

        // Assert: no history and no current message added, context is empty
        assertNotNull(result);
        assertEquals(0, result.size());
    }

    @Test
    void whenBuildContextWithSummary_thenSummaryIsIncluded() {
        // Arrange
        ConversationThread thread = createEmptyThread();
        thread.setSummary("Previous conversation summary");
        thread.setMemoryBullets(List.of("Fact 1", "Fact 2"));
        AssistantRole assistantRole = createAssistantRole("You are a helpful assistant.");
        String currentMessage = "Hello";

        when(historyConfig.getIncludeSystemPrompt()).thenReturn(true);
        when(historyConfig.getMaxResponseTokens()).thenReturn(4000);
        when(coreCommonProperties.getMaxTotalPromptTokens()).thenReturn(32000);
        when(messageRepository.findByThreadOrderBySequenceNumberAsc(any())).thenReturn(new ArrayList<>());
        when(tokenCounter.estimateTokens("You are a helpful assistant.")).thenReturn(10);
        when(tokenCounter.estimateTokens(anyString())).thenReturn(20); // for summary
        when(tokenCounter.estimateTokens(currentMessage)).thenReturn(2);

        // Act
        List<Map<String, Object>> result = conversationContextBuilderService.buildContext(
            thread, currentMessage, assistantRole);

        // Assert
        assertNotNull(result);
        assertTrue(result.size() >= 2);
        // Verify summary message is present
        boolean hasSummary = result.stream()
            .anyMatch(m -> "system".equals(m.get("role")) &&
                         m.get("content") instanceof String s && s.contains("Summary of previous conversation"));
        assertTrue(hasSummary);
    }

    @Test
    void whenBuildContextWithHistory_thenHistoryIsIncluded() {
        // Arrange
        ConversationThread thread = createEmptyThread();
        AssistantRole assistantRole = createAssistantRole("You are a helpful assistant.");
        String currentMessage = "Third message";

        AIBotMessage userMsg1 = createUserMessage("First message");
        AIBotMessage assistantMsg1 = createAssistantMessage("First response");
        AIBotMessage userMsg2 = createUserMessage("Second message");
        AIBotMessage assistantMsg2 = createAssistantMessage("Second response");

        when(historyConfig.getIncludeSystemPrompt()).thenReturn(true);
        when(historyConfig.getMaxResponseTokens()).thenReturn(4000);
        when(coreCommonProperties.getMaxTotalPromptTokens()).thenReturn(32000);
        when(messageRepository.findByThreadOrderBySequenceNumberAsc(thread))
            .thenReturn(List.of(userMsg1, assistantMsg1, userMsg2, assistantMsg2));
        when(tokenCounter.estimateTokens("You are a helpful assistant.")).thenReturn(10);
        when(tokenCounter.estimateTokens("First message")).thenReturn(3);
        when(tokenCounter.estimateTokens("First response")).thenReturn(5);
        when(tokenCounter.estimateTokens("Second message")).thenReturn(3);
        when(tokenCounter.estimateTokens("Second response")).thenReturn(5);
        when(tokenCounter.estimateTokens(currentMessage)).thenReturn(3);

        // Act
        List<Map<String, Object>> result = conversationContextBuilderService.buildContext(
            thread, currentMessage, assistantRole);

        // Assert: system + history (gateway adds current message)
        assertNotNull(result);
        assertEquals(5, result.size());
        assertEquals("system", result.get(0).get("role"));
        assertEquals("user", result.get(1).get("role"));
        assertEquals("First message", result.get(1).get("content"));
        assertEquals("assistant", result.get(2).get("role"));
        assertEquals("First response", result.get(2).get("content"));
        assertEquals("user", result.get(3).get("role"));
        assertEquals("Second message", result.get(3).get("content"));
        assertEquals("assistant", result.get(4).get("role"));
        assertEquals("Second response", result.get(4).get("content"));
    }

    @Test
    void whenTokenBudgetExceeded_thenHistoryIsTruncated() {
        // Arrange
        ConversationThread thread = createEmptyThread();
        AssistantRole assistantRole = createAssistantRole("You are a helpful assistant.");
        String currentMessage = "Current message";

        AIBotMessage userMsg1 = createUserMessage("First message");
        AIBotMessage userMsg2 = createUserMessage("Second message");

        when(historyConfig.getIncludeSystemPrompt()).thenReturn(true);
        when(historyConfig.getMaxResponseTokens()).thenReturn(50);
        when(coreCommonProperties.getMaxTotalPromptTokens()).thenReturn(120); // Prompt budget = 120 - 50 = 70, after system (20) history does not fit
        when(messageRepository.findByThreadOrderBySequenceNumberAsc(thread))
            .thenReturn(List.of(userMsg1, userMsg2));
        when(tokenCounter.estimateTokens("You are a helpful assistant.")).thenReturn(20);
        when(tokenCounter.estimateTokens("First message")).thenReturn(50); // Exceeds limit
        when(tokenCounter.estimateTokens("Second message")).thenReturn(50);
        when(tokenCounter.estimateTokens(currentMessage)).thenReturn(10);

        // Act
        List<Map<String, Object>> result = conversationContextBuilderService.buildContext(
            thread, currentMessage, assistantRole);

        // Assert: only system, history did not fit, gateway adds current message
        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals("system", result.get(0).get("role"));
    }

    @Test
    void whenThreadHasNoSummary_thenSummaryIsNotIncluded() {
        // Arrange
        ConversationThread thread = createEmptyThread();
        thread.setSummary(null);
        AssistantRole assistantRole = createAssistantRole("You are a helpful assistant.");
        String currentMessage = "Hello";

        when(historyConfig.getIncludeSystemPrompt()).thenReturn(true);
        when(historyConfig.getMaxResponseTokens()).thenReturn(4000);
        when(coreCommonProperties.getMaxTotalPromptTokens()).thenReturn(32000);
        when(messageRepository.findByThreadOrderBySequenceNumberAsc(any())).thenReturn(new ArrayList<>());
        when(tokenCounter.estimateTokens("You are a helpful assistant.")).thenReturn(10);
        when(tokenCounter.estimateTokens(currentMessage)).thenReturn(2);

        // Act
        List<Map<String, Object>> result = conversationContextBuilderService.buildContext(
            thread, currentMessage, assistantRole);

        // Assert: only system (gateway adds current message)
        assertNotNull(result);
        assertEquals(1, result.size());
        boolean hasSummary = result.stream()
            .anyMatch(m -> m.get("content") instanceof String s && s.contains("Summary of previous conversation"));
        assertFalse(hasSummary);
    }

    @Test
    void whenServiceResponseHasNullResponseText_thenResponseIsSkipped() {
        // Arrange
        ConversationThread thread = createEmptyThread();
        AssistantRole assistantRole = createAssistantRole("You are a helpful assistant.");
        String currentMessage = "Current message";

        AIBotMessage userMsg = createUserMessage("First message");
        AIBotMessage assistantMsgWithNull = createAssistantMessage(null); // content = null

        when(historyConfig.getIncludeSystemPrompt()).thenReturn(true);
        when(historyConfig.getMaxResponseTokens()).thenReturn(4000);
        when(coreCommonProperties.getMaxTotalPromptTokens()).thenReturn(32000);
        when(messageRepository.findByThreadOrderBySequenceNumberAsc(thread))
            .thenReturn(List.of(userMsg, assistantMsgWithNull));
        when(tokenCounter.estimateTokens("You are a helpful assistant.")).thenReturn(10);
        when(tokenCounter.estimateTokens("First message")).thenReturn(3);
        when(tokenCounter.estimateTokens(currentMessage)).thenReturn(3);

        // Act — no NPE; null response should be skipped
        List<Map<String, Object>> result = conversationContextBuilderService.buildContext(
            thread, currentMessage, assistantRole);

        // Assert: system + user from history (gateway adds current; null response skipped)
        assertNotNull(result);
        assertEquals(2, result.size());
        assertEquals("system", result.get(0).get("role"));
        assertEquals("user", result.get(1).get("role"));
        assertEquals("First message", result.get(1).get("content"));
        // Verify no assistant message with null
        boolean hasNullAssistantMessage = result.stream()
            .anyMatch(m -> "assistant".equals(m.get("role")) &&
                         (m.get("content") == null || (m.get("content") instanceof String s && s.isEmpty())));
        assertFalse(hasNullAssistantMessage, "Should not have assistant message with null or empty content");
    }

    // Helper methods for creating test objects
    private ConversationThread createEmptyThread() {
        ConversationThread thread = new ConversationThread();
        thread.setId(1L);
        thread.setThreadKey("test-thread-key");
        thread.setSummary(null);
        thread.setMemoryBullets(new ArrayList<>());
        return thread;
    }

    private AssistantRole createAssistantRole(String content) {
        AssistantRole role = new AssistantRole();
        role.setId(1L);
        role.setContent(content);
        return role;
    }

    private AIBotMessage createUserMessage(String text) {
        AIBotMessage message = new AIBotMessage();
        message.setId(1L);
        message.setRole(MessageRole.USER);
        message.setContent(text);
        message.setUser(user);
        return message;
    }

    private AIBotMessage createAssistantMessage(String text) {
        AIBotMessage message = new AIBotMessage();
        message.setId(2L);
        message.setRole(MessageRole.ASSISTANT);
        message.setContent(text);
        message.setUser(user);
        return message;
    }

    @Test
    void whenBuildContextWithUserMessageWithAttachments_thenContentPartsIncludeImageFromStorage() throws Exception {
        ConversationThread thread = createEmptyThread();
        AssistantRole assistantRole = createAssistantRole("You are a helpful assistant.");
        String currentMessage = "Next";

        AIBotMessage userMsgWithAttachment = createUserMessage("Look at the photo");
        Map<String, Object> ref = new HashMap<>();
        ref.put("storageKey", "photo/test-key.jpg");
        ref.put("expiresAt", OffsetDateTime.now().plusHours(1).toString());
        ref.put("mimeType", "image/jpeg");
        ref.put("filename", "photo.jpg");
        userMsgWithAttachment.setAttachments(List.of(ref));

        when(historyConfig.getIncludeSystemPrompt()).thenReturn(true);
        when(historyConfig.getMaxResponseTokens()).thenReturn(4000);
        when(coreCommonProperties.getMaxTotalPromptTokens()).thenReturn(32000);
        when(messageRepository.findByThreadOrderBySequenceNumberAsc(any())).thenReturn(List.of(userMsgWithAttachment));
        when(tokenCounter.estimateTokens(anyString())).thenReturn(5);

        FileStorageService fileStorage = mock(FileStorageService.class);
        when(fileStorage.get("photo/test-key.jpg")).thenReturn(new byte[]{1, 2, 3});
        when(fileStorageServiceProvider.getIfAvailable()).thenReturn(fileStorage);

        List<Map<String, Object>> result = conversationContextBuilderService.buildContext(
                thread, currentMessage, assistantRole);

        List<Map<String, Object>> userMessages = result.stream()
                .filter(m -> "user".equals(m.get("role")))
                .toList();
        assertEquals(1, userMessages.size()); // only history (gateway adds current)
        Object historyContent = userMessages.get(0).get("content");
        assertInstanceOf(List.class, historyContent);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> parts = (List<Map<String, Object>>) historyContent;
        assertEquals(2, parts.size());
        assertTrue(parts.stream().anyMatch(p -> "text".equals(p.get("type")) && "Look at the photo".equals(p.get("text"))));
        assertTrue(parts.stream().anyMatch(p -> "image_url".equals(p.get("type")) && p.get("image_url") != null));
    }

    @Test
    void whenBuildContextWithExpiredAttachment_thenContentIsPlainText() {
        ConversationThread thread = createEmptyThread();
        AssistantRole assistantRole = createAssistantRole("You are a helpful assistant.");
        String currentMessage = "Next";

        AIBotMessage userMsgWithAttachment = createUserMessage("Old photo");
        Map<String, Object> ref = new HashMap<>();
        ref.put("storageKey", "photo/old.jpg");
        ref.put("expiresAt", OffsetDateTime.now().minusHours(1).toString());
        ref.put("mimeType", "image/jpeg");
        ref.put("filename", "old.jpg");
        userMsgWithAttachment.setAttachments(List.of(ref));

        when(historyConfig.getIncludeSystemPrompt()).thenReturn(true);
        when(historyConfig.getMaxResponseTokens()).thenReturn(4000);
        when(coreCommonProperties.getMaxTotalPromptTokens()).thenReturn(32000);
        when(messageRepository.findByThreadOrderBySequenceNumberAsc(any())).thenReturn(List.of(userMsgWithAttachment));
        when(tokenCounter.estimateTokens(anyString())).thenReturn(5);
        when(fileStorageServiceProvider.getIfAvailable()).thenReturn(mock(FileStorageService.class));

        List<Map<String, Object>> result = conversationContextBuilderService.buildContext(
                thread, currentMessage, assistantRole);

        List<Map<String, Object>> userMessages = result.stream()
                .filter(m -> "user".equals(m.get("role")))
                .toList();
        Object historyContent = userMessages.get(0).get("content");
        assertEquals("Old photo", historyContent);
    }
}

