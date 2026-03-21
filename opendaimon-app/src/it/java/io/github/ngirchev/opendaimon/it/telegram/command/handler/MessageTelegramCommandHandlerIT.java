package io.github.ngirchev.opendaimon.it.telegram.command.handler;

import io.github.ngirchev.opendaimon.it.ITTestConfiguration;
import io.github.ngirchev.opendaimon.telegram.service.PersistentKeyboardService;
import io.github.ngirchev.opendaimon.telegram.service.UserModelPreferenceService;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import io.github.ngirchev.opendaimon.bulkhead.config.BulkHeadAutoConfig;
import io.github.ngirchev.opendaimon.bulkhead.service.IWhitelistService;
import io.github.ngirchev.opendaimon.common.ai.AIGateways;
import io.github.ngirchev.opendaimon.common.ai.factory.AICommandFactoryRegistry;
import io.github.ngirchev.opendaimon.common.ai.command.AICommand;
import io.github.ngirchev.opendaimon.common.ai.command.OpenDaimonChatOptions;
import io.github.ngirchev.opendaimon.common.config.CoreAutoConfig;
import io.github.ngirchev.opendaimon.common.config.CoreCommonProperties;
import io.github.ngirchev.opendaimon.common.model.ConversationThread;
import io.github.ngirchev.opendaimon.common.model.OpenDaimonMessage;
import io.github.ngirchev.opendaimon.common.model.MessageRole;
import io.github.ngirchev.opendaimon.common.repository.ConversationThreadRepository;
import io.github.ngirchev.opendaimon.common.repository.OpenDaimonMessageRepository;
import io.github.ngirchev.opendaimon.common.service.*;
import io.github.ngirchev.opendaimon.common.ai.response.MapResponse;
import io.github.ngirchev.opendaimon.telegram.TelegramBot;
import io.github.ngirchev.opendaimon.telegram.command.TelegramCommand;
import io.github.ngirchev.opendaimon.telegram.command.TelegramCommandType;
import io.github.ngirchev.opendaimon.telegram.command.handler.impl.MessageTelegramCommandHandler;
import io.github.ngirchev.opendaimon.telegram.config.TelegramFlywayConfig;
import io.github.ngirchev.opendaimon.telegram.config.TelegramJpaConfig;
import io.github.ngirchev.opendaimon.common.storage.config.StorageProperties;
import io.github.ngirchev.opendaimon.telegram.config.TelegramProperties;
import io.github.ngirchev.opendaimon.telegram.model.TelegramUser;
import io.github.ngirchev.opendaimon.telegram.repository.TelegramUserRepository;
import io.github.ngirchev.opendaimon.telegram.repository.TelegramUserSessionRepository;
import io.github.ngirchev.opendaimon.telegram.service.TelegramMessageService;
import io.github.ngirchev.opendaimon.telegram.service.TelegramUserService;
import io.github.ngirchev.opendaimon.telegram.service.TelegramUserSessionService;
import io.github.ngirchev.opendaimon.telegram.service.TypingIndicatorService;
import io.github.ngirchev.opendaimon.telegram.service.TelegramBotRegistrar;
import io.github.ngirchev.opendaimon.test.TestDatabaseConfiguration;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.eq;
import static io.github.ngirchev.opendaimon.common.ai.LlmParamNames.CHOICES;

import org.mockito.ArgumentCaptor;

@SpringBootTest(classes = ITTestConfiguration.class)
@ActiveProfiles("test")
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
        "open-daimon.telegram.token=test-token",
        "open-daimon.telegram.username=test-bot",
        "open-daimon.telegram.max-message-length=4096",
        // Disable TelegramAutoConfig auto-load (otherwise TelegramBotRegistrar registers bot on ApplicationReadyEvent)
        // Disable Spring AI autoconfig (OpenAI, Ollama, etc.)
        "spring.autoconfigure.exclude=" +
                "io.github.ngirchev.opendaimon.telegram.config.TelegramAutoConfig," +
                "org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration," +
                "org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration," +
                "org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionAutoConfiguration," +
                "org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration," +
                "org.springframework.ai.model.openai.autoconfigure.OpenAiImageAutoConfiguration," +
                "io.github.ngirchev.opendaimon.ai.springai.config.SpringAIAutoConfig",
        "open-daimon.telegram.enabled=true",
        "open-daimon.common.bulkhead.enabled=true",
        "open-daimon.common.assistant-role=You are a helpful assistant",
        "open-daimon.common.summarization.message-window-size=5",
        "open-daimon.common.summarization.max-window-tokens=16000",
        "open-daimon.common.summarization.max-output-tokens=2000",
        "open-daimon.common.summarization.prompt=You are an assistant. Create a summary in JSON. Conversation:",
        "open-daimon.ai.openrouter.enabled=false",
        "open-daimon.ai.deepseek.enabled=false",
        "open-daimon.ai.spring-ai.enabled=false",
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
                        String userRole = ((OpenDaimonChatOptions) cmd.options()).userRole();
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
                public boolean checkUserInChannel(Long userId, String channelId) {
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
                OpenDaimonMessageService messageService,
                TelegramUserService telegramUserService,
                CoreCommonProperties coreCommonProperties,
                MessageLocalizationService messageLocalizationService,
                ObjectProvider<StorageProperties> storagePropertiesProvider,
                ObjectProvider<TelegramMessageService> telegramMessageServiceSelfProvider) {
            return new TelegramMessageService(
                    messageService,
                    telegramUserService,
                    coreCommonProperties,
                    messageLocalizationService,
                    storagePropertiesProvider,
                    telegramMessageServiceSelfProvider
            );
        }

        @Bean
        @Primary
        public ScheduledExecutorService typingIndicatorScheduledExecutor() {
            return mock(ScheduledExecutorService.class);
        }

        @Bean
        @Primary
        public TypingIndicatorService typingIndicatorService() {
            return mock(TypingIndicatorService.class);
        }

        @Bean
        public UserModelPreferenceService userModelPreferenceService(
                TelegramUserRepository telegramUserRepository) {
            return new UserModelPreferenceService(telegramUserRepository);
        }

        @Bean
        @Primary
        public PersistentKeyboardService persistentKeyboardService(
                UserModelPreferenceService userModelPreferenceService,
                CoreCommonProperties coreCommonProperties,
                ObjectProvider<TelegramBot> telegramBotProvider,
                TelegramProperties telegramProperties,
                MessageLocalizationService messageLocalizationService,
                TelegramUserRepository telegramUserRepository
        ) {
            return new PersistentKeyboardService(
                    userModelPreferenceService, coreCommonProperties, telegramBotProvider, telegramProperties,
                    messageLocalizationService, telegramUserRepository);
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
                OpenDaimonMessageService messageService,
                AICommandFactoryRegistry aiCommandFactoryRegistry,
                TelegramProperties telegramProperties,
                UserModelPreferenceService userModelPreferenceService,
                PersistentKeyboardService persistentKeyboardService) {
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
                    telegramProperties,
                    userModelPreferenceService,
                    persistentKeyboardService);
        }
    }

    @Autowired
    private MessageTelegramCommandHandler messageHandler;

    @Autowired
    private TelegramUserRepository telegramUserRepository;

    @Autowired
    private ConversationThreadRepository threadRepository;

    @Autowired
    private OpenDaimonMessageRepository messageRepository;

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
        List<ConversationThread> threads = threadRepository.findByUserAndIsActiveTrueOrderByLastActivityAtDesc((io.github.ngirchev.opendaimon.common.model.User) testUser);
        assertEquals(1, threads.size());
        ConversationThread thread = threads.getFirst();
        assertNotNull(thread.getThreadKey());
        assertTrue(thread.getIsActive());

        // Verify messages were saved
        List<OpenDaimonMessage> messages = messageRepository.findByThreadOrderBySequenceNumberAsc(thread);
        assertEquals(2, messages.size()); // USER + ASSISTANT
        
        OpenDaimonMessage userMessage = messages.stream()
                .filter(m -> m.getRole() == MessageRole.USER)
                .findFirst()
                .orElseThrow();
        assertEquals("Hello, how are you?", userMessage.getContent());
        assertEquals(thread.getId(), userMessage.getThread().getId());
        assertEquals(1, userMessage.getSequenceNumber());
        assertNotNull(userMessage.getTokenCount(), "tokenCount must be set for USER message");
        assertTrue(userMessage.getTokenCount() > 0, "tokenCount must be > 0 for non-empty message");

        OpenDaimonMessage assistantMessage = messages.stream()
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
        verify(mockTelegramBot, times(1)).sendMessage(eq(command.telegramId()), messageCaptor.capture(), replyToMessageIdCaptor.capture(), any());
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

        List<OpenDaimonMessage> messages = messageRepository.findByThreadOrderBySequenceNumberAsc(thread);
        assertEquals(6, messages.size());

        messages.forEach(message -> assertEquals(thread.getId(), message.getThread().getId()));

        // USER sequence numbers: 1, 3, 5
        List<OpenDaimonMessage> userMessages = messages.stream()
                .filter(m -> m.getRole() == MessageRole.USER)
                .toList();
        assertEquals(3, userMessages.size());
        assertEquals(1, userMessages.get(0).getSequenceNumber());
        assertEquals(3, userMessages.get(1).getSequenceNumber());
        assertEquals(5, userMessages.get(2).getSequenceNumber());

        // ASSISTANT sequence numbers: 2, 4, 6
        List<OpenDaimonMessage> assistantMessages = messages.stream()
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
        List<OpenDaimonMessage> messages = messageRepository.findByThreadOrderBySequenceNumberAsc(thread);
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
        List<OpenDaimonMessage> messages = messageRepository.findAll();
        OpenDaimonMessage userMessage = messages.stream()
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
        user.setIsAdmin(false);
        user.setIsPremium(false);
        user.setIsBlocked(false);
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

