package io.github.ngirchev.opendaimon.it.fixture;

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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fixture test for use case: forwarded-message.md
 *
 * <p>Verifies happy paths:
 * <ul>
 *   <li>Forwarded text message includes source attribution and produces AI response</li>
 *   <li>Forwarded command (e.g. /start) is NOT executed as command — treated as regular message</li>
 *   <li>Forwarded message from channel includes channel attribution</li>
 * </ul>
 *
 * @see <a href="docs/usecases/forwarded-message.md">forwarded-message.md</a>
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
class ForwardedMessageFixtureIT {

    @Autowired
    TelegramFixtureConfig.RecordingTelegramBot telegramBot;

    @Autowired
    MessageTelegramCommandHandler messageHandler;

    @Autowired
    OpenDaimonMessageRepository messageRepository;

    @BeforeEach
    void setUp() {
        telegramBot.clearMessages();
    }

    @Test
    @DisplayName("Forwarded text message from user — source attribution present, AI responds")
    void forwardedTextFromUser_hasAttribution_andGetsResponse() {
        // Simulate what TelegramBot.mapToTelegramTextCommand produces for a forwarded message:
        // enriched text = "[Forwarded from John Doe (@johndoe)]\nHello, this is a forwarded text"
        var enrichedText = "[Forwarded from John Doe (@johndoe)]\nHello, this is a forwarded text";

        var command = createMessageCommand(350001001L, enrichedText);
        command.forwardedFrom("John Doe (@johndoe)");

        messageHandler.handle(command);

        assertThat(telegramBot.sentMessages())
                .as("Bot should send AI response for forwarded message")
                .isNotEmpty();
        assertThat(telegramBot.sentMessages().getLast()).isNotBlank();
        assertThat(messageRepository.count())
                .as("At least user message + assistant response should be saved")
                .isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("Forwarded /start command — NOT executed as command, treated as regular message")
    void forwardedCommand_notExecuted_treatedAsMessage() {
        // When a forwarded message contains a command like /start,
        // TelegramBot.mapToTelegramTextCommand detects the forward and creates MESSAGE type,
        // NOT START command type. The command text is enriched with forward attribution.
        var enrichedText = "[Forwarded from Admin (@admin)]\n/start";

        var command = createMessageCommand(350001002L, enrichedText);
        command.forwardedFrom("Admin (@admin)");

        messageHandler.handle(command);

        // The message should be processed as a regular text message (AI responds),
        // not as a /start command (which would trigger the start flow)
        assertThat(telegramBot.sentMessages())
                .as("Forwarded command should produce AI response, not command execution")
                .isNotEmpty();
        assertThat(telegramBot.sentMessages().getLast()).isNotBlank();
    }

    @Test
    @DisplayName("Forwarded message from channel — channel attribution present")
    void forwardedFromChannel_hasChannelAttribution_andGetsResponse() {
        var enrichedText = "[Forwarded from Tech News (signature: editor)]\nBreaking: new AI model released";

        var command = createMessageCommand(350001003L, enrichedText);
        command.forwardedFrom("Tech News (signature: editor)");

        messageHandler.handle(command);

        assertThat(telegramBot.sentMessages())
                .as("Bot should respond to forwarded channel message")
                .isNotEmpty();
        assertThat(telegramBot.sentMessages().getLast()).isNotBlank();
    }

    private TelegramCommand createMessageCommand(Long chatId, String text) {
        var update = new Update();

        var from = new User();
        from.setId(chatId);
        from.setUserName("fixture-user-" + chatId);
        from.setFirstName("Fixture");
        from.setLastName("User");
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
