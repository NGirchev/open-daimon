package ru.girchev.aibot.it.telegram;

import io.github.ngirchev.dotenv.DotEnvLoader;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.beans.factory.ObjectProvider;
import org.telegram.telegrambots.meta.api.objects.Chat;
import org.telegram.telegrambots.meta.api.objects.Document;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.PhotoSize;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import ru.girchev.aibot.common.model.AttachmentType;
import ru.girchev.aibot.common.storage.service.FileStorageService;
import ru.girchev.aibot.telegram.service.TelegramFileService;
import ru.girchev.aibot.common.config.CoreFlywayConfig;
import ru.girchev.aibot.common.config.CoreJpaConfig;
import ru.girchev.aibot.common.repository.AIBotMessageRepository;
import ru.girchev.aibot.telegram.TelegramBot;
import ru.girchev.aibot.telegram.command.TelegramCommand;
import ru.girchev.aibot.telegram.command.TelegramCommandType;
import ru.girchev.aibot.telegram.command.handler.impl.MessageTelegramCommandHandler;
import ru.girchev.aibot.telegram.config.TelegramFlywayConfig;
import ru.girchev.aibot.telegram.config.TelegramJpaConfig;
import ru.girchev.aibot.test.TestDatabaseConfiguration;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Интеграционный тест для Telegram модуля с реальным Telegram Bot API.
 * 
 * <p><b>Цель:</b> Протестировать модуль aibot-telegram целиком без моков - 
 * реальные бины, реальная БД, реальный Telegram API.
 * 
 * <p>Этот тест проверяет работу TelegramBot с реальным Telegram API.
 * Тест по умолчанию отключен (@Disabled), так как требует реальных credentials.
 * 
 * <p>Для запуска теста:
 * <ol>
 *   <li>Убедитесь что файл .env содержит TELEGRAM_TOKEN, TELEGRAM_USERNAME и ADMIN_TELEGRAM_ID</li>
 *   <li>Удалите @Disabled с нужного теста или со всего класса</li>
 *   <li>Запустите тест</li>
 * </ol>
 */
@Slf4j
@Disabled("Требует реальные Telegram credentials. Удалите @Disabled для локального запуска.")
@SpringBootTest(
        classes = TelegramRealGatewayIT.TestConfig.class,
        properties = {
                "spring.main.banner-mode=off"
        }
)
@ActiveProfiles("integration-test")
@Import({
        TestDatabaseConfiguration.class,
        CoreFlywayConfig.class,
        CoreJpaConfig.class,
        TelegramFlywayConfig.class,
        TelegramJpaConfig.class
})
@TestPropertySource(properties = {
        "ai-bot.telegram.enabled=true",
        "ai-bot.telegram.token=${TELEGRAM_TOKEN}",
        "ai-bot.telegram.username=${TELEGRAM_USERNAME}",
        "ai-bot.common.bulkhead.enabled=true",
        "ai-bot.ai.gateway-mock.enabled=true"
})
class TelegramRealGatewayIT {

    static {
        DotEnvLoader.loadDotEnv(Path.of("../.env"));
    }

    @Value("${ADMIN_TELEGRAM_ID}")
    private Long adminTelegramId;

    @Autowired
    private TelegramBot telegramBot;

    @Autowired
    private MessageTelegramCommandHandler messageHandler;

    @Autowired
    private AIBotMessageRepository messageRepository;

    /**
     * Тест отправки сообщения через реальный Telegram API.
     * <p>
     * Для запуска установите переменную окружения TEST_TELEGRAM_CHAT_ID
     * с вашим chat ID (можно получить через @userinfobot в Telegram).
     */
    @Test
    void messageCommand_sendsRealTelegramMessage() {
        // Arrange
        assertThat(telegramBot.getBotToken())
                .as("TELEGRAM_TOKEN должен быть установлен")
                .isNotBlank();
        assertThat(telegramBot.getBotUsername())
                .as("TELEGRAM_USERNAME должен быть установлен")
                .isNotBlank();

        log.info("=== Testing real Telegram message command ===");
        log.info("Bot: @{}", telegramBot.getBotUsername());
        log.info("Chat ID: {}", adminTelegramId);

        // Создаём имитацию Update с реальным chatId
        var update = new Update();

        var from = new User();
        from.setId(adminTelegramId);
        from.setUserName("real-test-user");
        from.setFirstName("Real");
        from.setLastName("Test");
        from.setLanguageCode("ru");

        var msg = new Message();
        msg.setMessageId(1);
        var chat = new Chat();
        chat.setId(adminTelegramId);
        msg.setChat(chat);
        msg.setText("Тестовое сообщение из интеграционного теста");
        msg.setFrom(from);
        update.setMessage(msg);

        var command = new TelegramCommand(
                null,
                msg.getChatId(),
                new TelegramCommandType(TelegramCommand.MESSAGE),
                update,
                msg.getText()
        );
        command.stream(false);

        // Act - выполняем обработку, это отправит реальное сообщение в Telegram
        messageHandler.handle(command);

        // Assert
        assertThat(messageRepository.count())
                .as("Сообщения должны быть сохранены в БД")
                .isGreaterThanOrEqualTo(2);
        
        log.info("=== Real Telegram message test completed successfully ===");
    }

    /**
     * Тест прямой отправки сообщения через TelegramBot.sendMessage()
     */
    @Test
    void directSendMessage_sendsRealTelegramMessage() throws TelegramApiException {
        // Arrange
        assertThat(telegramBot.getBotToken())
                .as("TELEGRAM_TOKEN должен быть установлен")
                .isNotBlank();

        log.info("=== Testing direct Telegram send message ===");
        log.info("Bot: @{}", telegramBot.getBotUsername());
        log.info("Chat ID: {}", adminTelegramId);

        // Act - отправляем прямое сообщение через Telegram API
        telegramBot.sendMessage(adminTelegramId, "🧪 Прямое тестовое сообщение из TelegramRealGatewayIT");
        
        log.info("=== Direct send message test completed successfully ===");
    }

    // ==================== FILE UPLOAD TESTS ====================
    // 
    // ВАЖНО: Следующие тесты являются ФИНАЛЬНЫМИ тестами для Agent 1.
    // Они требуют включённых feature flags:
    //   - ai-bot.common.storage.enabled=true
    //   - ai-bot.telegram.file-upload.enabled=true
    // И запущенного MinIO сервера (docker-compose up minio).
    // 
    // Тесты должны запускаться пользователем вручную после настройки окружения.
    // ===========================================================

    @Autowired(required = false)
    private ObjectProvider<TelegramFileService> fileServiceProvider;

    @Autowired(required = false)
    private ObjectProvider<FileStorageService> storageServiceProvider;

    /**
     * ФИНАЛЬНЫЙ ТЕСТ для Agent 1: Обработка фото из Telegram и сохранение в MinIO.
     * 
     * <p>Для запуска этого теста необходимо:
     * <ol>
     *   <li>Запустить MinIO: docker-compose up minio</li>
     *   <li>Включить feature flags в TestPropertySource:
     *       <ul>
     *         <li>ai-bot.common.storage.enabled=true</li>
     *         <li>ai-bot.telegram.file-upload.enabled=true</li>
     *       </ul>
     *   </li>
     *   <li>Отправить боту реальное фото в Telegram</li>
     *   <li>Удалить @Disabled с этого теста</li>
     * </ol>
     * 
     * <p>Тест демонстрирует полный цикл:
     * <ol>
     *   <li>TelegramBot получает Update с фото</li>
     *   <li>TelegramFileService скачивает фото через Telegram API</li>
     *   <li>MinioFileStorageService сохраняет файл в bucket</li>
     *   <li>TelegramCommand содержит Attachment с метаданными</li>
     * </ol>
     */
    @Test
    @Disabled("ФИНАЛЬНЫЙ ТЕСТ: Требует MinIO и реальное фото от пользователя")
    void photoCommand_savesToMinioStorage() {
        log.info("=== Testing photo upload to MinIO ===");
        
        // Проверка что сервисы доступны
        assertThat(fileServiceProvider)
                .as("TelegramFileService должен быть доступен (включите ai-bot.telegram.file-upload.enabled=true)")
                .isNotNull();
        assertThat(storageServiceProvider)
                .as("FileStorageService должен быть доступен (включите ai-bot.common.storage.enabled=true)")
                .isNotNull();

        TelegramFileService fileService = fileServiceProvider.getIfAvailable();
        FileStorageService storageService = storageServiceProvider.getIfAvailable();
        
        assertThat(fileService)
                .as("TelegramFileService bean должен быть создан")
                .isNotNull();
        assertThat(storageService)
                .as("FileStorageService bean должен быть создан")
                .isNotNull();

        // Создаём имитацию Update с фото
        // ПРИМЕЧАНИЕ: В реальном сценарии Update приходит от Telegram API
        // Здесь мы тестируем инфраструктуру без реального фото
        var update = createUpdateWithPhoto();
        var command = telegramBot.mapToTelegramPhotoCommand(update);

        // Assert
        assertThat(command.attachments())
                .as("Команда должна содержать вложения")
                .hasSize(1);
        assertThat(command.attachments().get(0).type())
                .as("Тип вложения должен быть IMAGE")
                .isEqualTo(AttachmentType.IMAGE);
        assertThat(command.attachments().get(0).key())
                .as("Файл должен иметь ключ хранилища")
                .startsWith("photo/");

        // Проверяем что файл сохранён в MinIO
        String storageKey = command.attachments().get(0).key();
        assertThat(storageService.exists(storageKey))
                .as("Файл должен существовать в MinIO")
                .isTrue();

        log.info("Photo saved to MinIO: key={}, mimeType={}, size={}", 
                storageKey, 
                command.attachments().get(0).mimeType(),
                command.attachments().get(0).size());
        log.info("=== Photo upload test completed successfully ===");
    }

    /**
     * ФИНАЛЬНЫЙ ТЕСТ для Agent 1: Обработка PDF документа из Telegram и сохранение в MinIO.
     * 
     * <p>Для запуска этого теста необходимо:
     * <ol>
     *   <li>Запустить MinIO: docker-compose up minio</li>
     *   <li>Включить feature flags в TestPropertySource</li>
     *   <li>Отправить боту реальный PDF документ в Telegram</li>
     *   <li>Удалить @Disabled с этого теста</li>
     * </ol>
     */
    @Test
    @Disabled("ФИНАЛЬНЫЙ ТЕСТ: Требует MinIO и реальный PDF от пользователя")
    void documentCommand_savesPdfToMinioStorage() {
        log.info("=== Testing PDF document upload to MinIO ===");

        // Проверка что сервисы доступны
        TelegramFileService fileService = fileServiceProvider.getIfAvailable();
        FileStorageService storageService = storageServiceProvider.getIfAvailable();
        
        assertThat(fileService).as("TelegramFileService должен быть доступен").isNotNull();
        assertThat(storageService).as("FileStorageService должен быть доступен").isNotNull();

        // Создаём имитацию Update с PDF документом
        var update = createUpdateWithPdfDocument();
        var command = telegramBot.mapToTelegramDocumentCommand(update);

        // Assert
        assertThat(command.attachments())
                .as("Команда должна содержать вложения")
                .hasSize(1);
        assertThat(command.attachments().get(0).type())
                .as("Тип вложения должен быть PDF")
                .isEqualTo(AttachmentType.PDF);
        assertThat(command.attachments().get(0).key())
                .as("Файл должен иметь ключ хранилища")
                .startsWith("document/");

        // Проверяем что файл сохранён в MinIO
        String storageKey = command.attachments().get(0).key();
        assertThat(storageService.exists(storageKey))
                .as("Файл должен существовать в MinIO")
                .isTrue();

        log.info("PDF saved to MinIO: key={}, mimeType={}, size={}", 
                storageKey, 
                command.attachments().get(0).mimeType(),
                command.attachments().get(0).size());
        log.info("=== PDF document upload test completed successfully ===");
    }

    /**
     * Создаёт Update с фото для тестирования.
     * ПРИМЕЧАНИЕ: fileId должен быть реальным для работы с Telegram API.
     */
    private Update createUpdateWithPhoto() {
        var update = new Update();
        var msg = new Message();
        
        var from = new User();
        from.setId(adminTelegramId);
        from.setUserName("test-user");
        from.setFirstName("Test");
        
        var chat = new Chat();
        chat.setId(adminTelegramId);
        
        msg.setMessageId(1);
        msg.setChat(chat);
        msg.setFrom(from);
        msg.setCaption("Test photo caption");
        
        // PhotoSize - в реальном сценарии fileId приходит от Telegram API
        // Для тестирования с реальным API нужно использовать реальный fileId
        var photo = new PhotoSize();
        photo.setFileId("TEST_FILE_ID_PHOTO"); // Замените на реальный fileId
        photo.setFileUniqueId("unique_id");
        photo.setWidth(800);
        photo.setHeight(600);
        photo.setFileSize(50000);
        
        msg.setPhoto(List.of(photo));
        update.setMessage(msg);
        
        return update;
    }

    /**
     * Создаёт Update с PDF документом для тестирования.
     * ПРИМЕЧАНИЕ: fileId должен быть реальным для работы с Telegram API.
     */
    private Update createUpdateWithPdfDocument() {
        var update = new Update();
        var msg = new Message();
        
        var from = new User();
        from.setId(adminTelegramId);
        from.setUserName("test-user");
        from.setFirstName("Test");
        
        var chat = new Chat();
        chat.setId(adminTelegramId);
        
        msg.setMessageId(1);
        msg.setChat(chat);
        msg.setFrom(from);
        msg.setCaption("Test PDF document");
        
        // Document - в реальном сценарии fileId приходит от Telegram API
        var doc = new Document();
        doc.setFileId("TEST_FILE_ID_PDF"); // Замените на реальный fileId
        doc.setFileUniqueId("unique_id");
        doc.setFileName("test-document.pdf");
        doc.setMimeType("application/pdf");
        doc.setFileSize(100000L);
        
        msg.setDocument(doc);
        update.setMessage(msg);
        
        return update;
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(excludeName = {
            "org.springframework.ai.model.ollama.autoconfigure.OllamaAutoConfiguration",
            "org.springframework.ai.model.chat.memory.autoconfigure.ChatMemoryAutoConfiguration",
            "ru.girchev.aibot.ai.springai.config.SpringAIAutoConfig",
            "org.springframework.ai.model.openai.autoconfigure.OpenAiAutoConfiguration",
            "org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration",
            "org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration",
            "org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionAutoConfiguration",
            "org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration",
            "org.springframework.ai.model.openai.autoconfigure.OpenAiImageAutoConfiguration",
            "org.springframework.ai.model.openai.autoconfigure.OpenAiModerationAutoConfiguration",
            "ru.girchev.aibot.rest.config.RestAutoConfig",
            "ru.girchev.aibot.ui.config.UIAutoConfig"
    })
    static class TestConfig {
    }
}
