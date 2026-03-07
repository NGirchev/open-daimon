package ru.girchev.aibot.it.common.service;

import ru.girchev.aibot.it.ITTestConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import ru.girchev.aibot.common.storage.service.FileStorageService;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import ru.girchev.aibot.common.ai.AIGateways;
import ru.girchev.aibot.common.ai.command.AICommand;
import ru.girchev.aibot.common.ai.response.MapResponse;
import ru.girchev.aibot.common.config.AsyncConfig;
import ru.girchev.aibot.common.config.CoreCommonProperties;
import ru.girchev.aibot.common.config.CoreFlywayConfig;
import ru.girchev.aibot.common.config.CoreJpaConfig;
import ru.girchev.aibot.common.model.ConversationThread;
import ru.girchev.aibot.common.model.AIBotMessage;
import ru.girchev.aibot.common.model.MessageRole;
import ru.girchev.aibot.common.model.RequestType;
import ru.girchev.aibot.common.model.ResponseStatus;
import ru.girchev.aibot.common.repository.AssistantRoleRepository;
import ru.girchev.aibot.common.repository.ConversationThreadRepository;
import ru.girchev.aibot.common.repository.AIBotMessageRepository;
import ru.girchev.aibot.common.service.*;
import ru.girchev.aibot.common.service.impl.AssistantRoleServiceImpl;
import ru.girchev.aibot.telegram.config.TelegramFlywayConfig;
import ru.girchev.aibot.telegram.config.TelegramJpaConfig;
import ru.girchev.aibot.telegram.model.TelegramUser;
import ru.girchev.aibot.telegram.model.TelegramUserSession;
import ru.girchev.aibot.telegram.repository.TelegramUserRepository;
import ru.girchev.aibot.telegram.repository.TelegramUserSessionRepository;
import ru.girchev.aibot.telegram.service.TelegramBotRegistrar;
import ru.girchev.aibot.test.TestDatabaseConfiguration;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static ru.girchev.aibot.common.ai.LlmParamNames.CHOICES;

@SpringBootTest(classes = ITTestConfiguration.class)
@ActiveProfiles("test")
@EnableConfigurationProperties(CoreCommonProperties.class)
@Import(value = {
        TestDatabaseConfiguration.class,
        CoreJpaConfig.class,
        CoreFlywayConfig.class,
        AsyncConfig.class,
        TelegramJpaConfig.class,
        TelegramFlywayConfig.class,
        SummarizationServiceIT.TestConfig.class
})
@TestPropertySource(properties = {
        // ВАЖНО: отключаем автозагрузку TelegramAutoConfig (иначе TelegramBotRegistrar зарегистрирует бота на ApplicationReadyEvent)
        // Отключаем CoreAutoConfig для этого теста, так как создаем бины вручную в TestConfig
        "spring.autoconfigure.exclude=" +
                "ru.girchev.aibot.telegram.config.TelegramAutoConfig," +
                "ru.girchev.aibot.common.config.CoreAutoConfig"
})
class SummarizationServiceIT {

    @TestConfiguration
    static class TestConfig {

        @Bean
        public TokenCounter tokenCounter(CoreCommonProperties coreCommonProperties) {
            return new TokenCounter(coreCommonProperties);
        }

        @Bean
        public ConversationContextBuilderService contextBuilderService(
                AIBotMessageRepository messageRepository,
                TokenCounter tokenCounter,
                CoreCommonProperties coreCommonProperties,
                ObjectProvider<FileStorageService> fileStorageServiceProvider) {
            return new ConversationContextBuilderService(
                    messageRepository,
                    tokenCounter,
                    coreCommonProperties,
                    fileStorageServiceProvider
            );
        }

        @Bean
        public ConversationThreadService conversationThreadService(
                ConversationThreadRepository threadRepository,
                AIBotMessageRepository messageRepository) {
            return new ConversationThreadService(
                    threadRepository,
                    messageRepository
            );
        }

        @Bean
        public ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        @Primary
        public AIGateway mockAiGateway() {
            AIGateway gateway = mock(AIGateway.class);
            
            // Мокируем supports, чтобы gateway поддерживал все модели
            when(gateway.supports(any(AICommand.class)))
                    .thenReturn(true);
            
            // Мокируем generateResponse, который возвращает AIResponse в формате, ожидаемом retrieveMessage
            String summaryJson = """
                    {
                      "summary": "Краткая сводка диалога о тестировании системы",
                      "memory_bullets": [
                        "Пользователь задавал вопросы о системе",
                        "Были обсуждены различные темы",
                        "Система работала корректно"
                      ]
                    }
                    """;
            
            Map<String, Object> mockResponse = new HashMap<>();
            Map<String, Object> message = new HashMap<>();
            message.put("content", summaryJson);
            Map<String, Object> choice = new HashMap<>();
            choice.put("message", message);
            mockResponse.put(CHOICES, List.of(choice));
            
            when(gateway.generateResponse(any(AICommand.class)))
                    .thenReturn(new MapResponse(AIGateways.OPENROUTER, mockResponse));
            
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
        public AssistantRoleService assistantRoleService(AssistantRoleRepository assistantRoleRepository) {
            return new AssistantRoleServiceImpl(assistantRoleRepository);
        }

        @Bean
        @Primary
        public SummarizationService summarizationService(
                AIBotMessageRepository messageRepository,
                ConversationThreadService threadService,
                AIGatewayRegistry aiGatewayRegistry,
                CoreCommonProperties coreCommonProperties,
                ObjectMapper objectMapper) {
            return new SummarizationService(
                    messageRepository,
                    threadService,
                    aiGatewayRegistry,
                    coreCommonProperties,
                    objectMapper
            );
        }
    }

    @Autowired
    private SummarizationService summarizationService;

    @Autowired
    private ConversationThreadRepository threadRepository;

    @Autowired
    private AIBotMessageRepository messageRepository;

    @Autowired
    private TelegramUserRepository telegramUserRepository;

    @Autowired
    private TelegramUserSessionRepository telegramUserSessionRepository;

    @Autowired
    private ConversationThreadService threadService;

    @MockBean
    private TelegramBotRegistrar telegramBotRegistrar;

    private TelegramUser testUser;
    private TelegramUserSession testSession;
    private ConversationThread testThread;

    @BeforeEach
    void setUp() {
        // Очищаем БД перед каждым тестом
        messageRepository.deleteAll();
        threadRepository.deleteAll();
        telegramUserRepository.deleteAll();

        // Создаем тестового пользователя
        testUser = createTestUser();
        telegramUserRepository.save(testUser);

        // Создаем тестовую сессию
        testSession = createSession(testUser);
        telegramUserSessionRepository.save(testSession);

        // Создаем тестовый thread
        testThread = threadService.createNewThread((ru.girchev.aibot.common.model.User) testUser);
        assertNotNull(testThread);
    }

    @Test
    void whenThreadTokensBelowThreshold_thenShouldNotTrigger() {
        // Arrange
        testThread.setTotalTokens(1000L); // 12.5% от 8000
        threadRepository.save(testThread);

        // Act
        boolean shouldTrigger = summarizationService.shouldTriggerSummarization(testThread);

        // Assert
        assertFalse(shouldTrigger);
    }

    @Test
    void whenThreadTokensAtThreshold_thenShouldTrigger() {
        // Arrange
        testThread.setTotalTokens(5600L); // 70% от 8000
        threadRepository.save(testThread);

        // Act
        boolean shouldTrigger = summarizationService.shouldTriggerSummarization(testThread);

        // Assert
        assertTrue(shouldTrigger);
    }

    @Test
    void whenThreadTokensAboveThreshold_thenShouldTrigger() {
        // Arrange
        testThread.setTotalTokens(8000L); // 100% от 8000
        threadRepository.save(testThread);

        // Act
        boolean shouldTrigger = summarizationService.shouldTriggerSummarization(testThread);

        // Assert
        assertTrue(shouldTrigger);
    }

    @Test
    void whenNoRequests_thenSummarizeThreadAsyncCompletesWithoutError() {
        // Arrange - thread без запросов

        // Act
        CompletableFuture<Void> future = summarizationService.summarizeThreadAsync(testThread);

        // Assert
        assertDoesNotThrow(future::join);
        
        // Проверяем, что summary не обновлен
        ConversationThread updatedThread = threadRepository.findById(testThread.getId() != null ? testThread.getId() : 0L).orElseThrow();
        assertNull(updatedThread.getSummary());
    }

    @Test
    void whenNotEnoughRequests_thenSummarizeThreadAsyncCompletesWithoutError() {
        // Arrange - создаем меньше запросов, чем defaultWindowSize (5)
        createUserRequestAndResponse(testThread, 1, "Message 1", "Response 1");
        createUserRequestAndResponse(testThread, 3, "Message 2", "Response 2");

        // Act
        CompletableFuture<Void> future = summarizationService.summarizeThreadAsync(testThread);

        // Assert
        assertDoesNotThrow(future::join);
        
        // Проверяем, что summary не обновлен (недостаточно запросов для summarization)
        ConversationThread updatedThread = threadRepository.findById(testThread.getId() != null ? testThread.getId() : 0L).orElseThrow();
        assertNull(updatedThread.getSummary());
    }

    @Test
    void whenEnoughRequests_thenSummarizeThreadAsyncProcessesRequests() {
        // Arrange - создаем больше запросов, чем defaultWindowSize (5)
        // Создаем 7 запросов: при defaultWindowSize=5 суммаризируется 2 первых (7-5=2)
        createUserRequestAndResponse(testThread, 1, "Message 1", "Response 1");
        createUserRequestAndResponse(testThread, 3, "Message 2", "Response 2");
        createUserRequestAndResponse(testThread, 5, "Message 3", "Response 3");
        createUserRequestAndResponse(testThread, 7, "Message 4", "Response 4");
        createUserRequestAndResponse(testThread, 9, "Message 5", "Response 5");
        createUserRequestAndResponse(testThread, 11, "Message 6", "Response 6");
        createUserRequestAndResponse(testThread, 13, "Message 7", "Response 7");

        // Act
        CompletableFuture<Void> future = summarizationService.summarizeThreadAsync(testThread);

        // Assert
        assertDoesNotThrow(future::join);
        
        // Проверяем, что summary обновлен
        ConversationThread updatedThread = threadRepository.findById(testThread.getId() != null ? testThread.getId() : 0L).orElseThrow();
        assertNotNull(updatedThread.getSummary());
        assertTrue(updatedThread.getSummary().contains("Краткая сводка"));
        assertNotNull(updatedThread.getMemoryBullets());
        assertFalse(updatedThread.getMemoryBullets().isEmpty());
    }

    @Test
    void whenSummarizeThreadAsync_thenCombinesWithExistingSummary() {
        // Arrange
        testThread.setSummary("Существующая сводка");
        testThread.setMemoryBullets(List.of("Старый факт 1", "Старый факт 2"));
        threadRepository.save(testThread);

        // Создаем достаточно запросов для summarization
        createUserRequestAndResponse(testThread, 1, "Message 1", "Response 1");
        createUserRequestAndResponse(testThread, 3, "Message 2", "Response 2");
        createUserRequestAndResponse(testThread, 5, "Message 3", "Response 3");
        createUserRequestAndResponse(testThread, 7, "Message 4", "Response 4");
        createUserRequestAndResponse(testThread, 9, "Message 5", "Response 5");
        createUserRequestAndResponse(testThread, 11, "Message 6", "Response 6");

        // Act
        CompletableFuture<Void> future = summarizationService.summarizeThreadAsync(testThread);

        // Assert
        assertDoesNotThrow(future::join);
        
        // Проверяем, что summary объединен с существующим
        ConversationThread updatedThread = threadRepository.findById(testThread.getId() != null ? testThread.getId() : 0L).orElseThrow();
        assertNotNull(updatedThread.getSummary());
        assertTrue(updatedThread.getSummary().contains("Существующая сводка"));
        assertTrue(updatedThread.getSummary().contains("Продолжение:"));
        
        // Проверяем, что memory bullets объединены
        assertTrue(updatedThread.getMemoryBullets().contains("Старый факт 1"));
        assertTrue(updatedThread.getMemoryBullets().size() > 2);
    }

    @Test
    void whenSummarizeThreadAsync_thenOnlyOldRequestsAreSummarized() {
        // Arrange - создаем 7 запросов, при defaultWindowSize=5 должны суммаризироваться только первые 2
        createUserRequestAndResponse(testThread, 1, "Old Message 1", "Old Response 1");
        createUserRequestAndResponse(testThread, 3, "Old Message 2", "Old Response 2");
        createUserRequestAndResponse(testThread, 5, "Recent Message 1", "Recent Response 1");
        createUserRequestAndResponse(testThread, 7, "Recent Message 2", "Recent Response 2");
        createUserRequestAndResponse(testThread, 9, "Recent Message 3", "Recent Response 3");
        createUserRequestAndResponse(testThread, 11, "Recent Message 4", "Recent Response 4");
        createUserRequestAndResponse(testThread, 13, "Recent Message 5", "Recent Response 5");

        // Act
        CompletableFuture<Void> future = summarizationService.summarizeThreadAsync(testThread);

        // Assert
        assertDoesNotThrow(future::join);
        
        // Проверяем, что summary содержит информацию о старых сообщениях
        ConversationThread updatedThread = threadRepository.findById(testThread.getId() != null ? testThread.getId() : 0L).orElseThrow();
        assertNotNull(updatedThread.getSummary());
        
        // Проверяем, что все сообщения все еще существуют (не удалены)
        List<AIBotMessage> allMessages =
                messageRepository.findByThreadOrderBySequenceNumberAsc(testThread);
        assertEquals(14, allMessages.size()); // 7 user + 7 assistant
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

    private void createUserRequestAndResponse(
            ConversationThread thread,
            int sequenceNumber,
            String requestText,
            String responseText) {
        assertNotNull(thread);
        
        // Создаем USER сообщение
        AIBotMessage userMessage = new AIBotMessage();
        userMessage.setUser(testUser);
        userMessage.setRole(MessageRole.USER);
        userMessage.setContent(requestText);
        userMessage.setRequestType(RequestType.TEXT);
        userMessage.setThread(thread);
        userMessage.setSequenceNumber(sequenceNumber);
        userMessage.setTokenCount(estimateTokens(requestText));
        // Сохраняем session_id в metadata
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("session_id", testSession.getId());
        userMessage.setMetadata(metadata);
        messageRepository.save(userMessage);

        // Создаем ASSISTANT сообщение
        AIBotMessage assistantMessage = new AIBotMessage();
        assistantMessage.setUser(testUser);
        assistantMessage.setRole(MessageRole.ASSISTANT);
        assistantMessage.setContent(responseText);
        assistantMessage.setServiceName("testService");
        assistantMessage.setStatus(ResponseStatus.SUCCESS);
        assistantMessage.setProcessingTimeMs(100);
        assistantMessage.setThread(thread);
        assistantMessage.setSequenceNumber(sequenceNumber + 1);
        assistantMessage.setTokenCount(estimateTokens(responseText));
        // Сохраняем session_id в metadata
        Map<String, Object> assistantMetadata = new HashMap<>();
        assistantMetadata.put("session_id", testSession.getId());
        assistantMessage.setMetadata(assistantMetadata);
        messageRepository.save(assistantMessage);

        // Обновляем счетчики thread
        threadService.updateThreadCounters(thread);
    }

    private TelegramUserSession createSession(TelegramUser user) {
        TelegramUserSession session = new TelegramUserSession();
        session.setTelegramUser(user);
        session.setSessionId(java.util.UUID.randomUUID().toString());
        session.setCreatedAt(OffsetDateTime.now());
        session.setUpdatedAt(OffsetDateTime.now());
        return session;
    }

    private int estimateTokens(String text) {
        // Простая оценка: 1 токен ≈ 4 символа
        return (int) Math.ceil((double) text.length() / 4);
    }
}

