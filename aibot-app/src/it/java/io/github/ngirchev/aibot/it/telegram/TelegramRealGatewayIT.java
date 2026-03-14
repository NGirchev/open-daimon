package io.github.ngirchev.aibot.it.telegram;

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
import io.github.ngirchev.aibot.common.model.AttachmentType;
import io.github.ngirchev.aibot.common.storage.service.FileStorageService;
import io.github.ngirchev.aibot.telegram.service.TelegramFileService;
import io.github.ngirchev.aibot.common.config.CoreFlywayConfig;
import io.github.ngirchev.aibot.common.config.CoreJpaConfig;
import io.github.ngirchev.aibot.common.repository.AIBotMessageRepository;
import io.github.ngirchev.aibot.telegram.TelegramBot;
import io.github.ngirchev.aibot.telegram.command.TelegramCommand;
import io.github.ngirchev.aibot.telegram.command.TelegramCommandType;
import io.github.ngirchev.aibot.telegram.command.handler.impl.MessageTelegramCommandHandler;
import io.github.ngirchev.aibot.telegram.config.TelegramFlywayConfig;
import io.github.ngirchev.aibot.telegram.config.TelegramJpaConfig;
import io.github.ngirchev.aibot.test.TestDatabaseConfiguration;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for Telegram module with real Telegram Bot API.
 *
 * <p><b>Goal:</b> Test aibot-telegram module end-to-end without mocks —
 * real beans, real DB, real Telegram API.
 *
 * <p>This test verifies TelegramBot with real Telegram API.
 * Test is disabled by default (@Disabled) as it requires real credentials.
 *
 * <p>To run the test:
 * <ol>
 *   <li>Ensure .env contains TELEGRAM_TOKEN, TELEGRAM_USERNAME and ADMIN_TELEGRAM_ID</li>
 *   <li>Remove @Disabled from the test or the whole class</li>
 *   <li>Run the test</li>
 * </ol>
 */
@Slf4j
@Disabled("Requires real Telegram credentials. Remove @Disabled for local run.")
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
        "ai-bot.telegram.max-message-length=4096",
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
     * Test sending a message via real Telegram API.
     * <p>
     * To run, set env var TEST_TELEGRAM_CHAT_ID to your chat ID (e.g. from @userinfobot in Telegram).
     */
    @Test
    void messageCommand_sendsRealTelegramMessage() {
        // Arrange
        assertThat(telegramBot.getBotToken())
                .as("TELEGRAM_TOKEN must be set")
                .isNotBlank();
        assertThat(telegramBot.getBotUsername())
                .as("TELEGRAM_USERNAME must be set")
                .isNotBlank();

        log.info("=== Testing real Telegram message command ===");
        log.info("Bot: @{}", telegramBot.getBotUsername());
        log.info("Chat ID: {}", adminTelegramId);

        // Create mock Update with real chatId
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
        msg.setText("Test message from integration test");
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

        // Act - run handling; this sends a real message to Telegram
        messageHandler.handle(command);

        // Assert
        assertThat(messageRepository.count())
                .as("Messages must be saved to DB")
                .isGreaterThanOrEqualTo(2);
        
        log.info("=== Real Telegram message test completed successfully ===");
    }

    /**
     * Test direct message send via TelegramBot.sendMessage()
     */
    @Test
    void directSendMessage_sendsRealTelegramMessage() throws TelegramApiException {
        // Arrange
        assertThat(telegramBot.getBotToken())
                .as("TELEGRAM_TOKEN must be set")
                .isNotBlank();

        log.info("=== Testing direct Telegram send message ===");
        log.info("Bot: @{}", telegramBot.getBotUsername());
        log.info("Chat ID: {}", adminTelegramId);

        // Act - send direct message via Telegram API
        telegramBot.sendMessage(adminTelegramId, "🧪 Direct test message from TelegramRealGatewayIT");
        
        log.info("=== Direct send message test completed successfully ===");
    }

    // ==================== FILE UPLOAD TESTS ====================
    //
    // NOTE: The following tests require feature flags:
    //   - ai-bot.common.storage.enabled=true
    //   - ai-bot.telegram.file-upload.enabled=true
    // And MinIO server running (docker-compose up minio).
    //
    // Run these tests manually after setting up the environment.
    // ===========================================================

    @Autowired(required = false)
    private ObjectProvider<TelegramFileService> fileServiceProvider;

    @Autowired(required = false)
    private ObjectProvider<FileStorageService> storageServiceProvider;

    /**
     * Test: process photo from Telegram and save to MinIO.
     *
     * <p>To run this test:
     * <ol>
     *   <li>Start MinIO: docker-compose up minio</li>
     *   <li>Enable feature flags in TestPropertySource:
     *       <ul>
     *         <li>ai-bot.common.storage.enabled=true</li>
     *         <li>ai-bot.telegram.file-upload.enabled=true</li>
     *       </ul>
     *   </li>
     *   <li>Send a real photo to the bot in Telegram</li>
     *   <li>Remove @Disabled from this test</li>
     * </ol>
     *
     * <p>Test demonstrates full flow:
     * <ol>
     *   <li>TelegramBot receives Update with photo</li>
     *   <li>TelegramFileService downloads photo via Telegram API</li>
     *   <li>MinioFileStorageService saves file to bucket</li>
     *   <li>TelegramCommand contains Attachment with metadata</li>
     * </ol>
     */
    @Test
    @Disabled("Requires MinIO and a real photo from user")
    void photoCommand_savesToMinioStorage() {
        log.info("=== Testing photo upload to MinIO ===");
        
        // Ensure services are available
        assertThat(fileServiceProvider)
                .as("TelegramFileService must be available (enable ai-bot.telegram.file-upload.enabled=true)")
                .isNotNull();
        assertThat(storageServiceProvider)
                .as("FileStorageService must be available (enable ai-bot.common.storage.enabled=true)")
                .isNotNull();

        TelegramFileService fileService = fileServiceProvider.getIfAvailable();
        FileStorageService storageService = storageServiceProvider.getIfAvailable();
        
        assertThat(fileService)
                .as("TelegramFileService bean must be created")
                .isNotNull();
        assertThat(storageService)
                .as("FileStorageService bean must be created")
                .isNotNull();

        // Create mock Update with photo (in real scenario Update comes from Telegram API)
        var update = createUpdateWithPhoto();
        var command = telegramBot.mapToTelegramPhotoCommand(update);

        // Assert
        assertThat(command.attachments())
                .as("Command must contain attachments")
                .hasSize(1);
        assertThat(command.attachments().get(0).type())
                .as("Attachment type must be IMAGE")
                .isEqualTo(AttachmentType.IMAGE);
        assertThat(command.attachments().get(0).key())
                .as("File must have storage key")
                .startsWith("photo/");

        // Verify file is saved in MinIO
        String storageKey = command.attachments().get(0).key();
        assertThat(storageService.exists(storageKey))
                .as("File must exist in MinIO")
                .isTrue();

        log.info("Photo saved to MinIO: key={}, mimeType={}, size={}", 
                storageKey, 
                command.attachments().get(0).mimeType(),
                command.attachments().get(0).size());
        log.info("=== Photo upload test completed successfully ===");
    }

    /**
     * Test: process PDF document from Telegram and save to MinIO.
     *
     * <p>To run this test:
     * <ol>
     *   <li>Start MinIO: docker-compose up minio</li>
     *   <li>Enable feature flags in TestPropertySource</li>
     *   <li>Send a real PDF document to the bot in Telegram</li>
     *   <li>Remove @Disabled from this test</li>
     * </ol>
     */
    @Test
    @Disabled("Requires MinIO and a real PDF from user")
    void documentCommand_savesPdfToMinioStorage() {
        log.info("=== Testing PDF document upload to MinIO ===");

        // Ensure services are available
        TelegramFileService fileService = fileServiceProvider.getIfAvailable();
        FileStorageService storageService = storageServiceProvider.getIfAvailable();
        
        assertThat(fileService).as("TelegramFileService must be available").isNotNull();
        assertThat(storageService).as("FileStorageService must be available").isNotNull();

        // Create mock Update with PDF document
        var update = createUpdateWithPdfDocument();
        var command = telegramBot.mapToTelegramDocumentCommand(update);

        // Assert
        assertThat(command.attachments())
                .as("Command must contain attachments")
                .hasSize(1);
        assertThat(command.attachments().get(0).type())
                .as("Attachment type must be PDF")
                .isEqualTo(AttachmentType.PDF);
        assertThat(command.attachments().get(0).key())
                .as("File must have storage key")
                .startsWith("document/");

        // Verify file is saved in MinIO
        String storageKey = command.attachments().get(0).key();
        assertThat(storageService.exists(storageKey))
                .as("File must exist in MinIO")
                .isTrue();

        log.info("PDF saved to MinIO: key={}, mimeType={}, size={}", 
                storageKey, 
                command.attachments().get(0).mimeType(),
                command.attachments().get(0).size());
        log.info("=== PDF document upload test completed successfully ===");
    }

    /**
     * Creates Update with photo for testing.
     * NOTE: fileId must be real when using Telegram API.
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
        
        // PhotoSize - in real scenario fileId comes from Telegram API; use real fileId for real API testing
        var photo = new PhotoSize();
        photo.setFileId("TEST_FILE_ID_PHOTO"); // Replace with real fileId for real API
        photo.setFileUniqueId("unique_id");
        photo.setWidth(800);
        photo.setHeight(600);
        photo.setFileSize(50000);
        
        msg.setPhoto(List.of(photo));
        update.setMessage(msg);
        
        return update;
    }

    /**
     * Creates Update with PDF document for testing.
     * NOTE: fileId must be real when using Telegram API.
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
        
        // Document - in real scenario fileId comes from Telegram API
        var doc = new Document();
        doc.setFileId("TEST_FILE_ID_PDF"); // Replace with real fileId for real API
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
            "org.springframework.ai.model.chat.memory.autoconfigure.ChatMemoryAutoConfiguration",
            "io.github.ngirchev.aibot.ai.springai.config.SpringAIAutoConfig",
            "org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration",
            "org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration",
            "org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionAutoConfiguration",
            "org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration",
            "org.springframework.ai.model.openai.autoconfigure.OpenAiImageAutoConfiguration",
            "org.springframework.ai.model.openai.autoconfigure.OpenAiModerationAutoConfiguration",
            "io.github.ngirchev.aibot.rest.config.RestAutoConfig",
            "io.github.ngirchev.aibot.ui.config.UIAutoConfig"
    })
    static class TestConfig {
    }
}
