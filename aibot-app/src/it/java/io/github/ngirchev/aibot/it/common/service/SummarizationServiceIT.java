package io.github.ngirchev.aibot.it.common.service;

import io.github.ngirchev.aibot.it.ITTestConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import io.github.ngirchev.aibot.common.storage.service.FileStorageService;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import io.github.ngirchev.aibot.common.ai.AIGateways;
import io.github.ngirchev.aibot.common.ai.command.AICommand;
import io.github.ngirchev.aibot.common.ai.response.MapResponse;
import io.github.ngirchev.aibot.common.config.AsyncConfig;
import io.github.ngirchev.aibot.common.config.CoreCommonProperties;
import io.github.ngirchev.aibot.common.config.CoreFlywayConfig;
import io.github.ngirchev.aibot.common.config.CoreJpaConfig;
import io.github.ngirchev.aibot.common.model.ConversationThread;
import io.github.ngirchev.aibot.common.model.AIBotMessage;
import io.github.ngirchev.aibot.common.model.MessageRole;
import io.github.ngirchev.aibot.common.model.RequestType;
import io.github.ngirchev.aibot.common.model.ResponseStatus;
import io.github.ngirchev.aibot.common.repository.AssistantRoleRepository;
import io.github.ngirchev.aibot.common.repository.ConversationThreadRepository;
import io.github.ngirchev.aibot.common.repository.AIBotMessageRepository;
import io.github.ngirchev.aibot.common.service.*;
import io.github.ngirchev.aibot.common.service.impl.AssistantRoleServiceImpl;
import io.github.ngirchev.aibot.telegram.config.TelegramFlywayConfig;
import io.github.ngirchev.aibot.telegram.config.TelegramJpaConfig;
import io.github.ngirchev.aibot.telegram.model.TelegramUser;
import io.github.ngirchev.aibot.telegram.model.TelegramUserSession;
import io.github.ngirchev.aibot.telegram.repository.TelegramUserRepository;
import io.github.ngirchev.aibot.telegram.repository.TelegramUserSessionRepository;
import io.github.ngirchev.aibot.telegram.service.TelegramBotRegistrar;
import io.github.ngirchev.aibot.test.TestDatabaseConfiguration;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static io.github.ngirchev.aibot.common.ai.LlmParamNames.CHOICES;

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
        // Disable TelegramAutoConfig autoload (otherwise TelegramBotRegistrar registers bot on ApplicationReadyEvent)
        // Disable CoreAutoConfig for this test, we create beans manually in TestConfig
        "spring.autoconfigure.exclude=" +
                "io.github.ngirchev.aibot.telegram.config.TelegramAutoConfig," +
                "io.github.ngirchev.aibot.common.config.CoreAutoConfig"
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
            
            // Mock supports so gateway supports all models
            when(gateway.supports(any(AICommand.class)))
                    .thenReturn(true);
            
            // Mock generateResponse returning AIResponse in format expected by retrieveMessage
            String summaryJson = """
                    {
                      "summary": "Short summary of the system test dialogue",
                      "memory_bullets": [
                        "User asked questions about the system",
                        "Various topics were discussed",
                        "System worked correctly"
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
        public AssistantRoleService assistantRoleService(
                AssistantRoleRepository assistantRoleRepository,
                ObjectProvider<AssistantRoleService> assistantRoleServiceSelfProvider) {
            return new AssistantRoleServiceImpl(assistantRoleRepository, assistantRoleServiceSelfProvider);
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
        // Clear DB before each test
        messageRepository.deleteAll();
        threadRepository.deleteAll();
        telegramUserRepository.deleteAll();

        // Create test user
        testUser = createTestUser();
        telegramUserRepository.save(testUser);

        // Create test session
        testSession = createSession(testUser);
        telegramUserSessionRepository.save(testSession);

        // Create test thread
        testThread = threadService.createNewThread((io.github.ngirchev.aibot.common.model.User) testUser);
        assertNotNull(testThread);
    }

    @Test
    void whenThreadTokensBelowThreshold_thenShouldNotTrigger() {
        // Arrange
        testThread.setTotalTokens(1000L); // 12.5% of 8000
        threadRepository.save(testThread);

        // Act
        boolean shouldTrigger = summarizationService.shouldTriggerSummarization(testThread);

        // Assert
        assertFalse(shouldTrigger);
    }

    @Test
    void whenThreadTokensAtThreshold_thenShouldTrigger() {
        // Arrange
        testThread.setTotalTokens(5600L); // 70% of 8000
        threadRepository.save(testThread);

        // Act
        boolean shouldTrigger = summarizationService.shouldTriggerSummarization(testThread);

        // Assert
        assertTrue(shouldTrigger);
    }

    @Test
    void whenThreadTokensAboveThreshold_thenShouldTrigger() {
        // Arrange
        testThread.setTotalTokens(8000L); // 100% of 8000
        threadRepository.save(testThread);

        // Act
        boolean shouldTrigger = summarizationService.shouldTriggerSummarization(testThread);

        // Assert
        assertTrue(shouldTrigger);
    }

    @Test
    void whenNoRequests_thenSummarizeThreadAsyncCompletesWithoutError() {
        // Arrange - thread with no requests

        // Act
        CompletableFuture<Void> future = summarizationService.summarizeThreadAsync(testThread);

        // Assert
        assertDoesNotThrow(future::join);
        
        // Verify summary was not updated
        ConversationThread updatedThread = threadRepository.findById(testThread.getId() != null ? testThread.getId() : 0L).orElseThrow();
        assertNull(updatedThread.getSummary());
    }

    @Test
    void whenNotEnoughRequests_thenSummarizeThreadAsyncCompletesWithoutError() {
        // Arrange - create fewer requests than defaultWindowSize (5)
        createUserRequestAndResponse(testThread, 1, "Message 1", "Response 1");
        createUserRequestAndResponse(testThread, 3, "Message 2", "Response 2");

        // Act
        CompletableFuture<Void> future = summarizationService.summarizeThreadAsync(testThread);

        // Assert
        assertDoesNotThrow(future::join);
        
        // Verify summary was not updated (not enough requests for summarization)
        ConversationThread updatedThread = threadRepository.findById(testThread.getId() != null ? testThread.getId() : 0L).orElseThrow();
        assertNull(updatedThread.getSummary());
    }

    @Test
    void whenEnoughRequests_thenSummarizeThreadAsyncProcessesRequests() {
        // Arrange - create more requests than defaultWindowSize (5)
        // Create 7 requests: with defaultWindowSize=5 the first 2 are summarized (7-5=2)
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
        
        // Verify summary was updated
        ConversationThread updatedThread = threadRepository.findById(testThread.getId() != null ? testThread.getId() : 0L).orElseThrow();
        assertNotNull(updatedThread.getSummary());
        assertTrue(updatedThread.getSummary().contains("Short summary"));
        assertNotNull(updatedThread.getMemoryBullets());
        assertFalse(updatedThread.getMemoryBullets().isEmpty());
    }

    @Test
    void whenSummarizeThreadAsync_thenCombinesWithExistingSummary() {
        // Arrange
        testThread.setSummary("Existing summary");
        testThread.setMemoryBullets(List.of("Old fact 1", "Old fact 2"));
        threadRepository.save(testThread);

        // Create enough requests for summarization
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
        
        // Verify summary was merged with existing
        ConversationThread updatedThread = threadRepository.findById(testThread.getId() != null ? testThread.getId() : 0L).orElseThrow();
        assertNotNull(updatedThread.getSummary());
        assertTrue(updatedThread.getSummary().contains("Existing summary"));
        assertTrue(updatedThread.getSummary().contains("Continuation:"));
        
        // Verify memory bullets were merged
        assertTrue(updatedThread.getMemoryBullets().contains("Old fact 1"));
        assertTrue(updatedThread.getMemoryBullets().size() > 2);
    }

    @Test
    void whenSummarizeThreadAsync_thenOnlyOldRequestsAreSummarized() {
        // Arrange - create 7 requests, with defaultWindowSize=5 only first 2 are summarized
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
        
        // Verify summary contains info about old messages
        ConversationThread updatedThread = threadRepository.findById(testThread.getId() != null ? testThread.getId() : 0L).orElseThrow();
        assertNotNull(updatedThread.getSummary());
        
        // Verify all messages still exist (not deleted)
        List<AIBotMessage> allMessages =
                messageRepository.findByThreadOrderBySequenceNumberAsc(testThread);
        assertEquals(14, allMessages.size()); // 7 user + 7 assistant
    }

    // Helper methods
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

    private void createUserRequestAndResponse(
            ConversationThread thread,
            int sequenceNumber,
            String requestText,
            String responseText) {
        assertNotNull(thread);
        
        // Create USER message
        AIBotMessage userMessage = new AIBotMessage();
        userMessage.setUser(testUser);
        userMessage.setRole(MessageRole.USER);
        userMessage.setContent(requestText);
        userMessage.setRequestType(RequestType.TEXT);
        userMessage.setThread(thread);
        userMessage.setSequenceNumber(sequenceNumber);
        userMessage.setTokenCount(estimateTokens(requestText));
        // Save session_id in metadata
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("session_id", testSession.getId());
        userMessage.setMetadata(metadata);
        messageRepository.save(userMessage);

        // Create ASSISTANT message
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
        // Save session_id in metadata
        Map<String, Object> assistantMetadata = new HashMap<>();
        assistantMetadata.put("session_id", testSession.getId());
        assistantMessage.setMetadata(assistantMetadata);
        messageRepository.save(assistantMessage);

        // Update thread counters
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
        // Simple estimate: 1 token ≈ 4 chars
        return (int) Math.ceil((double) text.length() / 4);
    }
}

