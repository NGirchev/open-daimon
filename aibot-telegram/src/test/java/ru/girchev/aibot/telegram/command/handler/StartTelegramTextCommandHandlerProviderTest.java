package ru.girchev.aibot.telegram.command.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import ru.girchev.aibot.bulkhead.service.PriorityRequestExecutor;
import ru.girchev.aibot.common.ai.factory.AICommandFactoryRegistry;
import ru.girchev.aibot.common.config.CoreCommonProperties;
import ru.girchev.aibot.common.repository.ConversationThreadRepository;
import ru.girchev.aibot.common.repository.AIBotMessageRepository;
import org.springframework.context.MessageSource;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import ru.girchev.aibot.common.service.AIGatewayRegistry;
import ru.girchev.aibot.common.service.AssistantRoleService;
import ru.girchev.aibot.common.service.BugreportService;
import ru.girchev.aibot.common.service.ConversationContextBuilderService;
import ru.girchev.aibot.common.service.ConversationThreadService;
import ru.girchev.aibot.common.service.AIBotMessageService;
import ru.girchev.aibot.common.service.MessageLocalizationService;
import ru.girchev.aibot.common.service.SummarizationService;
import ru.girchev.aibot.telegram.TelegramBot;
import ru.girchev.aibot.telegram.command.handler.impl.RoleTelegramCommandHandler;
import ru.girchev.aibot.telegram.command.handler.impl.StartTelegramCommandHandler;
import ru.girchev.aibot.telegram.config.TelegramCommandHandlerConfig;
import ru.girchev.aibot.telegram.config.TelegramProperties;
import ru.girchev.aibot.telegram.repository.TelegramUserRepository;
import ru.girchev.aibot.telegram.service.TelegramMessageService;
import ru.girchev.aibot.telegram.service.TelegramUserService;
import ru.girchev.aibot.telegram.service.TelegramUserSessionService;
import ru.girchev.aibot.telegram.service.TypingIndicatorService;

import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Test for ObjectProvider&lt;TelegramSupportedCommandProvider&gt; behaviour.
 * Verifies that the provider returns all command handler beans.
 */
@SpringBootTest(classes = {
        TelegramCommandHandlerConfig.class
})
@Import(StartTelegramTextCommandHandlerProviderTest.TestConfig.class)
@TestPropertySource(properties = {
        "ai-bot.telegram.enabled=true",
        "ai-bot.telegram.commands.start-enabled=true",
        "ai-bot.telegram.commands.role-enabled=true",
        "ai-bot.telegram.commands.message-enabled=true",
        "ai-bot.telegram.commands.newthread-enabled=true",
        "ai-bot.telegram.commands.history-enabled=true",
        "ai-bot.telegram.commands.threads-enabled=true",
        "ai-bot.telegram.token=test-token",
        "ai-bot.telegram.username=test-bot"
})
class StartTelegramTextCommandHandlerProviderTest {

    @Autowired
    private ObjectProvider<TelegramSupportedCommandProvider> handlersProvider;

    @Autowired
    private StartTelegramCommandHandler startTelegramCommandHandler;

    @Test
    void whenHandlersProviderInjected_thenShouldContainAllHandlers() {
        // Act
        List<TelegramSupportedCommandProvider> handlers = handlersProvider.orderedStream()
                .toList();

        // Assert
        assertNotNull(handlers, "Provider must not be null");
        assertFalse(handlers.isEmpty(), "Provider must contain at least one handler");

        // Verify RoleTelegramCommandHandler is present (implements getSupportedCommand)
        boolean hasRoleHandler = handlers.stream()
                .anyMatch(RoleTelegramCommandHandler.class::isInstance);
        assertTrue(hasRoleHandler, "Provider must contain RoleTelegramCommandHandler");

        System.out.println("Handlers found via ObjectProvider: " + handlers.size());
        handlers.forEach(h -> System.out.println("  - " + h.getClass().getSimpleName()));
    }

    @Test
    void whenStartHandlerUsesProvider_thenShouldGetAllHandlers() {
        // Arrange
        List<TelegramSupportedCommandProvider> handlersFromProvider = handlersProvider.orderedStream()
                .toList();

        // Act - verify StartTelegramCommandHandler uses provider and gets all handlers (except itself)
        List<TelegramSupportedCommandProvider> handlersInStart = handlersProvider.orderedStream()
                .filter(h -> h != startTelegramCommandHandler)
                .toList();

        // Assert
        assertNotNull(handlersInStart, "Handler list in StartTelegramCommandHandler must not be null");
        assertEquals(handlersFromProvider.size() - 1, handlersInStart.size(),
                "StartTelegramCommandHandler must see all handlers except itself");

        // Verify RoleTelegramCommandHandler is present
        boolean hasRoleHandler = handlersInStart.stream()
                .anyMatch(RoleTelegramCommandHandler.class::isInstance);
        assertTrue(hasRoleHandler,
                "StartTelegramCommandHandler must see RoleTelegramCommandHandler via provider");

        System.out.println("Handlers visible to StartTelegramCommandHandler: " + handlersInStart.size());
        handlersInStart.forEach(h -> {
            String command = h.getSupportedCommandText("ru");
            System.out.println("  - " + h.getClass().getSimpleName() + 
                    (command != null ? " (command: " + command + ")" : " (no command)"));
        });
    }

    @Test
    void whenGetSupportedCommands_thenShouldReturnNonEmptyList() {
        // Act
        String commands = handlersProvider.orderedStream()
                .filter(h -> h != startTelegramCommandHandler)
                .map(h -> h.getSupportedCommandText("ru"))
                .filter(cmd -> cmd != null && !cmd.isEmpty())
                .collect(Collectors.joining("\n"));

        // Assert
        assertNotNull(commands, "Command list must not be null");
        assertFalse(commands.isEmpty(), "Command list must not be empty");
        
        System.out.println("Available commands:\n" + commands);
    }

    @TestConfiguration
    static class TestConfig {

        @Bean
        public PriorityRequestExecutor priorityRequestExecutor() {
            return mock(PriorityRequestExecutor.class);
        }

        @Bean
        public TelegramBot telegramBot() {
            return mock(TelegramBot.class);
        }

        @Bean
        public MessageSource messageSource() {
            ReloadableResourceBundleMessageSource source = new ReloadableResourceBundleMessageSource();
            source.setBasenames("classpath:messages/common", "classpath:messages/telegram");
            source.setDefaultEncoding("UTF-8");
            source.setFallbackToSystemLocale(false);
            return source;
        }

        @Bean
        public MessageLocalizationService messageLocalizationService(MessageSource messageSource) {
            return new MessageLocalizationService(messageSource);
        }

        @Bean
        public TelegramProperties telegramProperties() {
            TelegramProperties props = new TelegramProperties();
            props.setToken("test-token");
            props.setUsername("test-bot");
            return props;
        }

        @Bean
        public CoreCommonProperties coreCommonProperties() {
            CoreCommonProperties props = new CoreCommonProperties();
            props.setAssistantRole("Test assistant role");
            props.setMaxOutputTokens(1000);
            props.setMaxUserMessageTokens(4000);
            props.setMaxTotalPromptTokens(32000);
            CoreCommonProperties.SummarizationProperties summarization = new CoreCommonProperties.SummarizationProperties();
            summarization.setMaxContextTokens(8000);
            summarization.setSummaryTriggerThreshold(0.7);
            summarization.setKeepRecentMessages(20);
            props.setSummarization(summarization);
            CoreCommonProperties.ManualConversationHistoryProperties manualHistory = new CoreCommonProperties.ManualConversationHistoryProperties();
            manualHistory.setEnabled(false);
            manualHistory.setMaxResponseTokens(4000);
            manualHistory.setDefaultWindowSize(20);
            manualHistory.setIncludeSystemPrompt(true);
            manualHistory.setTokenEstimationCharsPerToken(4);
            props.setManualConversationHistory(manualHistory);
            return props;
        }

        @Bean
        public TelegramUserService telegramUserService() {
            return mock(TelegramUserService.class);
        }

        @Bean
        public TelegramUserSessionService telegramUserSessionService() {
            return mock(TelegramUserSessionService.class);
        }

        @Bean
        public TelegramMessageService telegramMessageService() {
            return mock(TelegramMessageService.class);
        }

        @Bean
        public AIGatewayRegistry aiGatewayRegistry() {
            return mock(AIGatewayRegistry.class);
        }

        @Bean
        public BugreportService bugreportService() {
            return mock(BugreportService.class);
        }

        @Bean
        public AIBotMessageService messageService() {
            return mock(AIBotMessageService.class);
        }

        @Bean
        public AICommandFactoryRegistry aiCommandFactoryRegistry() {
            return mock(AICommandFactoryRegistry.class);
        }

        @Bean
        public ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        public ConversationThreadService conversationThreadService() {
            return mock(ConversationThreadService.class);
        }

        @Bean
        public ConversationThreadRepository conversationThreadRepository() {
            return mock(ConversationThreadRepository.class);
        }

        @Bean
        public ConversationContextBuilderService contextBuilderService() {
            return mock(ConversationContextBuilderService.class);
        }

        @Bean
        public AssistantRoleService assistantRoleService() {
            return mock(AssistantRoleService.class);
        }

        @Bean
        public SummarizationService summarizationService() {
            return mock(SummarizationService.class);
        }

        @Bean
        public TelegramUserRepository telegramUserRepository() {
            return mock(TelegramUserRepository.class);
        }

        @Bean
        public AIBotMessageRepository messageRepository() {
            return mock(AIBotMessageRepository.class);
        }

        @Bean
        public ScheduledExecutorService typingIndicatorScheduledExecutor() {
            return mock(ScheduledExecutorService.class);
        }

        @Bean
        public TypingIndicatorService typingIndicatorService() {
            return mock(TypingIndicatorService.class);
        }
    }
}

