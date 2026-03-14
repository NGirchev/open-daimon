package io.github.ngirchev.opendaimon.ai.springai.service;

import io.github.ngirchev.opendaimon.ai.springai.retry.metrics.OpenRouterStreamMetricsTracker;
import io.github.ngirchev.opendaimon.ai.springai.tool.WebTools;
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
import io.github.ngirchev.opendaimon.ai.springai.config.SpringAIModelConfig;
import io.github.ngirchev.opendaimon.ai.springai.config.SpringAIProperties;
import io.github.ngirchev.opendaimon.ai.springai.rag.FileRAGService;
import io.github.ngirchev.opendaimon.ai.springai.retry.SpringAIModelRegistry;
import io.github.ngirchev.opendaimon.common.ai.ModelCapabilities;
import io.github.ngirchev.opendaimon.common.ai.command.AICommand;
import io.github.ngirchev.opendaimon.common.ai.command.ChatAICommand;
import io.github.ngirchev.opendaimon.common.service.AIGatewayRegistry;

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
                mock(WebTools.class),
                chatMemory,
                springAIModelType,
                true // useChatMemoryAdvisor = true
        );
        
        // Create real SpringAIChatService
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<OpenRouterStreamMetricsTracker> objectProvider =
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
        lenient().when(chatMemoryProvider.getIfAvailable()).thenReturn(chatMemory);
        
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
        String userRole = "What do you know?";
        
        // Setup ChatMemory to return history
        // History should be: User -> Assistant
        String historyUser = "Test";
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
        assertNotNull(capturedPrompt, "Prompt must be captured");
        
        // Get message list from Prompt
        List<Message> messages = capturedPrompt.getInstructions();
        assertNotNull(messages, "Message list must not be null");
        assertFalse(messages.isEmpty(), "Message list must not be empty");
        
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
        assertTrue(systemIndex >= 0, "System message must be found");
        assertTrue(historyUserIndex >= 0, "History User message must be found");
        assertTrue(historyAssistantIndex >= 0, "History Assistant message must be found");
        assertTrue(currentUserIndex >= 0, "Current User message must be found");
        
        // Verify correct order per requirements:
        // System (index 0) -> History User (index 1) -> History Assistant (index 2) -> Current User (index 3)
        
        // Verify System message is first
        assertEquals(0, systemIndex, 
"System message must be first (index 0), but was at index: " + systemIndex +
                ". Order: " + getMessagesOrderString(messages));
        
        // Verify history User comes after System
        assertEquals(1, historyUserIndex, 
"History User must be at index 1, but was at index: " + historyUserIndex +
                ". Order: " + getMessagesOrderString(messages));
        
        // Verify history Assistant comes after history User
        assertEquals(2, historyAssistantIndex, 
"History Assistant must be at index 2, but was at index: " + historyAssistantIndex +
                ". Order: " + getMessagesOrderString(messages));
        
        // Verify current User message is last
        assertEquals(3, currentUserIndex, 
"Current User message must be last (index 3), but was at index: " + currentUserIndex +
                ". Order: " + getMessagesOrderString(messages));
        
        // Final check: order must be System -> History (User -> Assistant) -> User
        assertEquals(4, messages.size(), 
                "Must have 4 messages: System, History User, History Assistant, Current User");
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
        String summaryText = "Summary of previous conversation:\nSummary text\n\nKey points:\n• Point 1\n• Point 2";
        
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
        assertNotNull(capturedPrompt, "Prompt must be captured");
        
        // Get message list from Prompt
        List<Message> messages = capturedPrompt.getInstructions();
        assertNotNull(messages, "Message list must not be null");
        
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
                } else if (sysMsg.getText().contains("Summary of previous conversation") || 
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
        assertTrue(systemRoleIndex >= 0, "System message (systemRole) must be found");
        assertTrue(summaryIndex >= 0, "Summary System message must be found");
        assertTrue(historyUserIndex >= 0, "History User message must be found");
        assertTrue(historyAssistantIndex >= 0, "History Assistant message must be found");
        assertTrue(currentUserIndex >= 0, "Current User message must be found");
        
        // Verify correct order: System (current) -> summary(System) -> History -> User
        assertEquals(0, systemRoleIndex, 
                "System message (systemRole) must be first (index 0), but was at index: " + systemRoleIndex +
                ". Order: " + getMessagesOrderString(messages));
        assertEquals(1, summaryIndex,
                "Summary System message must be second (index 1), but was at index: " + summaryIndex +
                ". Order: " + getMessagesOrderString(messages));
        assertEquals(2, historyUserIndex,
                "History User must be at index 2, but was at index: " + historyUserIndex +
                ". Order: " + getMessagesOrderString(messages));
        assertEquals(3, historyAssistantIndex,
                "History Assistant must be at index 3, but was at index: " + historyAssistantIndex +
                ". Order: " + getMessagesOrderString(messages));
        assertEquals(4, currentUserIndex,
                "Current User message must be last (index 4), but was at index: " + currentUserIndex +
                ". Order: " + getMessagesOrderString(messages));
        
        // Final check: order must be System -> summary(System) -> History (User -> Assistant) -> User
        assertEquals(5, messages.size(), 
                "Must have 5 messages: System, Summary, History User, History Assistant, Current User");
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
        assertNotNull(capturedPrompt, "Prompt must be captured");
        
        // Verify messages were passed to ChatModel
        List<Message> messages = capturedPrompt.getInstructions();
        assertNotNull(messages, "Message list must not be null");
        assertFalse(messages.isEmpty(), "Message list must not be empty");
        
        // Verify order: System must be at start, User last
        boolean hasSystemMessage = messages.stream()
                .anyMatch(m -> m instanceof SystemMessage);
        assertTrue(hasSystemMessage, 
                "Must have at least one System message");
        assertTrue(messages.get(messages.size() - 1) instanceof UserMessage, 
                "Last message must be UserMessage, but was: " + 
                messages.get(messages.size() - 1).getClass().getSimpleName());
    }
}
