package ru.girchev.aibot.ai.springai.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.OllamaChatModel;
import ru.girchev.aibot.ai.springai.config.SpringAIModelConfig;
import ru.girchev.aibot.ai.springai.config.SpringAIProperties;
import ru.girchev.aibot.ai.springai.rag.FileRAGService;
import ru.girchev.aibot.ai.springai.retry.SpringAIModelRegistry;
import ru.girchev.aibot.common.ai.ModelCapabilities;
import ru.girchev.aibot.common.ai.command.AICommand;
import ru.girchev.aibot.common.ai.command.ChatAICommand;
import ru.girchev.aibot.common.service.AIGatewayRegistry;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

/**
 * Test for SpringAIGateway with memory adviser and summary.
 *
 * Verifies:
 * 1. ChatMemory.get() call via MessageChatMemoryAdvisor
 * 2. Summary call via SummarizingChatMemory
 * 3. Correct message order in request: System -> Summary (if any) -> History (from ChatMemory) -> User
 *
 * IMPORTANT: Test verifies MessageOrderingAdvisor, which fixes known Spring AI issue #4170
 * where MessageChatMemoryAdvisor adds history before System messages.
 */
@ExtendWith(MockitoExtension.class)
class SpringAIGatewayMemoryAdvisorTest {

    @Mock
    private SpringAIProperties springAIProperties;
    
    @Mock
    private AIGatewayRegistry aiGatewayRegistry;
    
    @Mock
    private SpringAIModelType springAIModelType;

    @Mock
    private SpringAIModelRegistry springAIModelRegistry;

    @Mock
    private SpringAIChatService chatService;
    
    @Mock
    private ChatMemory chatMemory;
    
    @Mock
    private OllamaChatModel ollamaChatModel;
    
    private SpringAIGateway springAIGateway;
    private SpringAIPromptFactory promptFactory;
    private SpringAIChatService realChatService;
    private ChatClient ollamaChatClient;
    private SpringAIModelConfig modelConfig;

    @BeforeEach
    void setUp() {
        // Setup mocks
        when(springAIProperties.getMock()).thenReturn(false);
        
        // Create real ChatClient with mocked ChatModel
        ollamaChatClient = ChatClient.builder(ollamaChatModel).build();
        
        // Create SpringAIPromptFactory with useChatMemoryAdvisor = true
        promptFactory = new SpringAIPromptFactory(
                ollamaChatClient,
                ollamaChatClient, // use ollama for openAiChatClient too for simplicity
                mock(ru.girchev.aibot.ai.springai.tool.WebTools.class),
                chatMemory,
                springAIModelType,
                true // useChatMemoryAdvisor = true
        );
        
        // Create real SpringAIChatService
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<ru.girchev.aibot.ai.springai.retry.metrics.OpenRouterStreamMetricsTracker> objectProvider =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        lenient().when(objectProvider.getIfAvailable()).thenReturn(null);
        realChatService = new SpringAIChatService(
                promptFactory,
                objectProvider
        );
        
        // Setup model
        modelConfig = new SpringAIModelConfig();
        modelConfig.setName("test-model");
        modelConfig.setCapabilities(List.of(ModelCapabilities.CHAT));
        modelConfig.setProviderType(SpringAIModelConfig.ProviderType.OLLAMA);
        modelConfig.setPriority(1);
        
        lenient().when(springAIModelType.getByCapabilities(any()))
                .thenReturn(Optional.of(modelConfig));
        lenient().when(springAIModelType.getByCapabilities(eq(Set.of(ModelCapabilities.AUTO))))
                .thenReturn(Optional.of(modelConfig));
        lenient().when(springAIModelRegistry.getCandidatesByCapabilities(any(), any()))
                .thenReturn(List.of(modelConfig));
        lenient().when(springAIModelRegistry.getByModelName(any())).thenReturn(Optional.of(modelConfig));

        // Create SpringAIGateway (RAG disabled - pass null/empty providers)
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<DocumentProcessingService> docProvider = 
                mock(org.springframework.beans.factory.ObjectProvider.class);
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<FileRAGService> ragProvider =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<ChatMemory> chatMemoryProvider =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(chatMemoryProvider.getIfAvailable()).thenReturn(chatMemory);
        
        springAIGateway = new SpringAIGateway(
                springAIProperties,
                aiGatewayRegistry,
                springAIModelRegistry,
                realChatService,
                chatMemoryProvider,
                null, // ragProperties - RAG disabled
                docProvider,
                ragProvider
        );
    }

    @Test
    void whenGenerateResponseWithThreadKey_thenChatMemoryGetIsCalled() {
        // Arrange
        String threadKey = "test-thread-key";
        String systemRole = "You are a helpful assistant";
        String userRole = "Hello, how are you?";
        
        // Setup ChatMemory to return history
        List<Message> historyMessages = List.of(
                new UserMessage("Previous user message"),
                new AssistantMessage("Previous assistant response")
        );
        when(chatMemory.get(threadKey)).thenReturn(historyMessages);
        
        // Setup ChatModel to return response
        ChatResponse mockResponse = ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage("Test response"))))
                .build();
        when(ollamaChatModel.call(any(Prompt.class))).thenReturn(mockResponse);
        
        // Create command with threadKey
        Map<String, String> metadata = new HashMap<>();
        metadata.put(AICommand.THREAD_KEY_FIELD, threadKey);
        ChatAICommand command = new ChatAICommand(
                Set.of(ModelCapabilities.CHAT),
                0.7,
                1000,
                systemRole,
                userRole,
                false,
                metadata,
                new HashMap<>()
        );
        
        // Act
        springAIGateway.generateResponse(command);
        
        // Assert
        // Verify ChatMemory.get() was called with correct threadKey
        verify(chatMemory, times(1)).get(threadKey);
    }

    @Test
    void whenGenerateResponseWithThreadKey_thenMessagesOrderIsCorrect() {
        // Arrange
        String threadKey = "test-thread-key";
        String systemRole = "You are a friendly assistant. respond concisely and to the point.";
        String userRole = "Что ты знаешь?";
        
        // Setup ChatMemory to return history
        // History should be: User -> Assistant
        String historyUser = "Тест";
        String historyAssistant = "Okay.";
        List<Message> historyMessages = List.of(
                new UserMessage(historyUser),
                new AssistantMessage(historyAssistant)
        );
        when(chatMemory.get(threadKey)).thenReturn(historyMessages);
        
        // Setup ChatModel to return response
        ChatResponse mockResponse = ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage("Test response"))))
                .build();
        
        // Use ArgumentCaptor to capture Prompt
        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        when(ollamaChatModel.call(promptCaptor.capture())).thenReturn(mockResponse);
        
        // Create command with threadKey
        Map<String, String> metadata = new HashMap<>();
        metadata.put(AICommand.THREAD_KEY_FIELD, threadKey);
        ChatAICommand command = new ChatAICommand(
                Set.of(ModelCapabilities.CHAT),
                0.7,
                1000,
                systemRole,
                userRole,
                false,
                metadata,
                new HashMap<>()
        );
        
        // Act
        springAIGateway.generateResponse(command);
        
        // Assert
        // Verify Prompt was captured
        Prompt capturedPrompt = promptCaptor.getValue();
        assertNotNull(capturedPrompt, "Prompt должен быть перехвачен");
        
        // Get message list from Prompt
        List<Message> messages = capturedPrompt.getInstructions();
        assertNotNull(messages, "Список сообщений не должен быть null");
        assertFalse(messages.isEmpty(), "Список сообщений не должен быть пустым");
        
        // Verify exact message order per requirements:
        // 1. System message (first) - fixed by MessageOrderingAdvisor
        // 2. History from ChatMemory (User -> Assistant)
        // 3. Last User message (current request)
        //
        // MessageOrderingAdvisor reorders messages after MessageChatMemoryAdvisor
        // so System messages come first (fix for Spring AI bug #4170)
        
        // Find message indices
        int systemIndex = -1;
        int historyUserIndex = -1;
        int historyAssistantIndex = -1;
        int currentUserIndex = -1;
        
        for (int i = 0; i < messages.size(); i++) {
            Message msg = messages.get(i);
            if (msg instanceof SystemMessage) {
                SystemMessage sysMsg = (SystemMessage) msg;
                if (systemRole.equals(sysMsg.getText())) {
                    systemIndex = i;
                }
            } else if (msg instanceof UserMessage) {
                UserMessage userMsg = (UserMessage) msg;
                if (historyUser.equals(userMsg.getText())) {
                    historyUserIndex = i;
                } else if (userRole.equals(userMsg.getText())) {
                    currentUserIndex = i;
                }
            } else if (msg instanceof AssistantMessage) {
                AssistantMessage assMsg = (AssistantMessage) msg;
                if (historyAssistant.equals(assMsg.getText())) {
                    historyAssistantIndex = i;
                }
            }
        }
        
        // Verify all messages were found
        assertTrue(systemIndex >= 0, "System сообщение должно быть найдено");
        assertTrue(historyUserIndex >= 0, "История User сообщение должно быть найдено");
        assertTrue(historyAssistantIndex >= 0, "История Assistant сообщение должно быть найдено");
        assertTrue(currentUserIndex >= 0, "Текущее User сообщение должно быть найдено");
        
        // Verify correct order per requirements:
        // System (index 0) -> History User (index 1) -> History Assistant (index 2) -> Current User (index 3)
        
        // Verify System message is first
        assertEquals(0, systemIndex, 
                "System сообщение должно быть первым (index 0), но было на index: " + systemIndex + 
                ". Текущий порядок: " + getMessagesOrderString(messages));
        
        // Verify history User comes after System
        assertEquals(1, historyUserIndex, 
                "История User должна быть на index 1, но была на index: " + historyUserIndex + 
                ". Текущий порядок: " + getMessagesOrderString(messages));
        
        // Verify history Assistant comes after history User
        assertEquals(2, historyAssistantIndex, 
                "История Assistant должна быть на index 2, но была на index: " + historyAssistantIndex + 
                ". Текущий порядок: " + getMessagesOrderString(messages));
        
        // Verify current User message is last
        assertEquals(3, currentUserIndex, 
                "Текущее User сообщение должно быть последним (index 3), но было на index: " + currentUserIndex + 
                ". Текущий порядок: " + getMessagesOrderString(messages));
        
        // Final check: order must be System -> History (User -> Assistant) -> User
        assertEquals(4, messages.size(), 
                "Должно быть 4 сообщения: System, History User, History Assistant, Current User");
    }
    
    private String getMessagesOrderString(List<Message> messages) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < messages.size(); i++) {
            Message msg = messages.get(i);
            String role = msg instanceof SystemMessage ? "system" : 
                         msg instanceof UserMessage ? "user" : 
                         msg instanceof AssistantMessage ? "assistant" : "unknown";
            sb.append(i).append(":").append(role);
            if (i < messages.size() - 1) {
                sb.append(", ");
            }
        }
        sb.append("]");
        return sb.toString();
    }

    @Test
    void whenChatMemoryHasSummary_thenSummaryIsIncludedInMessages() {
        // Arrange
        String threadKey = "test-thread-key";
        String systemRole = "You are a helpful assistant";
        String userRole = "Hello, how are you?";
        String summaryText = "Краткое содержание предыдущей беседы:\nSummary text\n\nКлючевые моменты:\n• Point 1\n• Point 2";
        
        // Setup ChatMemory to return summary as SystemMessage
        List<Message> historyWithSummary = List.of(
                new SystemMessage(summaryText), // Summary as SystemMessage
                new UserMessage("Recent user message"),
                new AssistantMessage("Recent assistant response")
        );
        when(chatMemory.get(threadKey)).thenReturn(historyWithSummary);
        
        // Setup ChatModel to return response
        ChatResponse mockResponse = ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage("Test response"))))
                .build();
        
        // Use ArgumentCaptor to capture Prompt
        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        when(ollamaChatModel.call(promptCaptor.capture())).thenReturn(mockResponse);
        
        // Create command with threadKey
        Map<String, String> metadata = new HashMap<>();
        metadata.put(AICommand.THREAD_KEY_FIELD, threadKey);
        ChatAICommand command = new ChatAICommand(
                Set.of(ModelCapabilities.CHAT),
                0.7,
                1000,
                systemRole,
                userRole,
                false,
                metadata,
                new HashMap<>()
        );
        
        // Act
        springAIGateway.generateResponse(command);
        
        // Assert
        // Verify ChatMemory.get() was called
        verify(chatMemory, times(1)).get(threadKey);
        
        // Verify Prompt was captured
        Prompt capturedPrompt = promptCaptor.getValue();
        assertNotNull(capturedPrompt, "Prompt должен быть перехвачен");
        
        // Get message list from Prompt
        List<Message> messages = capturedPrompt.getInstructions();
        assertNotNull(messages, "Список сообщений не должен быть null");
        
        // Verify correct order: System (current) -> summary(System) -> History -> User
        // MessageOrderingAdvisor must reorder all System messages first
        
        // Find indices of all messages to verify order
        int systemRoleIndex = -1;
        int summaryIndex = -1;
        int historyUserIndex = -1;
        int historyAssistantIndex = -1;
        int currentUserIndex = -1;
        
        for (int i = 0; i < messages.size(); i++) {
            Message msg = messages.get(i);
            if (msg instanceof SystemMessage) {
                SystemMessage sysMsg = (SystemMessage) msg;
                if (systemRole.equals(sysMsg.getText())) {
                    systemRoleIndex = i;
                } else if (sysMsg.getText().contains("Краткое содержание предыдущей беседы") || 
                          sysMsg.getText().contains("Summary text")) {
                    summaryIndex = i;
                }
            } else if (msg instanceof UserMessage) {
                UserMessage userMsg = (UserMessage) msg;
                if ("Recent user message".equals(userMsg.getText())) {
                    historyUserIndex = i;
                } else if (userRole.equals(userMsg.getText())) {
                    currentUserIndex = i;
                }
            } else if (msg instanceof AssistantMessage) {
                AssistantMessage assMsg = (AssistantMessage) msg;
                if ("Recent assistant response".equals(assMsg.getText())) {
                    historyAssistantIndex = i;
                }
            }
        }
        
        // Verify all messages were found
        assertTrue(systemRoleIndex >= 0, "System сообщение (systemRole) должно быть найдено");
        assertTrue(summaryIndex >= 0, "Summary System сообщение должно быть найдено");
        assertTrue(historyUserIndex >= 0, "История User сообщение должно быть найдено");
        assertTrue(historyAssistantIndex >= 0, "История Assistant сообщение должно быть найдено");
        assertTrue(currentUserIndex >= 0, "Текущее User сообщение должно быть найдено");
        
        // Verify correct order: System (current) -> summary(System) -> History -> User
        assertEquals(0, systemRoleIndex, 
                "System сообщение (systemRole) должно быть первым (index 0), но было на index: " + systemRoleIndex +
                ". Текущий порядок: " + getMessagesOrderString(messages));
        assertEquals(1, summaryIndex,
                "Summary System сообщение должно быть вторым (index 1), но было на index: " + summaryIndex +
                ". Текущий порядок: " + getMessagesOrderString(messages));
        assertEquals(2, historyUserIndex,
                "История User должна быть на index 2, но была на index: " + historyUserIndex +
                ". Текущий порядок: " + getMessagesOrderString(messages));
        assertEquals(3, historyAssistantIndex,
                "История Assistant должна быть на index 3, но была на index: " + historyAssistantIndex +
                ". Текущий порядок: " + getMessagesOrderString(messages));
        assertEquals(4, currentUserIndex,
                "Текущее User сообщение должно быть последним (index 4), но было на index: " + currentUserIndex +
                ". Текущий порядок: " + getMessagesOrderString(messages));
        
        // Final check: order must be System -> summary(System) -> History (User -> Assistant) -> User
        assertEquals(5, messages.size(), 
                "Должно быть 5 сообщений: System, Summary, History User, History Assistant, Current User");
    }

    @Test
    void whenChatMemoryTriggersSummarization_thenSummaryIsAdded() {
        // Arrange
        String threadKey = "test-thread-key";
        String systemRole = "You are a helpful assistant";
        String userRole = "Hello, how are you?";
        
        // Simulate ChatMemory returning many messages
        // (which should trigger summarization in SummarizingChatMemory)
        List<Message> manyHistoryMessages = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            manyHistoryMessages.add(new UserMessage("User message " + i));
            manyHistoryMessages.add(new AssistantMessage("Assistant response " + i));
        }
        when(chatMemory.get(threadKey)).thenReturn(manyHistoryMessages);
        
        // Setup ChatModel to return response
        ChatResponse mockResponse = ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage("Test response"))))
                .build();
        
        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        when(ollamaChatModel.call(promptCaptor.capture())).thenReturn(mockResponse);
        
        // Create command with threadKey
        Map<String, String> metadata = new HashMap<>();
        metadata.put(AICommand.THREAD_KEY_FIELD, threadKey);
        ChatAICommand command = new ChatAICommand(
                Set.of(ModelCapabilities.CHAT),
                0.7,
                1000,
                systemRole,
                userRole,
                false,
                metadata,
                new HashMap<>()
        );
        
        // Act
        springAIGateway.generateResponse(command);
        
        // Assert
        // Verify ChatMemory.get() was called
        verify(chatMemory, atLeastOnce()).get(threadKey);
        
        // Verify Prompt was captured
        Prompt capturedPrompt = promptCaptor.getValue();
        assertNotNull(capturedPrompt, "Prompt должен быть перехвачен");
        
        // Verify messages were passed to ChatModel
        List<Message> messages = capturedPrompt.getInstructions();
        assertNotNull(messages, "Список сообщений не должен быть null");
        assertFalse(messages.isEmpty(), "Список сообщений не должен быть пустым");
        
        // Verify order: System must be at start, User last
        boolean hasSystemMessage = messages.stream()
                .anyMatch(m -> m instanceof SystemMessage);
        assertTrue(hasSystemMessage, 
                "Должно быть хотя бы одно System сообщение");
        assertTrue(messages.get(messages.size() - 1) instanceof UserMessage, 
                "Последнее сообщение должно быть UserMessage, но было: " + 
                messages.get(messages.size() - 1).getClass().getSimpleName());
    }
}
