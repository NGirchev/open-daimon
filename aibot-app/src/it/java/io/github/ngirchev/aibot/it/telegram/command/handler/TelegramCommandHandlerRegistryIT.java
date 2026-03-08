package io.github.ngirchev.aibot.it.telegram.command.handler;

import io.github.ngirchev.aibot.it.ITTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import io.github.ngirchev.aibot.bulkhead.config.BulkHeadAutoConfig;
import io.github.ngirchev.aibot.common.command.CommandHandlerRegistry;
import io.github.ngirchev.aibot.common.command.ICommandHandler;
import io.github.ngirchev.aibot.common.config.CoreAutoConfig;
import io.github.ngirchev.aibot.telegram.TelegramBot;
import io.github.ngirchev.aibot.telegram.config.TelegramCommandHandlerConfig;
import io.github.ngirchev.aibot.telegram.config.TelegramFlywayConfig;
import io.github.ngirchev.aibot.telegram.config.TelegramJpaConfig;
import io.github.ngirchev.aibot.telegram.config.TelegramProperties;
import io.github.ngirchev.aibot.telegram.config.TelegramServiceConfig;
import io.github.ngirchev.aibot.telegram.service.TelegramBotRegistrar;
import io.github.ngirchev.aibot.test.TestDatabaseConfiguration;

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
                "io.github.ngirchev.aibot.ai.springai.config.SpringAIAutoConfig",
        "ai-bot.telegram.enabled=true",
        "ai-bot.telegram.token=test-token",
        "ai-bot.telegram.username=test-bot",
        "ai-bot.telegram.start-message=Test welcome message",
        "ai-bot.telegram.max-message-length=4096",
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
        "ai-bot.telegram.commands.start-enabled=true",
        "ai-bot.telegram.commands.role-enabled=true",
        "ai-bot.telegram.commands.message-enabled=true",
        "ai-bot.telegram.commands.bugreport-enabled=true",
        "ai-bot.telegram.commands.newthread-enabled=true",
        "ai-bot.telegram.commands.history-enabled=true",
        "ai-bot.telegram.commands.threads-enabled=true",
        // File upload properties
        "ai-bot.telegram.file-upload.enabled=false",
        "ai-bot.telegram.file-upload.max-file-size-mb=10",
        "ai-bot.telegram.file-upload.supported-image-types=jpeg,png,gif,webp",
        "ai-bot.telegram.file-upload.supported-document-types=pdf",
        "ai-bot.ai.openrouter.enabled=false",
        "ai-bot.ai.deepseek.enabled=false",
        "ai-bot.ai.spring-ai.enabled=false",
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

