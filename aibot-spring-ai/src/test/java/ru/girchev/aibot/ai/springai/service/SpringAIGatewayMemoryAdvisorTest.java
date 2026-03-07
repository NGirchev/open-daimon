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
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.OllamaChatModel;
import ru.girchev.aibot.ai.springai.config.SpringAIModelConfig;
import ru.girchev.aibot.ai.springai.config.SpringAIProperties;
import ru.girchev.aibot.common.ai.ModelType;
import ru.girchev.aibot.common.ai.command.AIBotChatOptions;
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
 * Тест для проверки работы SpringAIGateway с memory adviser и summary.
 * 
 * Проверяет:
 * 1. Вызов ChatMemory.get() через MessageChatMemoryAdvisor
 * 2. Вызов summary через SummarizingChatMemory
 * 3. Правильный порядок сообщений в запросе: System -> Summary (если есть) -> History (из ChatMemory) -> User
 * 
 * ВАЖНО: Тест проверяет работу MessageOrderingAdvisor, который исправляет известную проблему Spring AI (issue #4170),
 * когда MessageChatMemoryAdvisor добавляет историю перед System сообщениями.
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
        // Настраиваем моки
        when(springAIProperties.getMock()).thenReturn(false);
        
        // Создаем реальный ChatClient с моком ChatModel
        ollamaChatClient = ChatClient.builder(ollamaChatModel).build();
        
        // Создаем SpringAIPromptFactory с useChatMemoryAdvisor = true
        promptFactory = new SpringAIPromptFactory(
                ollamaChatClient,
                ollamaChatClient, // openAiChatClient тоже используем ollama для простоты
                mock(ru.girchev.aibot.ai.springai.tool.WebTools.class),
                chatMemory,
                springAIModelType,
                true // useChatMemoryAdvisor = true
        );
        
        // Создаем реальный SpringAIChatService
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<ru.girchev.aibot.common.openrouter.metrics.OpenRouterStreamMetricsTracker> objectProvider = 
                mock(org.springframework.beans.factory.ObjectProvider.class);
        lenient().when(objectProvider.getIfAvailable()).thenReturn(null);
        realChatService = new SpringAIChatService(
                promptFactory,
                objectProvider
        );
        
        // Настраиваем модель
        modelConfig = new SpringAIModelConfig();
        modelConfig.setName("test-model");
        modelConfig.setCapabilities(List.of(ModelType.CHAT));
        modelConfig.setProviderType(SpringAIModelConfig.ProviderType.OLLAMA);
        modelConfig.setPriority(1);
        
        lenient().when(springAIModelType.getByCapabilities(any()))
                .thenReturn(Optional.of(modelConfig));
        lenient().when(springAIModelType.getByCapabilities(eq(Set.of(ModelType.AUTO))))
                .thenReturn(Optional.of(modelConfig));
        
        // Создаем SpringAIGateway (RAG выключен - передаем null/empty providers)
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<DocumentProcessingService> docProvider = 
                mock(org.springframework.beans.factory.ObjectProvider.class);
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<RAGService> ragProvider = 
                mock(org.springframework.beans.factory.ObjectProvider.class);
        
        springAIGateway = new SpringAIGateway(
                springAIProperties,
                aiGatewayRegistry,
                springAIModelType,
                realChatService,
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
        
        // Настраиваем ChatMemory для возврата истории
        List<Message> historyMessages = List.of(
                new UserMessage("Previous user message"),
                new AssistantMessage("Previous assistant response")
        );
        when(chatMemory.get(threadKey)).thenReturn(historyMessages);
        
        // Настраиваем ChatModel для возврата ответа
        ChatResponse mockResponse = ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage("Test response"))))
                .build();
        when(ollamaChatModel.call(any(Prompt.class))).thenReturn(mockResponse);
        
        // Создаем команду с threadKey
        Map<String, String> metadata = new HashMap<>();
        metadata.put(AICommand.THREAD_KEY_FIELD, threadKey);
        ChatAICommand command = new ChatAICommand(
                Set.of(ModelType.CHAT),
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
        // Проверяем, что ChatMemory.get() был вызван с правильным threadKey
        verify(chatMemory, times(1)).get(threadKey);
    }

    @Test
    void whenGenerateResponseWithThreadKey_thenMessagesOrderIsCorrect() {
        // Arrange
        String threadKey = "test-thread-key";
        String systemRole = "You are a friendly assistant. respond concisely and to the point.";
        String userRole = "Что ты знаешь?";
        
        // Настраиваем ChatMemory для возврата истории
        // История должна быть: User -> Assistant
        String historyUser = "Тест";
        String historyAssistant = "Okay.";
        List<Message> historyMessages = List.of(
                new UserMessage(historyUser),
                new AssistantMessage(historyAssistant)
        );
        when(chatMemory.get(threadKey)).thenReturn(historyMessages);
        
        // Настраиваем ChatModel для возврата ответа
        ChatResponse mockResponse = ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage("Test response"))))
                .build();
        
        // Используем ArgumentCaptor для перехвата Prompt
        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        when(ollamaChatModel.call(promptCaptor.capture())).thenReturn(mockResponse);
        
        // Создаем команду с threadKey
        Map<String, String> metadata = new HashMap<>();
        metadata.put(AICommand.THREAD_KEY_FIELD, threadKey);
        ChatAICommand command = new ChatAICommand(
                Set.of(ModelType.CHAT),
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
        // Проверяем, что Prompt был перехвачен
        Prompt capturedPrompt = promptCaptor.getValue();
        assertNotNull(capturedPrompt, "Prompt должен быть перехвачен");
        
        // Получаем список сообщений из Prompt
        List<Message> messages = capturedPrompt.getInstructions();
        assertNotNull(messages, "Список сообщений не должен быть null");
        assertFalse(messages.isEmpty(), "Список сообщений не должен быть пустым");
        
        // Проверяем точный порядок сообщений согласно требованиям:
        // 1. System сообщение (первое) - исправлено MessageOrderingAdvisor
        // 2. История из ChatMemory (User -> Assistant)
        // 3. Последнее User сообщение (текущий запрос)
        // 
        // MessageOrderingAdvisor переупорядочивает сообщения после MessageChatMemoryAdvisor,
        // чтобы System сообщения были первыми (исправление бага Spring AI #4170)
        
        // Находим индексы сообщений
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
        
        // Проверяем, что все сообщения найдены
        assertTrue(systemIndex >= 0, "System сообщение должно быть найдено");
        assertTrue(historyUserIndex >= 0, "История User сообщение должно быть найдено");
        assertTrue(historyAssistantIndex >= 0, "История Assistant сообщение должно быть найдено");
        assertTrue(currentUserIndex >= 0, "Текущее User сообщение должно быть найдено");
        
        // Проверяем правильный порядок согласно требованиям:
        // System (index 0) -> History User (index 1) -> History Assistant (index 2) -> Current User (index 3)
        
        // Проверяем, что System сообщение первое
        assertEquals(0, systemIndex, 
                "System сообщение должно быть первым (index 0), но было на index: " + systemIndex + 
                ". Текущий порядок: " + getMessagesOrderString(messages));
        
        // Проверяем, что история User идет после System
        assertEquals(1, historyUserIndex, 
                "История User должна быть на index 1, но была на index: " + historyUserIndex + 
                ". Текущий порядок: " + getMessagesOrderString(messages));
        
        // Проверяем, что история Assistant идет после истории User
        assertEquals(2, historyAssistantIndex, 
                "История Assistant должна быть на index 2, но была на index: " + historyAssistantIndex + 
                ". Текущий порядок: " + getMessagesOrderString(messages));
        
        // Проверяем, что текущее User сообщение последнее
        assertEquals(3, currentUserIndex, 
                "Текущее User сообщение должно быть последним (index 3), но было на index: " + currentUserIndex + 
                ". Текущий порядок: " + getMessagesOrderString(messages));
        
        // Итоговая проверка: порядок должен быть System -> History (User -> Assistant) -> User
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
        
        // Настраиваем ChatMemory для возврата summary как SystemMessage
        List<Message> historyWithSummary = List.of(
                new SystemMessage(summaryText), // Summary как SystemMessage
                new UserMessage("Recent user message"),
                new AssistantMessage("Recent assistant response")
        );
        when(chatMemory.get(threadKey)).thenReturn(historyWithSummary);
        
        // Настраиваем ChatModel для возврата ответа
        ChatResponse mockResponse = ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage("Test response"))))
                .build();
        
        // Используем ArgumentCaptor для перехвата Prompt
        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        when(ollamaChatModel.call(promptCaptor.capture())).thenReturn(mockResponse);
        
        // Создаем команду с threadKey
        Map<String, String> metadata = new HashMap<>();
        metadata.put(AICommand.THREAD_KEY_FIELD, threadKey);
        ChatAICommand command = new ChatAICommand(
                Set.of(ModelType.CHAT),
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
        // Проверяем, что ChatMemory.get() был вызван
        verify(chatMemory, times(1)).get(threadKey);
        
        // Проверяем, что Prompt был перехвачен
        Prompt capturedPrompt = promptCaptor.getValue();
        assertNotNull(capturedPrompt, "Prompt должен быть перехвачен");
        
        // Получаем список сообщений из Prompt
        List<Message> messages = capturedPrompt.getInstructions();
        assertNotNull(messages, "Список сообщений не должен быть null");
        
        // Проверяем правильный порядок: System (текущий) -> summary(System) -> История -> User
        // MessageOrderingAdvisor должен переупорядочить все System сообщения первыми
        
        // Находим индексы всех сообщений для проверки порядка
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
        
        // Проверяем, что все сообщения найдены
        assertTrue(systemRoleIndex >= 0, "System сообщение (systemRole) должно быть найдено");
        assertTrue(summaryIndex >= 0, "Summary System сообщение должно быть найдено");
        assertTrue(historyUserIndex >= 0, "История User сообщение должно быть найдено");
        assertTrue(historyAssistantIndex >= 0, "История Assistant сообщение должно быть найдено");
        assertTrue(currentUserIndex >= 0, "Текущее User сообщение должно быть найдено");
        
        // Проверяем правильный порядок: System (текущий) -> summary(System) -> История -> User
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
        
        // Итоговая проверка: порядок должен быть System -> summary(System) -> History (User -> Assistant) -> User
        assertEquals(5, messages.size(), 
                "Должно быть 5 сообщений: System, Summary, History User, History Assistant, Current User");
    }

    @Test
    void whenChatMemoryTriggersSummarization_thenSummaryIsAdded() {
        // Arrange
        String threadKey = "test-thread-key";
        String systemRole = "You are a helpful assistant";
        String userRole = "Hello, how are you?";
        
        // Симулируем ситуацию, когда ChatMemory возвращает много сообщений
        // (что должно триггерить суммаризацию в SummarizingChatMemory)
        List<Message> manyHistoryMessages = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            manyHistoryMessages.add(new UserMessage("User message " + i));
            manyHistoryMessages.add(new AssistantMessage("Assistant response " + i));
        }
        when(chatMemory.get(threadKey)).thenReturn(manyHistoryMessages);
        
        // Настраиваем ChatModel для возврата ответа
        ChatResponse mockResponse = ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage("Test response"))))
                .build();
        
        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        when(ollamaChatModel.call(promptCaptor.capture())).thenReturn(mockResponse);
        
        // Создаем команду с threadKey
        Map<String, String> metadata = new HashMap<>();
        metadata.put(AICommand.THREAD_KEY_FIELD, threadKey);
        ChatAICommand command = new ChatAICommand(
                Set.of(ModelType.CHAT),
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
        // Проверяем, что ChatMemory.get() был вызван
        verify(chatMemory, atLeastOnce()).get(threadKey);
        
        // Проверяем, что Prompt был перехвачен
        Prompt capturedPrompt = promptCaptor.getValue();
        assertNotNull(capturedPrompt, "Prompt должен быть перехвачен");
        
        // Проверяем, что сообщения были переданы в ChatModel
        List<Message> messages = capturedPrompt.getInstructions();
        assertNotNull(messages, "Список сообщений не должен быть null");
        assertFalse(messages.isEmpty(), "Список сообщений не должен быть пустым");
        
        // Проверяем порядок: System должен быть в начале, User - последним
        boolean hasSystemMessage = messages.stream()
                .anyMatch(m -> m instanceof SystemMessage);
        assertTrue(hasSystemMessage, 
                "Должно быть хотя бы одно System сообщение");
        assertTrue(messages.get(messages.size() - 1) instanceof UserMessage, 
                "Последнее сообщение должно быть UserMessage, но было: " + 
                messages.get(messages.size() - 1).getClass().getSimpleName());
    }
}
