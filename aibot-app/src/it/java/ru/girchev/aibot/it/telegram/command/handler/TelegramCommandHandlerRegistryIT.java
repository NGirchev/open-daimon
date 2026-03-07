package ru.girchev.aibot.it.telegram.command.handler;

import ru.girchev.aibot.it.ITTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import ru.girchev.aibot.bulkhead.config.BulkHeadAutoConfig;
import ru.girchev.aibot.common.command.CommandHandlerRegistry;
import ru.girchev.aibot.common.command.ICommandHandler;
import ru.girchev.aibot.common.config.CoreAutoConfig;
import ru.girchev.aibot.telegram.TelegramBot;
import ru.girchev.aibot.telegram.config.TelegramCommandHandlerConfig;
import ru.girchev.aibot.telegram.config.TelegramFlywayConfig;
import ru.girchev.aibot.telegram.config.TelegramJpaConfig;
import ru.girchev.aibot.telegram.config.TelegramProperties;
import ru.girchev.aibot.telegram.config.TelegramServiceConfig;
import ru.girchev.aibot.telegram.service.TelegramBotRegistrar;
import ru.girchev.aibot.test.TestDatabaseConfiguration;

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
        // TelegramAutoConfig не импортируем, так как он регистрирует бота на ApplicationReadyEvent
        TelegramCommandHandlerConfig.class
})
@TestPropertySource(properties = {
        // Отключаем автоконфигурацию Spring AI (OpenAI, Ollama и т.д.)
        "spring.autoconfigure.exclude=" +
                "org.springframework.ai.model.openai.autoconfigure.OpenAiAutoConfiguration," +
                "org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration," +
                "org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration," +
                "org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionAutoConfiguration," +
                "org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration," +
                "org.springframework.ai.model.openai.autoconfigure.OpenAiImageAutoConfiguration," +
                "org.springframework.ai.ollama.OllamaAutoConfiguration," +
                "ru.girchev.aibot.ai.springai.config.SpringAIAutoConfig",
        "ai-bot.telegram.enabled=true",
        "ai-bot.telegram.token=test-token",
        "ai-bot.telegram.username=test-bot",
        "ai-bot.telegram.start-message=Тестовое приветственное сообщение",
        "ai-bot.common.bulkhead.enabled=true",
        "ai-bot.common.assistant-role=Ты полезный ассистент",
        "ai-bot.common.conversation-context.enabled=false",
        "ai-bot.common.conversation-context.max-context-tokens=8000",
        "ai-bot.common.conversation-context.max-response-tokens=4000",
        "ai-bot.common.conversation-context.default-window-size=20",
        "ai-bot.common.conversation-context.summary-trigger-threshold=0.7",
        "ai-bot.common.conversation-context.include-system-prompt=true",
        "ai-bot.common.conversation-context.token-estimation-chars-per-token=4",
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
        // Моковые значения для Spring AI, чтобы автоконфигурация не падала
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
        assertNotNull(handlers, "Список обработчиков не должен быть null");
        assertFalse(handlers.isEmpty(), "Должен быть зарегистрирован хотя бы один обработчик");

        Set<String> handlerClassNames = handlers.stream()
                .map(handler -> handler.getClass().getSimpleName())
                .collect(Collectors.toSet());

        // Проверяем наличие всех ожидаемых обработчиков
        assertTrue(handlerClassNames.contains("StartTelegramCommandHandler"),
                "Должен быть зарегистрирован StartTelegramCommandHandler");
        assertTrue(handlerClassNames.contains("MessageTelegramCommandHandler"),
                "Должен быть зарегистрирован MessageTelegramCommandHandler");
        assertTrue(handlerClassNames.contains("RoleTelegramCommandHandler"),
                "Должен быть зарегистрирован RoleTelegramCommandHandler");
        assertTrue(handlerClassNames.contains("BugreportTelegramCommandHandler"),
                "Должен быть зарегистрирован BugreportTelegramCommandHandler");
        assertTrue(handlerClassNames.contains("NewThreadTelegramCommandHandler"),
                "Должен быть зарегистрирован NewThreadTelegramCommandHandler");
        assertTrue(handlerClassNames.contains("HistoryTelegramCommandHandler"),
                "Должен быть зарегистрирован HistoryTelegramCommandHandler");
        assertTrue(handlerClassNames.contains("ThreadsTelegramCommandHandler"),
                "Должен быть зарегистрирован ThreadsTelegramCommandHandler");
        assertTrue(handlerClassNames.contains("BackoffCommandHandler"),
                "Должен быть зарегистрирован BackoffCommandHandler");
    }

    @Test
    void whenRegistryCreated_thenHandlersHaveValidPriorities() {
        // Arrange
        List<ICommandHandler<?, ?, ?>> handlers = registry.getHandlers();

        // Act & Assert
        handlers.forEach(handler -> {
            int priority = handler.priority();
            assertTrue(priority >= 0, 
                    String.format("Обработчик %s должен иметь приоритет >= 0, но имеет %d", 
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
                "Не должно быть дублирующихся обработчиков. " +
                "Ожидалось " + handlers.size() + " уникальных обработчиков, " +
                "но найдено " + handlerClassNames.size());
    }

    @Test
    void whenRegistryCreated_thenHandlersCountMatchesExpected() {
        // Arrange
        List<ICommandHandler<?, ?, ?>> handlers = registry.getHandlers();

        // Act
        int handlersCount = handlers.size();

        // Assert
        // Ожидаем минимум 8 обработчиков (Start, Message, Role, Bugreport, NewThread, History, Threads, Backoff)
        assertTrue(handlersCount >= 8,
                String.format("Ожидалось минимум 8 обработчиков, но найдено %d", handlersCount));
    }
}

