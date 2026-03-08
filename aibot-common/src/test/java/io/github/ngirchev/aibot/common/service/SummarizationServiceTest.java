package io.github.ngirchev.aibot.common.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.ngirchev.aibot.common.ai.AIGateways;
import io.github.ngirchev.aibot.common.ai.command.AICommand;
import io.github.ngirchev.aibot.common.ai.response.MapResponse;
import io.github.ngirchev.aibot.common.config.CoreCommonProperties;
import io.github.ngirchev.aibot.common.model.AIBotMessage;
import io.github.ngirchev.aibot.common.model.ConversationThread;
import io.github.ngirchev.aibot.common.model.MessageRole;
import io.github.ngirchev.aibot.common.model.User;
import io.github.ngirchev.aibot.common.repository.AIBotMessageRepository;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SummarizationServiceTest {

    @Mock
    private AIBotMessageRepository messageRepository;

    @Mock
    private ConversationThreadService threadService;

    @Mock
    private AIGatewayRegistry aiGatewayRegistry;

    @Mock
    private CoreCommonProperties coreCommonProperties;

    @Mock
    private CoreCommonProperties.SummarizationProperties summarization;

    @Mock
    private User user;

    private ObjectMapper objectMapper;
    private SummarizationService summarizationService;

    @BeforeEach
    void setUp() {
        when(coreCommonProperties.getSummarization()).thenReturn(summarization);
        objectMapper = new ObjectMapper(); // Use real ObjectMapper for JSON parsing
        summarizationService = new SummarizationService(
            messageRepository,
            threadService,
            aiGatewayRegistry,
            coreCommonProperties,
            objectMapper
        );
    }

    @Test
    void whenThreadTokensBelowThreshold_thenShouldNotTrigger() {
        // Arrange
        ConversationThread thread = createThread(1000L);
        when(summarization.getMaxContextTokens()).thenReturn(8000);
        when(summarization.getSummaryTriggerThreshold()).thenReturn(0.7);

        // Act
        boolean result = summarizationService.shouldTriggerSummarization(thread);

        // Assert
        assertFalse(result);
    }

    @Test
    void whenThreadTokensAtThreshold_thenShouldTrigger() {
        // Arrange
        ConversationThread thread = createThread(5600L); // 70% of 8000
        when(summarization.getMaxContextTokens()).thenReturn(8000);
        when(summarization.getSummaryTriggerThreshold()).thenReturn(0.7);

        // Act
        boolean result = summarizationService.shouldTriggerSummarization(thread);

        // Assert
        assertTrue(result);
    }

    @Test
    void whenThreadTokensAboveThreshold_thenShouldTrigger() {
        // Arrange
        ConversationThread thread = createThread(8000L); // 100% of 8000
        when(summarization.getMaxContextTokens()).thenReturn(8000);
        when(summarization.getSummaryTriggerThreshold()).thenReturn(0.7);

        // Act
        boolean result = summarizationService.shouldTriggerSummarization(thread);

        // Assert
        assertTrue(result);
    }

    @Test
    void whenThreadTokensIsNull_thenShouldNotTrigger() {
        // Arrange
        ConversationThread thread = createThread(null);

        // Act
        boolean result = summarizationService.shouldTriggerSummarization(thread);

        // Assert
        assertFalse(result);
    }

    @Test
    void whenThreadTokensIsZero_thenShouldNotTrigger() {
        // Arrange
        ConversationThread thread = createThread(0L);

        // Act
        boolean result = summarizationService.shouldTriggerSummarization(thread);

        // Assert
        assertFalse(result);
    }

    @Test
    void whenNoMessages_thenSummarizeThreadAsyncCompletesWithoutError() {
        // Arrange
        ConversationThread thread = createThread(1000L);
        when(messageRepository.findByThreadOrderBySequenceNumberAsc(thread))
            .thenReturn(new ArrayList<>());

        // Act & Assert
        assertDoesNotThrow(() -> {
            summarizationService.summarizeThreadAsync(thread).join();
        });

        // Verify
        verify(messageRepository).findByThreadOrderBySequenceNumberAsc(thread);
        verify(threadService, never()).updateThreadSummary(any(), any(), any());
    }

    @Test
    void whenNotEnoughMessages_thenSummarizeThreadAsyncCompletesWithoutError() {
        // Arrange
        ConversationThread thread = createThread(1000L);
        when(summarization.getKeepRecentMessages()).thenReturn(20);
        when(messageRepository.findByThreadOrderBySequenceNumberAsc(thread))
            .thenReturn(List.of(createUserMessage("Message 1"), createAssistantMessage("Response 1")));

        // Act & Assert
        assertDoesNotThrow(() -> {
            summarizationService.summarizeThreadAsync(thread).join();
        });

        // Verify
        verify(messageRepository).findByThreadOrderBySequenceNumberAsc(thread);
        verify(threadService, never()).updateThreadSummary(any(), any(), any());
    }

    @Test
    void whenEnoughMessages_thenSummarizeThreadAsyncProcessesMessages() {
        // Arrange
        ConversationThread thread = createThread(1000L);
        when(summarization.getKeepRecentMessages()).thenReturn(2);
        
        // Create 3 turns (6 messages): with defaultWindowSize=2 only first 2 turns (4 messages) are summarized
        AIBotMessage userMsg1 = createUserMessage("Message 1");
        AIBotMessage assistantMsg1 = createAssistantMessage("Response 1");
        AIBotMessage userMsg2 = createUserMessage("Message 2");
        AIBotMessage assistantMsg2 = createAssistantMessage("Response 2");
        AIBotMessage userMsg3 = createUserMessage("Message 3");
        AIBotMessage assistantMsg3 = createAssistantMessage("Response 3");

        when(messageRepository.findByThreadOrderBySequenceNumberAsc(thread))
            .thenReturn(List.of(userMsg1, assistantMsg1, userMsg2, assistantMsg2, userMsg3, assistantMsg3));

        AIGateway mockGateway = mock(AIGateway.class);
        when(aiGatewayRegistry.getSupportedAiGateways(any())).thenReturn(List.of(mockGateway));
        
        // Mock generateResponse returning OpenRouterResponse in format expected by retrieveMessage
        Map<String, Object> message = new HashMap<>();
        message.put("content", "{\"summary\": \"Test summary\", \"memory_bullets\": [\"Fact 1\", \"Fact 2\"]}");
        Map<String, Object> choice = new HashMap<>();
        choice.put("message", message);
        Map<String, Object> responseData = new HashMap<>();
        responseData.put("choices", List.of(choice));
        
        MapResponse mockResponse = new MapResponse(AIGateways.OPENROUTER, responseData);
        
        when(mockGateway.generateResponse(any(AICommand.class))).thenReturn(mockResponse);

        // Act & Assert
        assertDoesNotThrow(() -> {
            summarizationService.summarizeThreadAsync(thread).join();
        });

        // Verify
        verify(messageRepository).findByThreadOrderBySequenceNumberAsc(thread);
        verify(mockGateway).generateResponse(any(AICommand.class));
        verify(threadService).updateThreadSummary(eq(thread), anyString(), anyList());
    }

    @Test
    void whenModelReturnsNonJsonThenValidJson_thenRetrySucceeds() {
        ConversationThread thread = createThread(1000L);
        when(summarization.getKeepRecentMessages()).thenReturn(2);
        when(messageRepository.findByThreadOrderBySequenceNumberAsc(thread))
            .thenReturn(List.of(
                createUserMessage("Message 1"),
                createAssistantMessage("Response 1"),
                createUserMessage("Message 2"),
                createAssistantMessage("Response 2"),
                createUserMessage("Message 3"),
                createAssistantMessage("Response 3")));

        AIGateway mockGateway = mock(AIGateway.class);
        when(aiGatewayRegistry.getSupportedAiGateways(any())).thenReturn(List.of(mockGateway));
        String validJson = "{\"summary\": \"Test summary\", \"memory_bullets\": [\"Fact 1\", \"Fact 2\"]}";
        when(mockGateway.generateResponse(any(AICommand.class)))
            .thenReturn(responseWithContent("Dear, here is the summary..."))
            .thenReturn(responseWithContent(validJson));

        assertDoesNotThrow(() -> summarizationService.summarizeThreadAsync(thread).join());

        verify(mockGateway, times(2)).generateResponse(any(AICommand.class));
        verify(threadService).updateThreadSummary(eq(thread), eq("Test summary"), anyList());
    }

    @Test
    void whenModelAlwaysReturnsNonJson_thenNoUpdateAfterRetries() {
        ConversationThread thread = createThread(1000L);
        when(summarization.getKeepRecentMessages()).thenReturn(2);
        when(messageRepository.findByThreadOrderBySequenceNumberAsc(thread))
            .thenReturn(List.of(
                createUserMessage("Message 1"),
                createAssistantMessage("Response 1"),
                createUserMessage("Message 2"),
                createAssistantMessage("Response 2"),
                createUserMessage("Message 3"),
                createAssistantMessage("Response 3")));

        AIGateway mockGateway = mock(AIGateway.class);
        when(aiGatewayRegistry.getSupportedAiGateways(any())).thenReturn(List.of(mockGateway));
        when(mockGateway.generateResponse(any(AICommand.class)))
            .thenReturn(responseWithContent("Dear, here is a brief summary..."));

        summarizationService.summarizeThreadAsync(thread).join();

        // After 3 failed parse attempts summarization does not save result
        verify(mockGateway, times(3)).generateResponse(any(AICommand.class));
        verify(threadService, never()).updateThreadSummary(any(), any(), any());
    }

    private static MapResponse responseWithContent(String content) {
        Map<String, Object> message = new HashMap<>();
        message.put("content", content);
        Map<String, Object> choice = new HashMap<>();
        choice.put("message", message);
        Map<String, Object> responseData = new HashMap<>();
        responseData.put("choices", List.of(choice));
        return new MapResponse(AIGateways.OPENROUTER, responseData);
    }

    // Helper methods for creating test objects
    private ConversationThread createThread(Long totalTokens) {
        ConversationThread thread = new ConversationThread();
        thread.setId(1L);
        thread.setThreadKey("test-thread-key");
        thread.setTotalTokens(totalTokens);
        thread.setSummary(null);
        thread.setMemoryBullets(new ArrayList<>());
        return thread;
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
}

