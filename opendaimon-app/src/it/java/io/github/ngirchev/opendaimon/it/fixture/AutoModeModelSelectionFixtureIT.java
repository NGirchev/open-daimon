package io.github.ngirchev.opendaimon.it.fixture;

import io.github.ngirchev.opendaimon.bulkhead.model.UserPriority;
import io.github.ngirchev.opendaimon.common.config.CoreCommonProperties;
import io.github.ngirchev.opendaimon.common.config.CoreFlywayConfig;
import io.github.ngirchev.opendaimon.common.config.CoreJpaConfig;
import io.github.ngirchev.opendaimon.common.repository.OpenDaimonMessageRepository;
import io.github.ngirchev.opendaimon.it.ITTestConfiguration;
import io.github.ngirchev.opendaimon.it.fixture.config.TelegramFixtureConfig;
import io.github.ngirchev.opendaimon.telegram.command.TelegramCommand;
import io.github.ngirchev.opendaimon.telegram.command.TelegramCommandType;
import io.github.ngirchev.opendaimon.telegram.command.handler.impl.MessageTelegramCommandHandler;
import io.github.ngirchev.opendaimon.telegram.config.TelegramFlywayConfig;
import io.github.ngirchev.opendaimon.telegram.config.TelegramJpaConfig;
import io.github.ngirchev.opendaimon.test.TestDatabaseConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.telegram.telegrambots.meta.api.objects.Chat;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fixture test for use case: auto-mode-model-selection.md
 *
 * <p>Verifies happy paths:
 * <ul>
 *   <li>ADMIN user in AUTO mode — message processed, AI responds</li>
 *   <li>VIP user in AUTO mode — message processed, AI responds</li>
 *   <li>REGULAR user in AUTO mode — message processed with free models, AI responds</li>
 * </ul>
 *
 * <p>Each tier has different chat-routing properties (maxPrice, requiredCapabilities)
 * configured in application-integration-test.yaml. This test verifies that the full
 * pipeline (factory → gateway → response) works for all three tiers without errors.
 *
 * @see <a href="docs/usecases/auto-mode-model-selection.md">auto-mode-model-selection.md</a>
 */
@Tag("fixture")
@SpringBootTest(
        classes = ITTestConfiguration.class,
        properties = {
                "spring.main.banner-mode=off",
                "spring.autoconfigure.exclude=" +
                        "io.github.ngirchev.opendaimon.common.config.CoreAutoConfig," +
                        "io.github.ngirchev.opendaimon.bulkhead.config.BulkHeadAutoConfig," +
                        "io.github.ngirchev.opendaimon.telegram.config.TelegramAutoConfig," +
                        "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration," +
                        "org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration," +
                        "org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration," +
                        "org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionAutoConfiguration," +
                        "org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration," +
                        "org.springframework.ai.model.openai.autoconfigure.OpenAiImageAutoConfiguration," +
                        "org.springframework.ai.model.openai.autoconfigure.OpenAiModerationAutoConfiguration"
        }
)
@ActiveProfiles("integration-test")
@EnableConfigurationProperties(CoreCommonProperties.class)
@Import({
        TestDatabaseConfiguration.class,
        CoreFlywayConfig.class,
        CoreJpaConfig.class,
        TelegramFlywayConfig.class,
        TelegramJpaConfig.class,
        TelegramFixtureConfig.class
})
class AutoModeModelSelectionFixtureIT {

    @Autowired
    TelegramFixtureConfig.RecordingTelegramBot telegramBot;

    @Autowired
    MessageTelegramCommandHandler messageHandler;

    @Autowired
    OpenDaimonMessageRepository messageRepository;

    @Autowired
    AtomicReference<UserPriority> userPriorityRef;

    @BeforeEach
    void setUp() {
        telegramBot.clearMessages();
        userPriorityRef.set(UserPriority.REGULAR);
    }

    @Test
    @DisplayName("ADMIN user in AUTO mode — full capability access, gets AI response")
    void adminUser_autoMode_getsResponse() {
        userPriorityRef.set(UserPriority.ADMIN);

        var command = createMessageCommand(350002001L, "Summarize recent news about AI");
        messageHandler.handle(command);

        assertThat(telegramBot.sentMessages())
                .as("ADMIN should receive AI response in AUTO mode")
                .isNotEmpty();
        assertThat(telegramBot.sentMessages().getLast()).isNotBlank();
    }

    @Test
    @DisplayName("VIP user in AUTO mode — CHAT capability, gets AI response")
    void vipUser_autoMode_getsResponse() {
        userPriorityRef.set(UserPriority.VIP);

        var command = createMessageCommand(350002002L, "What is the capital of France?");
        messageHandler.handle(command);

        assertThat(telegramBot.sentMessages())
                .as("VIP should receive AI response in AUTO mode")
                .isNotEmpty();
        assertThat(telegramBot.sentMessages().getLast()).isNotBlank();
    }

    @Test
    @DisplayName("REGULAR user in AUTO mode — free models only (maxPrice=0.0), gets AI response")
    void regularUser_autoMode_getsResponse() {
        userPriorityRef.set(UserPriority.REGULAR);

        var command = createMessageCommand(350002003L, "Hello, how are you?");
        messageHandler.handle(command);

        assertThat(telegramBot.sentMessages())
                .as("REGULAR should receive AI response with free models")
                .isNotEmpty();
        assertThat(telegramBot.sentMessages().getLast()).isNotBlank();
    }

    private TelegramCommand createMessageCommand(Long chatId, String text) {
        var update = new Update();

        var from = new User();
        from.setId(chatId);
        from.setUserName("fixture-model-" + chatId);
        from.setFirstName("Model");
        from.setLastName("Test");
        from.setLanguageCode("en");

        var msg = new Message();
        msg.setMessageId(1);
        var chat = new Chat();
        chat.setId(chatId);
        msg.setChat(chat);
        msg.setText(text);
        msg.setFrom(from);
        update.setMessage(msg);

        var command = new TelegramCommand(
                null,
                msg.getChatId(),
                new TelegramCommandType(TelegramCommand.MESSAGE),
                update,
                text
        );
        command.stream(false);
        command.languageCode("en");
        return command;
    }
}
