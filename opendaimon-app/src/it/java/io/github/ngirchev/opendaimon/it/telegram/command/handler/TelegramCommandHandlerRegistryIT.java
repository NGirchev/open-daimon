package io.github.ngirchev.opendaimon.it.telegram.command.handler;

import io.github.ngirchev.opendaimon.it.ITTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import io.github.ngirchev.opendaimon.bulkhead.config.BulkHeadAutoConfig;
import io.github.ngirchev.opendaimon.common.command.CommandHandlerRegistry;
import io.github.ngirchev.opendaimon.common.command.ICommandHandler;
import io.github.ngirchev.opendaimon.common.config.CoreAutoConfig;
import io.github.ngirchev.opendaimon.telegram.TelegramBot;
import io.github.ngirchev.opendaimon.telegram.config.TelegramCommandHandlerConfig;
import io.github.ngirchev.opendaimon.telegram.config.TelegramFlywayConfig;
import io.github.ngirchev.opendaimon.telegram.config.TelegramJpaConfig;
import io.github.ngirchev.opendaimon.telegram.config.TelegramProperties;
import io.github.ngirchev.opendaimon.telegram.config.TelegramServiceConfig;
import io.github.ngirchev.opendaimon.telegram.service.TelegramBotRegistrar;
import io.github.ngirchev.opendaimon.test.TestDatabaseConfiguration;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = ITTestConfiguration.class)
@EnableConfigurationProperties(TelegramProperties.class)
@Import({
        TestDatabaseConfiguration.class,
        BulkHeadAutoConfig.class,
        CoreAutoConfig.class,
        TelegramJpaConfig.class,
        TelegramFlywayConfig.class,
        TelegramServiceConfig.class,
        // TelegramAutoConfig not imported — it registers the bot on ApplicationReadyEvent
        TelegramCommandHandlerConfig.class
})
@TestPropertySource(properties = {
        // Disable Spring AI autoconfiguration (OpenAI, Ollama, etc.)
        "spring.autoconfigure.exclude=" +
                "org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration," +
                "org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration," +
                "org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionAutoConfiguration," +
                "org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration," +
                "org.springframework.ai.model.openai.autoconfigure.OpenAiImageAutoConfiguration," +
                "io.github.ngirchev.opendaimon.ai.springai.config.SpringAIAutoConfig",
        "open-daimon.telegram.enabled=true",
        "open-daimon.telegram.token=test-token",
        "open-daimon.telegram.username=test-bot",
        "open-daimon.telegram.start-message=Test welcome message",
        "open-daimon.telegram.max-message-length=4096",
        "open-daimon.common.bulkhead.enabled=true",
        "open-daimon.common.assistant-role=You are a helpful assistant",
        "open-daimon.common.summarization.max-context-tokens=8000",
        "open-daimon.common.summarization.summary-trigger-threshold=0.7",
        "open-daimon.common.summarization.keep-recent-messages=20",
        "open-daimon.common.summarization.prompt=You are an assistant. Create a summary in JSON. Conversation:",
        "open-daimon.common.manual-conversation-history.enabled=false",
        "open-daimon.common.manual-conversation-history.max-response-tokens=4000",
        "open-daimon.common.manual-conversation-history.default-window-size=20",
        "open-daimon.common.manual-conversation-history.include-system-prompt=true",
        "open-daimon.common.manual-conversation-history.token-estimation-chars-per-token=4",
        "open-daimon.telegram.commands.start-enabled=true",
        "open-daimon.telegram.commands.role-enabled=true",
        "open-daimon.telegram.commands.message-enabled=true",
        "open-daimon.telegram.commands.bugreport-enabled=true",
        "open-daimon.telegram.commands.newthread-enabled=true",
        "open-daimon.telegram.commands.history-enabled=true",
        "open-daimon.telegram.commands.threads-enabled=true",
        // File upload properties
        "open-daimon.telegram.file-upload.enabled=false",
        "open-daimon.telegram.file-upload.max-file-size-mb=10",
        "open-daimon.telegram.file-upload.supported-image-types=jpeg,png,gif,webp",
        "open-daimon.telegram.file-upload.supported-document-types=pdf",
        "open-daimon.ai.openrouter.enabled=false",
        "open-daimon.ai.deepseek.enabled=false",
        "open-daimon.ai.spring-ai.enabled=false",
        // Mock values for Spring AI so autoconfiguration does not fail
        "spring.ai.openai.api-key=mock-key",
        "spring.ai.ollama.base-url=http://localhost:11434"
})
class TelegramCommandHandlerRegistryIT {

    @MockBean
    private TelegramBot telegramBot;

    @MockBean
    private TelegramBotRegistrar telegramBotRegistrar;

    @Autowired
    private CommandHandlerRegistry registry;

    @Test
    void whenRegistryCreated_thenAllExpectedHandlersAreRegistered() {
        // Arrange
        List<ICommandHandler<?, ?, ?>> handlers = registry.getHandlers();
        
        // Act & Assert
        assertNotNull(handlers, "Handler list must not be null");
        assertFalse(handlers.isEmpty(), "At least one handler must be registered");

        Set<String> handlerClassNames = handlers.stream()
                .map(handler -> handler.getClass().getSimpleName())
                .collect(Collectors.toSet());

        // Verify all expected handlers are present
        assertTrue(handlerClassNames.contains("StartTelegramCommandHandler"),
                "StartTelegramCommandHandler must be registered");
        assertTrue(handlerClassNames.contains("MessageTelegramCommandHandler"),
                "MessageTelegramCommandHandler must be registered");
        assertTrue(handlerClassNames.contains("RoleTelegramCommandHandler"),
                "RoleTelegramCommandHandler must be registered");
        assertTrue(handlerClassNames.contains("BugreportTelegramCommandHandler"),
                "BugreportTelegramCommandHandler must be registered");
        assertTrue(handlerClassNames.contains("NewThreadTelegramCommandHandler"),
                "NewThreadTelegramCommandHandler must be registered");
        assertTrue(handlerClassNames.contains("HistoryTelegramCommandHandler"),
                "HistoryTelegramCommandHandler must be registered");
        assertTrue(handlerClassNames.contains("ThreadsTelegramCommandHandler"),
                "ThreadsTelegramCommandHandler must be registered");
        assertTrue(handlerClassNames.contains("BackoffCommandHandler"),
                "BackoffCommandHandler must be registered");
    }

    @Test
    void whenRegistryCreated_thenHandlersHaveValidPriorities() {
        // Arrange
        List<ICommandHandler<?, ?, ?>> handlers = registry.getHandlers();

        // Act & Assert
        handlers.forEach(handler -> {
            int priority = handler.priority();
            assertTrue(priority >= 0, 
                    String.format("Handler %s must have priority >= 0, but has %d", 
                            handler.getClass().getSimpleName(), priority));
        });
    }

    @Test
    void whenRegistryCreated_thenNoDuplicateHandlers() {
        // Arrange
        List<ICommandHandler<?, ?, ?>> handlers = registry.getHandlers();

        // Act
        Set<String> handlerClassNames = handlers.stream()
                .map(handler -> handler.getClass().getSimpleName())
                .collect(Collectors.toSet());

        // Assert
        assertEquals(handlers.size(), handlerClassNames.size(),
                "There must be no duplicate handlers. " +
                "Expected " + handlers.size() + " unique handlers, " +
                "found " + handlerClassNames.size());
    }

    @Test
    void whenRegistryCreated_thenHandlersCountMatchesExpected() {
        // Arrange
        List<ICommandHandler<?, ?, ?>> handlers = registry.getHandlers();

        // Act
        int handlersCount = handlers.size();

        // Assert - expect at least 8 handlers (Start, Message, Role, Bugreport, NewThread, History, Threads, Backoff)
        assertTrue(handlersCount >= 8,
                String.format("Expected at least 8 handlers, but found %d", handlersCount));
    }
}

