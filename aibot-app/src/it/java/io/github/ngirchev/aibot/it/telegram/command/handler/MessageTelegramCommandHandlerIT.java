package io.github.ngirchev.aibot.it.telegram.command.handler;

import io.github.ngirchev.aibot.it.ITTestConfiguration;
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
import io.github.ngirchev.aibot.bulkhead.config.BulkHeadAutoConfig;
import io.github.ngirchev.aibot.bulkhead.service.IWhitelistService;
import io.github.ngirchev.aibot.common.ai.AIGateways;
import io.github.ngirchev.aibot.common.ai.factory.AICommandFactoryRegistry;
import io.github.ngirchev.aibot.common.ai.command.AICommand;
import io.github.ngirchev.aibot.common.ai.command.AIBotChatOptions;
import io.github.ngirchev.aibot.common.config.CoreAutoConfig;
import io.github.ngirchev.aibot.common.config.CoreCommonProperties;
import io.github.ngirchev.aibot.common.model.ConversationThread;
import io.github.ngirchev.aibot.common.model.AIBotMessage;
import io.github.ngirchev.aibot.common.model.MessageRole;
import io.github.ngirchev.aibot.common.repository.ConversationThreadRepository;
import io.github.ngirchev.aibot.common.repository.AIBotMessageRepository;
import io.github.ngirchev.aibot.common.service.*;
import io.github.ngirchev.aibot.common.ai.response.MapResponse;
import io.github.ngirchev.aibot.telegram.TelegramBot;
import io.github.ngirchev.aibot.telegram.command.TelegramCommand;
import io.github.ngirchev.aibot.telegram.command.TelegramCommandType;
import io.github.ngirchev.aibot.telegram.command.handler.impl.MessageTelegramCommandHandler;
import io.github.ngirchev.aibot.telegram.config.TelegramFlywayConfig;
import io.github.ngirchev.aibot.telegram.config.TelegramJpaConfig;
import io.github.ngirchev.aibot.common.storage.config.StorageProperties;
import io.github.ngirchev.aibot.telegram.config.TelegramProperties;
import io.github.ngirchev.aibot.telegram.model.TelegramUser;
import io.github.ngirchev.aibot.telegram.repository.TelegramUserRepository;
import io.github.ngirchev.aibot.telegram.repository.TelegramUserSessionRepository;
import io.github.ngirchev.aibot.telegram.service.TelegramMessageService;
import io.github.ngirchev.aibot.telegram.service.TelegramUserService;
import io.github.ngirchev.aibot.telegram.service.TelegramUserSessionService;
import io.github.ngirchev.aibot.telegram.service.TypingIndicatorService;
import io.github.ngirchev.aibot.telegram.service.TelegramBotRegistrar;
import io.github.ngirchev.aibot.test.TestDatabaseConfiguration;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.eq;
import static io.github.ngirchev.aibot.common.ai.LlmParamNames.CHOICES;

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
        "ai-bot.telegram.max-message-length=4096",
        // Disable TelegramAutoConfig auto-load (otherwise TelegramBotRegistrar registers bot on ApplicationReadyEvent)
        // Disable Spring AI autoconfig (OpenAI, Ollama, etc.)
        "spring.autoconfigure.exclude=" +
                "io.github.ngirchev.aibot.telegram.config.TelegramAutoConfig," +
                "org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration," +
                "org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration," +
                "org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionAutoConfiguration," +
                "org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration," +
                "org.springframework.ai.model.openai.autoconfigure.OpenAiImageAutoConfiguration," +
                "io.github.ngirchev.aibot.ai.springai.config.SpringAIAutoConfig",
        "ai-bot.telegram.enabled=true",
        "ai-bot.common.bulkhead.enabled=true",
        "ai-bot.common.assistant-role=You are a helpful assistant",
        "ai-bot.common.summarization.max-context-tokens=8000",
        "ai-bot.common.summarization.summary-trigger-threshold=0.7",
        "ai-bot.common.summarization.keep-recent-messages=20",
        "ai-bot.common.summarization.prompt=You are an assistant. Create a summary in JSON. Conversation:",
        "ai-bot.common.manual-conversation-history.enabled=false",
        "ai-bot.common.manual-conversation-history.max-response-tokens=4000",
        "ai-bot.common.manual-conversation-history.default-window-size=20",
        "ai-bot.common.manual-conversation-history.include-system-prompt=true",
        "ai-bot.common.manual-conversation-history.token-estimation-chars-per-token=4",
        "ai-bot.ai.openrouter.enabled=false",
        "ai-bot.ai.deepseek.enabled=false",
        "ai-bot.ai.spring-ai.enabled=false",
        // Mock values for Spring AI so autoconfig does not fail
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
            
            // Mock supports so gateway accepts all models
            when(gateway.supports(any(AICommand.class)))
                    .thenReturn(true);
            
            // Mock generateResponse returning AIResponse in format expected by retrieveMessage
            when(gateway.generateResponse(any(AICommand.class)))
                    .thenAnswer(invocation -> {
                        AICommand cmd = invocation.getArgument(0);
                        String userRole = ((AIBotChatOptions) cmd.options()).userRole();
                        String responseText = "Test response from AI";
                        if (userRole != null && !userRole.trim().isEmpty()) {
                            responseText = "Reply to: " + userRole;
                        }
                        if (responseText.trim().isEmpty()) {
                            responseText = "Test response from AI";
                        }
                        Map<String, Object> message = new HashMap<>();
                        message.put("content", responseText);
                        Map<String, Object> choice = new HashMap<>();
                        choice.put("message", message);
                        choice.put("finish_reason", "stop");
                        List<Map<String, Object>> choicesList = new ArrayList<>();
                        choicesList.add(choice);
                        Map<String, Object> usage = new HashMap<>();
                        usage.put("prompt_tokens", 10);
                        usage.put("completion_tokens", 5);
                        usage.put("total_tokens", 15);
                        
                        Map<String, Object> mockResponse = new HashMap<>();
                        mockResponse.put(CHOICES, choicesList);
                        mockResponse.put("usage", usage);
                        mockResponse.put("model", "test-model");
                        
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
                MessageLocalizationService messageLocalizationService,
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
                    messageLocalizationService,
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
        messageRepository.deleteAll();
        threadRepository.deleteAll();
        telegramUserRepository.deleteAll();
        testUser = createTestUser();
        telegramUserRepository.save(testUser);
        reset(mockTelegramBot);
        try {
            doAnswer(invocation -> null).when(mockTelegramBot).sendMessage(anyLong(), anyString(), any());
        } catch (TelegramApiException e) {
            // ignore in mock
        }
    }

    @Test
    void whenHandleMessage_thenCreatesThreadAndSavesRequest() throws TelegramApiException {
        // Arrange
        TelegramCommand command = createTelegramCommand("Hello, how are you?");

        // Act
        String result = messageHandler.handleInner(command);

        // Assert
        assertNull(result);

        // Verify thread created
        List<ConversationThread> threads = threadRepository.findByUserAndIsActiveTrueOrderByLastActivityAtDesc((io.github.ngirchev.aibot.common.model.User) testUser);
        assertEquals(1, threads.size());
        ConversationThread thread = threads.getFirst();
        assertNotNull(thread.getThreadKey());
        assertTrue(thread.getIsActive());

        // Verify messages were saved
        List<AIBotMessage> messages = messageRepository.findByThreadOrderBySequenceNumberAsc(thread);
        assertEquals(2, messages.size()); // USER + ASSISTANT
        
        AIBotMessage userMessage = messages.stream()
                .filter(m -> m.getRole() == MessageRole.USER)
                .findFirst()
                .orElseThrow();
        assertEquals("Hello, how are you?", userMessage.getContent());
        assertEquals(thread.getId(), userMessage.getThread().getId());
        assertEquals(1, userMessage.getSequenceNumber());
        assertNotNull(userMessage.getTokenCount(), "tokenCount must be set for USER message");
        assertTrue(userMessage.getTokenCount() > 0, "tokenCount must be > 0 for non-empty message");

        AIBotMessage assistantMessage = messages.stream()
                .filter(m -> m.getRole() == MessageRole.ASSISTANT)
                .findFirst()
                .orElseThrow();
        assertNotNull(assistantMessage.getContent());
        assertEquals(thread.getId(), assistantMessage.getThread().getId());
        assertEquals(2, assistantMessage.getSequenceNumber());
        assertNotNull(assistantMessage.getTokenCount(), "tokenCount must be set for ASSISTANT message");
        assertTrue(assistantMessage.getTokenCount() > 0, "tokenCount must be > 0 for non-empty message");
        assertNotNull(assistantMessage.getResponseData(), "response_data must contain provider data");
        assertTrue(assistantMessage.getResponseData().containsKey("prompt_tokens"), "response_data must contain prompt_tokens");
        assertTrue(assistantMessage.getResponseData().containsKey("completion_tokens"), "response_data must contain completion_tokens");
        assertTrue(assistantMessage.getResponseData().containsKey("total_tokens"), "response_data must contain total_tokens");
        assertTrue(assistantMessage.getResponseData().containsKey("finish_reason"), "response_data must contain finish_reason");
        assertTrue(assistantMessage.getResponseData().containsKey("actual_model"), "response_data must contain actual_model");
        assertEquals("stop", assistantMessage.getResponseData().get("finish_reason"));
        assertEquals("test-model", assistantMessage.getResponseData().get("actual_model"));

        // Verify message was sent
        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Integer> replyToMessageIdCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(mockTelegramBot, times(1)).sendMessage(eq(command.telegramId()), messageCaptor.capture(), replyToMessageIdCaptor.capture());
        assertNotNull(messageCaptor.getValue(), "Message must be sent");
        assertFalse(messageCaptor.getValue().isEmpty(), "Message must not be empty");
    }

    @Test
    void whenHandleMultipleMessages_thenUsesSameThread() {
        TelegramCommand command1 = createTelegramCommand("First message");
        TelegramCommand command2 = createTelegramCommand("Second message");
        TelegramCommand command3 = createTelegramCommand("Third message");

        messageHandler.handleInner(command1);
        messageHandler.handleInner(command2);
        messageHandler.handleInner(command3);

        // Only one active thread
        List<ConversationThread> threads = threadRepository.findByUserAndIsActiveTrueOrderByLastActivityAtDesc(testUser);
        assertEquals(1, threads.size());
        ConversationThread thread = threads.getFirst();

        List<AIBotMessage> messages = messageRepository.findByThreadOrderBySequenceNumberAsc(thread);
        assertEquals(6, messages.size());

        messages.forEach(message -> assertEquals(thread.getId(), message.getThread().getId()));

        // USER sequence numbers: 1, 3, 5
        List<AIBotMessage> userMessages = messages.stream()
                .filter(m -> m.getRole() == MessageRole.USER)
                .toList();
        assertEquals(3, userMessages.size());
        assertEquals(1, userMessages.get(0).getSequenceNumber());
        assertEquals(3, userMessages.get(1).getSequenceNumber());
        assertEquals(5, userMessages.get(2).getSequenceNumber());

        // ASSISTANT sequence numbers: 2, 4, 6
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
        TelegramCommand command1 = createTelegramCommand("First message");
        TelegramCommand command2 = createTelegramCommand("Second message");

        // Act
        messageHandler.handleInner(command1);
        messageHandler.handleInner(command2);

        // Assert
        ConversationThread thread = threadRepository.findMostRecentActiveThread(testUser)
                .orElseThrow();

        assertTrue(thread.getTotalMessages() > 0);
        assertTrue(thread.getTotalTokens() >= 0);
        assertNotNull(thread.getLastActivityAt());
        List<AIBotMessage> messages = messageRepository.findByThreadOrderBySequenceNumberAsc(thread);
        messages.forEach(message -> {
            assertNotNull(message.getTokenCount(), "tokenCount must be set for role " + message.getRole());
            if (message.getRole() == MessageRole.ASSISTANT && message.getErrorMessage() != null) {
                assertEquals(0, message.getTokenCount(), "tokenCount must be 0 for error messages");
            } else {
                assertTrue(message.getTokenCount() >= 0, "tokenCount must be >= 0 for role " + message.getRole());
            }
        });
    }

    @Test
    void whenHandleMessage_thenSetsThreadTitle() {
        TelegramCommand command = createTelegramCommand("This is the first message in the conversation");

        messageHandler.handleInner(command);

        ConversationThread thread = threadRepository.findMostRecentActiveThread(testUser)
                .orElseThrow();
        assertNotNull(thread.getTitle());
        assertTrue(thread.getTitle().length() <= 50);
        assertTrue(thread.getTitle().contains("This is the first message") || thread.getTitle().contains("first message"));
    }

    @Test
    void whenHandleMessage_thenCreatesAssistantRole() {
        TelegramCommand command = createTelegramCommand("Test");

        messageHandler.handleInner(command);

        // Message is linked to AssistantRole
        List<AIBotMessage> messages = messageRepository.findAll();
        AIBotMessage userMessage = messages.stream()
                .filter(m -> m.getRole() == MessageRole.USER)
                .findFirst()
                .orElseThrow();
        assertNotNull(userMessage.getAssistantRole());
        assertNotNull(userMessage.getAssistantRole().getId());
    }

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

