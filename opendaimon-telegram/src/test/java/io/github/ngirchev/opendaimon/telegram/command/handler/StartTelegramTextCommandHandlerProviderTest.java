package io.github.ngirchev.opendaimon.telegram.command.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import io.github.ngirchev.opendaimon.bulkhead.service.PriorityRequestExecutor;
import io.github.ngirchev.opendaimon.common.ai.factory.AICommandFactoryRegistry;
import io.github.ngirchev.opendaimon.common.config.CoreCommonProperties;
import io.github.ngirchev.opendaimon.common.repository.ConversationThreadRepository;
import io.github.ngirchev.opendaimon.common.repository.OpenDaimonMessageRepository;
import org.springframework.context.MessageSource;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import io.github.ngirchev.opendaimon.common.service.AIGatewayRegistry;
import io.github.ngirchev.opendaimon.common.service.AssistantRoleService;
import io.github.ngirchev.opendaimon.common.service.BugreportService;
import io.github.ngirchev.opendaimon.common.service.ConversationContextBuilderService;
import io.github.ngirchev.opendaimon.common.service.ConversationThreadService;
import io.github.ngirchev.opendaimon.common.service.OpenDaimonMessageService;
import io.github.ngirchev.opendaimon.common.service.MessageLocalizationService;
import io.github.ngirchev.opendaimon.common.service.SummarizationService;
import io.github.ngirchev.opendaimon.telegram.TelegramBot;
import io.github.ngirchev.opendaimon.telegram.command.handler.impl.RoleTelegramCommandHandler;
import io.github.ngirchev.opendaimon.telegram.command.handler.impl.StartTelegramCommandHandler;
import io.github.ngirchev.opendaimon.telegram.config.TelegramCommandHandlerConfig;
import io.github.ngirchev.opendaimon.telegram.config.TelegramProperties;
import io.github.ngirchev.opendaimon.telegram.repository.TelegramUserRepository;
import io.github.ngirchev.opendaimon.telegram.service.TelegramMessageService;
import io.github.ngirchev.opendaimon.telegram.service.TelegramUserService;
import io.github.ngirchev.opendaimon.telegram.service.TelegramUserSessionService;
import io.github.ngirchev.opendaimon.telegram.service.TypingIndicatorService;

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
        "open-daimon.telegram.enabled=true",
        "open-daimon.telegram.commands.start-enabled=true",
        "open-daimon.telegram.commands.role-enabled=true",
        "open-daimon.telegram.commands.message-enabled=true",
        "open-daimon.telegram.commands.newthread-enabled=true",
        "open-daimon.telegram.commands.history-enabled=true",
        "open-daimon.telegram.commands.threads-enabled=true",
        "open-daimon.telegram.token=test-token",
        "open-daimon.telegram.username=test-bot"
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
        public OpenDaimonMessageService messageService() {
            return mock(OpenDaimonMessageService.class);
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
        public OpenDaimonMessageRepository messageRepository() {
            return mock(OpenDaimonMessageRepository.class);
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

