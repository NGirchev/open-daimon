package ru.girchev.aibot.it.telegram.command.handler;

import ru.girchev.aibot.it.ITTestConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import ru.girchev.aibot.bulkhead.config.BulkHeadAutoConfig;
import ru.girchev.aibot.bulkhead.service.IWhitelistService;
import ru.girchev.aibot.common.ai.AIGateways;
import ru.girchev.aibot.common.ai.factory.AICommandFactoryRegistry;
import ru.girchev.aibot.common.ai.command.AICommand;
import ru.girchev.aibot.common.ai.command.AIBotChatOptions;
import ru.girchev.aibot.common.config.CoreAutoConfig;
import ru.girchev.aibot.common.config.CoreCommonProperties;
import ru.girchev.aibot.common.model.ConversationThread;
import ru.girchev.aibot.common.model.AIBotMessage;
import ru.girchev.aibot.common.model.MessageRole;
import ru.girchev.aibot.common.repository.ConversationThreadRepository;
import ru.girchev.aibot.common.repository.AIBotMessageRepository;
import ru.girchev.aibot.common.service.*;
import ru.girchev.aibot.common.ai.response.MapResponse;
import ru.girchev.aibot.telegram.TelegramBot;
import ru.girchev.aibot.telegram.command.TelegramCommand;
import ru.girchev.aibot.telegram.command.TelegramCommandType;
import ru.girchev.aibot.telegram.command.handler.impl.MessageTelegramCommandHandler;
import ru.girchev.aibot.telegram.config.TelegramFlywayConfig;
import ru.girchev.aibot.telegram.config.TelegramJpaConfig;
import ru.girchev.aibot.common.storage.config.StorageProperties;
import ru.girchev.aibot.telegram.config.TelegramProperties;
import ru.girchev.aibot.telegram.model.TelegramUser;
import ru.girchev.aibot.telegram.repository.TelegramUserRepository;
import ru.girchev.aibot.telegram.repository.TelegramUserSessionRepository;
import ru.girchev.aibot.telegram.service.TelegramMessageService;
import ru.girchev.aibot.telegram.service.TelegramUserService;
import ru.girchev.aibot.telegram.service.TelegramUserSessionService;
import ru.girchev.aibot.telegram.service.TypingIndicatorService;
import ru.girchev.aibot.telegram.service.TelegramBotRegistrar;
import ru.girchev.aibot.test.TestDatabaseConfiguration;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.eq;
import static ru.girchev.aibot.common.ai.LlmParamNames.CHOICES;

import org.mockito.ArgumentCaptor;

@SpringBootTest(classes = ITTestConfiguration.class)
@EnableConfigurationProperties(TelegramProperties.class)
@Import({
        TestDatabaseConfiguration.class,
        BulkHeadAutoConfig.class,
        CoreAutoConfig.class,
        TelegramJpaConfig.class,
        TelegramFlywayConfig.class,
        MessageTelegramCommandHandlerIT.TestConfig.class
})
@TestPropertySource(properties = {
        "ai-bot.telegram.token=test-token",
        "ai-bot.telegram.username=test-bot",
        "ai-bot.telegram.start-message=Тестовое приветственное сообщение",
        "ai-bot.telegram.max-message-length=4096",
        // ВАЖНО: отключаем автозагрузку TelegramAutoConfig (иначе TelegramBotRegistrar зарегистрирует бота на ApplicationReadyEvent)
        // Отключаем автоконфигурацию Spring AI (OpenAI, Ollama и т.д.)
        "spring.autoconfigure.exclude=" +
                "ru.girchev.aibot.telegram.config.TelegramAutoConfig," +
                "org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration," +
                "org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration," +
                "org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionAutoConfiguration," +
                "org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration," +
                "org.springframework.ai.model.openai.autoconfigure.OpenAiImageAutoConfiguration," +
                "ru.girchev.aibot.ai.springai.config.SpringAIAutoConfig",
        "ai-bot.telegram.enabled=true",
        "ai-bot.common.bulkhead.enabled=true",
        "ai-bot.common.assistant-role=Ты полезный ассистент",
        "ai-bot.common.summarization.max-context-tokens=8000",
        "ai-bot.common.summarization.summary-trigger-threshold=0.7",
        "ai-bot.common.summarization.keep-recent-messages=20",
        "ai-bot.common.manual-conversation-history.enabled=false",
        "ai-bot.common.manual-conversation-history.max-response-tokens=4000",
        "ai-bot.common.manual-conversation-history.default-window-size=20",
        "ai-bot.common.manual-conversation-history.include-system-prompt=true",
        "ai-bot.common.manual-conversation-history.token-estimation-chars-per-token=4",
        "ai-bot.ai.openrouter.enabled=false",
        "ai-bot.ai.deepseek.enabled=false",
        "ai-bot.ai.spring-ai.enabled=false",
        // Моковые значения для Spring AI, чтобы автоконфигурация не падала
        "spring.ai.openai.api-key=mock-key",
        "spring.ai.ollama.base-url=http://localhost:11434"
})
class MessageTelegramCommandHandlerIT {

    @TestConfiguration
    static class TestConfig {

        @Bean
        @Primary
        public TelegramBot mockTelegramBot() {
            return mock(TelegramBot.class);
        }

        @Bean
        @Primary
        public ObjectProvider<TelegramBot> telegramBotProvider(TelegramBot mockTelegramBot) {
            @SuppressWarnings("unchecked")
            ObjectProvider<TelegramBot> provider = (ObjectProvider<TelegramBot>) mock(ObjectProvider.class);
            when(provider.getObject()).thenReturn(mockTelegramBot);
            return provider;
        }

        @Bean
        @Primary
        public AIGateway mockAiGateway() {
            AIGateway gateway = mock(AIGateway.class);
            
            // Мокируем supports, чтобы gateway поддерживал все модели
            when(gateway.supports(any(AICommand.class)))
                    .thenReturn(true);
            
            // Мокируем generateResponse, который возвращает AIResponse в формате, ожидаемом retrieveMessage
            when(gateway.generateResponse(any(AICommand.class)))
                    .thenAnswer(invocation -> {
                        AICommand cmd = invocation.getArgument(0);
                        // Простой мок: возвращаем ответ на основе запроса
                        // Всегда возвращаем валидный непустой ответ
                        String userRole = ((AIBotChatOptions) cmd.options()).userRole();
                        String responseText = "Тестовый ответ от AI";
                        if (userRole != null && !userRole.trim().isEmpty()) {
                            responseText = "Ответ на: " + userRole;
                        }
                        
                        // Убеждаемся, что responseText не пустой
                        if (responseText.trim().isEmpty()) {
                            responseText = "Тестовый ответ от AI";
                        }
                        
                        // Создаем структуру ответа в точном формате, ожидаемом retrieveMessage
                        // Используем изменяемые структуры для гарантии правильных типов
                        Map<String, Object> message = new HashMap<>();
                        message.put("content", responseText); // Явно String
                        Map<String, Object> choice = new HashMap<>();
                        choice.put("message", message);
                        choice.put("finish_reason", "stop"); // Добавляем finish_reason для тестирования извлечения данных
                        List<Map<String, Object>> choicesList = new ArrayList<>();
                        choicesList.add(choice);
                        
                        // Добавляем usage данные для тестирования извлечения полезных данных
                        Map<String, Object> usage = new HashMap<>();
                        usage.put("prompt_tokens", 10);
                        usage.put("completion_tokens", 5);
                        usage.put("total_tokens", 15);
                        
                        Map<String, Object> mockResponse = new HashMap<>();
                        mockResponse.put(CHOICES, choicesList);
                        mockResponse.put("usage", usage);
                        mockResponse.put("model", "test-model"); // Добавляем модель для тестирования
                        
                        return new MapResponse(AIGateways.OPENROUTER, mockResponse);
                    });
            
            return gateway;
        }

        @Bean
        @Primary
        public AIGatewayRegistry mockAiGatewayRegistry(AIGateway mockAiGateway) {
            AIGatewayRegistry registry = mock(AIGatewayRegistry.class);
            when(registry.getSupportedAiGateways(any(AICommand.class)))
                    .thenReturn(List.of(mockAiGateway));
            return registry;
        }

        @Bean
        @Primary
        public IWhitelistService allowAllWhitelistService() {
            return new IWhitelistService() {
                @Override
                public boolean isUserAllowed(Long userId) {
                    return true;
                }

                @Override
                public boolean checkUserInChannel(Long userId) {
                    return true;
                }

                @Override
                public void addToWhitelist(Long userId) {
                    // no-op
                }
            };
        }

        @Bean
        @Primary
        public TelegramUserSessionService telegramUserSessionService(
                TelegramUserSessionRepository telegramUserSessionRepository,
                TelegramUserRepository telegramUserRepository) {
            return new TelegramUserSessionService(telegramUserSessionRepository, telegramUserRepository);
        }

        @Bean
        @Primary
        public TelegramUserService telegramUserService(
                TelegramUserRepository telegramUserRepository,
                TelegramUserSessionService telegramUserSessionService,
                AssistantRoleService assistantRoleService) {
            return new TelegramUserService(telegramUserRepository, telegramUserSessionService, assistantRoleService);
        }

        @Bean
        @Primary
        public ObjectProvider<StorageProperties> storagePropertiesProvider() {
            ObjectProvider<StorageProperties> provider = mock(ObjectProvider.class);
            when(provider.getIfAvailable()).thenReturn(null);
            return provider;
        }

        @Bean
        @Primary
        public TelegramMessageService telegramMessageService(
                AIBotMessageService messageService,
                TelegramUserService telegramUserService,
                CoreCommonProperties coreCommonProperties,
                ObjectProvider<StorageProperties> storagePropertiesProvider) {
            return new TelegramMessageService(
                    messageService,
                    telegramUserService,
                    coreCommonProperties,
                    storagePropertiesProvider
            );
        }

        @Bean
        @Primary
        public java.util.concurrent.ScheduledExecutorService typingIndicatorScheduledExecutor() {
            return mock(java.util.concurrent.ScheduledExecutorService.class);
        }

        @Bean
        @Primary
        public TypingIndicatorService typingIndicatorService() {
            return mock(TypingIndicatorService.class);
        }

        @Bean
        @Primary
        public MessageTelegramCommandHandler messageTelegramCommandHandler(
                ObjectProvider<TelegramBot> telegramBotProvider,
                TypingIndicatorService typingIndicatorService,
                TelegramUserService telegramUserService,
                TelegramUserSessionService telegramUserSessionService,
                TelegramMessageService telegramMessageService,
                AIGatewayRegistry aiGatewayRegistry,
                AIBotMessageService messageService,
                AICommandFactoryRegistry aiCommandFactoryRegistry,
                TelegramProperties telegramProperties) {
            return new MessageTelegramCommandHandler(
                    telegramBotProvider,
                    typingIndicatorService,
                    telegramUserService,
                    telegramUserSessionService,
                    telegramMessageService,
                    aiGatewayRegistry,
                    messageService,
                    aiCommandFactoryRegistry,
                    telegramProperties);
        }
    }

    @Autowired
    private MessageTelegramCommandHandler messageHandler;

    @Autowired
    private TelegramUserRepository telegramUserRepository;

    @Autowired
    private ConversationThreadRepository threadRepository;

    @Autowired
    private AIBotMessageRepository messageRepository;

    @Autowired
    private TelegramBot mockTelegramBot;

    @MockBean
    private TelegramBotRegistrar telegramBotRegistrar;

    private TelegramUser testUser;

    @BeforeEach
    void setUp() {
        // Очищаем БД перед каждым тестом
        messageRepository.deleteAll();
        threadRepository.deleteAll();
        telegramUserRepository.deleteAll();

        // Создаем тестового пользователя
        testUser = createTestUser();
        telegramUserRepository.save(testUser);

        // Сбрасываем вызовы моков между тестами (JUnit порядок не гарантирует)
        reset(mockTelegramBot);

        // Настраиваем моки для TelegramBot
        try {
            doAnswer(invocation -> null).when(mockTelegramBot).sendMessage(anyLong(), anyString(), any());
        } catch (TelegramApiException e) {
            // Игнорируем исключение в моке
        }
    }

    @Test
    void whenHandleMessage_thenCreatesThreadAndSavesRequest() throws TelegramApiException {
        // Arrange
        TelegramCommand command = createTelegramCommand("Привет, как дела?");

        // Act
        String result = messageHandler.handleInner(command);

        // Assert
        assertNull(result); // Handler возвращает null

        // Проверяем, что создан thread
        List<ConversationThread> threads = threadRepository.findByUserAndIsActiveTrueOrderByLastActivityAtDesc((ru.girchev.aibot.common.model.User) testUser);
        assertEquals(1, threads.size());
        ConversationThread thread = threads.getFirst();
        assertNotNull(thread.getThreadKey());
        assertTrue(thread.getIsActive());

        // Проверяем, что сохранены сообщения
        List<AIBotMessage> messages = messageRepository.findByThreadOrderBySequenceNumberAsc(thread);
        assertEquals(2, messages.size()); // USER + ASSISTANT
        
        AIBotMessage userMessage = messages.stream()
                .filter(m -> m.getRole() == MessageRole.USER)
                .findFirst()
                .orElseThrow();
        assertEquals("Привет, как дела?", userMessage.getContent());
        assertEquals(thread.getId(), userMessage.getThread().getId());
        assertEquals(1, userMessage.getSequenceNumber());
        // Проверяем, что tokenCount заполнен для USER сообщения
        assertNotNull(userMessage.getTokenCount(), "tokenCount должен быть заполнен для USER сообщения");
        assertTrue(userMessage.getTokenCount() > 0, "tokenCount должен быть больше 0 для непустого сообщения");

        AIBotMessage assistantMessage = messages.stream()
                .filter(m -> m.getRole() == MessageRole.ASSISTANT)
                .findFirst()
                .orElseThrow();
        assertNotNull(assistantMessage.getContent());
        assertEquals(thread.getId(), assistantMessage.getThread().getId());
        assertEquals(2, assistantMessage.getSequenceNumber());
        // Проверяем, что tokenCount заполнен для ASSISTANT сообщения
        assertNotNull(assistantMessage.getTokenCount(), "tokenCount должен быть заполнен для ASSISTANT сообщения");
        assertTrue(assistantMessage.getTokenCount() > 0, "tokenCount должен быть больше 0 для непустого сообщения");
        
        // Проверяем, что response_data содержит полезные данные из ответа AI провайдера
        assertNotNull(assistantMessage.getResponseData(), "response_data должен быть заполнен полезными данными");
        assertTrue(assistantMessage.getResponseData().containsKey("prompt_tokens"), 
                "response_data должен содержать prompt_tokens");
        assertTrue(assistantMessage.getResponseData().containsKey("completion_tokens"), 
                "response_data должен содержать completion_tokens");
        assertTrue(assistantMessage.getResponseData().containsKey("total_tokens"), 
                "response_data должен содержать total_tokens");
        assertTrue(assistantMessage.getResponseData().containsKey("finish_reason"), 
                "response_data должен содержать finish_reason");
        assertTrue(assistantMessage.getResponseData().containsKey("actual_model"), 
                "response_data должен содержать actual_model");
        assertEquals("stop", assistantMessage.getResponseData().get("finish_reason"));
        assertEquals("test-model", assistantMessage.getResponseData().get("actual_model"));

        // Проверяем, что отправлено сообщение
        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Integer> replyToMessageIdCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(mockTelegramBot, times(1)).sendMessage(eq(command.telegramId()), messageCaptor.capture(), replyToMessageIdCaptor.capture());
        assertNotNull(messageCaptor.getValue(), "Сообщение должно быть отправлено");
        assertFalse(messageCaptor.getValue().isEmpty(), "Сообщение не должно быть пустым");
    }

    @Test
    void whenHandleMultipleMessages_thenUsesSameThread() {
        // Arrange
        TelegramCommand command1 = createTelegramCommand("Первое сообщение");
        TelegramCommand command2 = createTelegramCommand("Второе сообщение");
        TelegramCommand command3 = createTelegramCommand("Третье сообщение");

        // Act
        messageHandler.handleInner(command1);
        messageHandler.handleInner(command2);
        messageHandler.handleInner(command3);

        // Assert
        // Проверяем, что создан только один активный thread
        List<ConversationThread> threads = threadRepository.findByUserAndIsActiveTrueOrderByLastActivityAtDesc(testUser);
        assertEquals(1, threads.size());
        ConversationThread thread = threads.getFirst();

        // Проверяем, что все сообщения связаны с одним thread
        List<AIBotMessage> messages = messageRepository.findByThreadOrderBySequenceNumberAsc(thread);
        assertEquals(6, messages.size()); // 3 USER + 3 ASSISTANT
        
        messages.forEach(message -> {
            assertEquals(thread.getId(), message.getThread().getId());
        });

        // Проверяем sequence numbers для USER сообщений: 1, 3, 5
        List<AIBotMessage> userMessages = messages.stream()
                .filter(m -> m.getRole() == MessageRole.USER)
                .toList();
        assertEquals(3, userMessages.size());
        assertEquals(1, userMessages.get(0).getSequenceNumber());
        assertEquals(3, userMessages.get(1).getSequenceNumber());
        assertEquals(5, userMessages.get(2).getSequenceNumber());

        // Проверяем sequence numbers для ASSISTANT сообщений: 2, 4, 6
        List<AIBotMessage> assistantMessages = messages.stream()
                .filter(m -> m.getRole() == MessageRole.ASSISTANT)
                .toList();
        assertEquals(3, assistantMessages.size());
        assertEquals(2, assistantMessages.get(0).getSequenceNumber());
        assertEquals(4, assistantMessages.get(1).getSequenceNumber());
        assertEquals(6, assistantMessages.get(2).getSequenceNumber());
    }

    @Test
    void whenHandleMessage_thenUpdatesThreadCounters() {
        // Arrange
        TelegramCommand command1 = createTelegramCommand("Первое сообщение");
        TelegramCommand command2 = createTelegramCommand("Второе сообщение");

        // Act
        messageHandler.handleInner(command1);
        messageHandler.handleInner(command2);

        // Assert
        ConversationThread thread = threadRepository.findMostRecentActiveThread(testUser)
                .orElseThrow();

        // Проверяем, что счетчики обновлены
        assertTrue(thread.getTotalMessages() > 0);
        assertTrue(thread.getTotalTokens() >= 0);
        assertNotNull(thread.getLastActivityAt());
        
        // Проверяем, что все сообщения имеют заполненный tokenCount
        List<AIBotMessage> messages = messageRepository.findByThreadOrderBySequenceNumberAsc(thread);
        messages.forEach(message -> {
            assertNotNull(message.getTokenCount(), 
                "tokenCount должен быть заполнен для сообщения с ролью " + message.getRole());
            if (message.getRole() == MessageRole.ASSISTANT && message.getErrorMessage() != null) {
                // Для сообщений с ошибкой tokenCount может быть 0
                assertEquals(0, message.getTokenCount(), 
                    "tokenCount должен быть 0 для сообщений с ошибкой");
            } else {
                assertTrue(message.getTokenCount() >= 0, 
                    "tokenCount должен быть >= 0 для сообщения с ролью " + message.getRole());
            }
        });
    }

    @Test
    void whenHandleMessage_thenSetsThreadTitle() {
        // Arrange
        TelegramCommand command = createTelegramCommand("Это первое сообщение в беседе");

        // Act
        messageHandler.handleInner(command);

        // Assert
        ConversationThread thread = threadRepository.findMostRecentActiveThread(testUser)
                .orElseThrow();

        // Проверяем, что title установлен на основе первого сообщения
        assertNotNull(thread.getTitle());
        assertTrue(thread.getTitle().length() <= 50);
        assertTrue(thread.getTitle().contains("Это первое сообщение") || 
                   thread.getTitle().contains("Это первое"));
    }

    @Test
    void whenHandleMessage_thenCreatesAssistantRole() {
        // Arrange
        TelegramCommand command = createTelegramCommand("Тест");

        // Act
        messageHandler.handleInner(command);

        // Assert
        // Проверяем, что сообщение связано с AssistantRole
        List<AIBotMessage> messages = messageRepository.findAll();
        AIBotMessage userMessage = messages.stream()
                .filter(m -> m.getRole() == MessageRole.USER)
                .findFirst()
                .orElseThrow();
        assertNotNull(userMessage.getAssistantRole());
        assertNotNull(userMessage.getAssistantRole().getId());
    }

    // Вспомогательные методы
    private TelegramUser createTestUser() {
        TelegramUser user = new TelegramUser();
        user.setTelegramId(12345L);
        user.setUsername("testuser");
        user.setFirstName("Test");
        user.setLastName("User");
        user.setCreatedAt(OffsetDateTime.now());
        user.setUpdatedAt(OffsetDateTime.now());
        user.setLastActivityAt(OffsetDateTime.now());
        return user;
    }

    private TelegramCommand createTelegramCommand(String text) {
        Update update = new Update();
        org.telegram.telegrambots.meta.api.objects.Message message = new org.telegram.telegrambots.meta.api.objects.Message();
        User telegramUser = new User();
        telegramUser.setId(12345L);
        telegramUser.setUserName("testuser");
        telegramUser.setFirstName("Test");
        telegramUser.setLastName("User");
        message.setFrom(telegramUser);
        message.setText(text);
        message.setMessageId(1);
        update.setMessage(message);

        TelegramCommandType commandType = new TelegramCommandType(TelegramCommand.MESSAGE);
        return new TelegramCommand(1L, 12345L, commandType, update, text);
    }
}

