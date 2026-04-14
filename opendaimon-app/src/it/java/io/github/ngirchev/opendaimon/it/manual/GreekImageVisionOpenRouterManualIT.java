package io.github.ngirchev.opendaimon.it.manual;

import io.github.ngirchev.dotenv.DotEnvLoader;
import io.github.ngirchev.opendaimon.common.model.Attachment;
import io.github.ngirchev.opendaimon.common.model.AttachmentType;
import io.github.ngirchev.opendaimon.common.model.ConversationThread;
import io.github.ngirchev.opendaimon.common.model.MessageRole;
import io.github.ngirchev.opendaimon.common.model.OpenDaimonMessage;
import io.github.ngirchev.opendaimon.common.repository.ConversationThreadRepository;
import io.github.ngirchev.opendaimon.common.repository.OpenDaimonMessageRepository;
import io.github.ngirchev.opendaimon.telegram.TelegramBot;
import io.github.ngirchev.opendaimon.telegram.command.TelegramCommand;
import io.github.ngirchev.opendaimon.telegram.command.TelegramCommandType;
import io.github.ngirchev.opendaimon.telegram.command.handler.impl.MessageTelegramCommandHandler;
import io.github.ngirchev.opendaimon.telegram.model.TelegramUser;
import io.github.ngirchev.opendaimon.telegram.repository.TelegramUserRepository;
import io.github.ngirchev.opendaimon.telegram.service.TelegramBotRegistrar;
import io.github.ngirchev.opendaimon.test.AbstractContainerIT;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import io.github.ngirchev.opendaimon.it.manual.config.OpenRouterSimpleManualTestConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.telegram.telegrambots.meta.api.objects.Chat;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboard;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.reset;

/**
 * Manual E2E integration test for OpenRouter auto + JPEG image with Greek text.
 *
 * <p>Same scenario as {@link GreekImageVisionOllamaManualIT} but uses {@code openrouter/auto} model
 * via OpenRouter API instead of a local Ollama chat model. Embeddings are handled by
 * {@code intfloat/multilingual-e5-large} via OpenRouter — no local Ollama required.
 *
 * <p>The image contains the sentence "Θέλω τον σκύλο του αγοριού." (I want the boy's dog.)
 * with the word "αγοριού" highlighted in purple. The vision model reads the text directly
 * from the image; there is no RAG indexing for JPEG attachments.
 *
 * <p>Requires:
 * <ul>
 *   <li>{@code OPENROUTER_KEY} environment variable with a valid OpenRouter API key</li>
 * </ul>
 *
 * <p>Not intended for regular CI runs.
 * Run explicitly:
 * <pre>
 * OPENROUTER_KEY=sk-or-... ./mvnw -pl opendaimon-app -am clean test-compile \
 *   failsafe:integration-test failsafe:verify \
 *   -Dit.test=GreekImageVisionOpenRouterManualIT \
 *   -Dfailsafe.failIfNoSpecifiedTests=false \
 *   -Dmanual.ollama.e2e=true
 * </pre>
 */
@Tag("manual")
@EnabledIfSystemProperty(named = "manual.ollama.e2e", matches = "true")
@SpringBootTest(
        classes = OpenRouterSimpleManualTestConfig.class,
        properties = "open-daimon.agent.enabled=false"
)
@ActiveProfiles({"integration-test", "manual-openrouter"})
class GreekImageVisionOpenRouterManualIT extends AbstractContainerIT {

    static {
        DotEnvLoader.loadDotEnv(Path.of("../.env"));
    }

    private static final Long TEST_CHAT_ID = 350009010L;
    private static final String IMAGE_RESOURCE = "attachments/greek.jpeg";

    @Autowired
    private MessageTelegramCommandHandler messageHandler;

    @Autowired
    private TelegramUserRepository telegramUserRepository;

    @Autowired
    private ConversationThreadRepository threadRepository;

    @Autowired
    private OpenDaimonMessageRepository messageRepository;

    @MockitoBean
    private TelegramBotRegistrar telegramBotRegistrar;

    @MockitoBean
    private TelegramBot telegramBot;

    @BeforeAll
    static void requireOpenRouterKey() {
        String openRouterKey = System.getProperty("OPENROUTER_KEY", System.getenv("OPENROUTER_KEY"));
        Assumptions.assumeTrue(openRouterKey != null && !openRouterKey.isBlank() && !openRouterKey.equals("sk-placeholder"),
                "Skipping manual test: OPENROUTER_KEY not set in .env or environment");
    }

    @BeforeEach
    void setUp() throws TelegramApiException {
        messageRepository.deleteAll();
        threadRepository.deleteAll();
        telegramUserRepository.deleteAll();

        reset(telegramBot);
        doNothing().when(telegramBot).showTyping(anyLong());
        doNothing().when(telegramBot).sendMessage(anyLong(), anyString(), any(), any(ReplyKeyboard.class));
        doNothing().when(telegramBot).sendMessage(anyLong(), anyString(), any());
        doNothing().when(telegramBot).sendErrorMessage(anyLong(), anyString(), any());
    }

    /**
     * This test expects the following behavior:
     * 1. Send a Telegram message with a JPEG image attachment and ask the model to quote the text.
     * 2. The vision model reads the Greek text directly from the image (no RAG indexing for JPEG).
     * 3. Ask a follow-up to translate the text to Russian.
     * 4. The model uses the conversation context to produce the Russian translation.
     * !!! DO NOT CHANGE THE TEST BEHAVIOR
     * !!! DO NOT CHANGE THE PROMPT
     * !!! DO NOT USE FAKE MOCKS JUST TO FORCE THE RESULT
     */
    @Test
    @Timeout(3 * 60)
    @DisplayName("Manual E2E: OpenRouter auto + greek.jpeg — vision reads Greek text + translates")
    void greekImage_openRouterVision_thenTranslates() throws IOException {
        Attachment imageAttachment = loadImageAttachment();

        TelegramCommand firstCommand = createMessageCommand(
                TEST_CHAT_ID,
                1,
                "Что написано на картинке? Процитируй текст дословно.",
                List.of(imageAttachment)
        );

        messageHandler.handle(firstCommand);

        TelegramUser user = telegramUserRepository.findByTelegramId(TEST_CHAT_ID)
                .orElseThrow(() -> new IllegalStateException("Telegram user should be created"));

        ConversationThread thread = threadRepository.findMostRecentActiveThread(user)
                .orElseThrow(() -> new IllegalStateException("Active thread should exist"));

        String firstReply = latestAssistantReply(thread);
        assertThat(firstReply)
                .as("First answer should not be blank")
                .isNotBlank();
        assertThat(firstReply.toLowerCase())
                .as("Reply should contain Greek text from the image")
                .containsAnyOf("θέλω", "σκύλο", "αγοριού");

        TelegramCommand secondCommand = createMessageCommand(
                TEST_CHAT_ID,
                2,
                "Переведи на русский язык греческий текст, который был на картинке. Напиши полный перевод.",
                List.of()
        );

        messageHandler.handle(secondCommand);

        ConversationThread threadAfterFollowUp = threadRepository.findMostRecentActiveThread(user)
                .orElseThrow(() -> new IllegalStateException("Active thread should exist after follow-up"));
        assertThat(threadAfterFollowUp.getId())
                .as("Follow-up should stay in same thread")
                .isEqualTo(thread.getId());

        String secondReply = latestAssistantReply(threadAfterFollowUp);
        assertThat(secondReply)
                .as("Follow-up answer should not be blank")
                .isNotBlank();
        assertThat(secondReply.toLowerCase())
                .as("Follow-up should contain Russian translation")
                .containsAnyOf("хочу", "собак", "мальчик", "want", "dog", "boy");

        assertThat(messageRepository.countByThreadAndRole(threadAfterFollowUp, MessageRole.USER))
                .as("Two user messages expected in thread")
                .isEqualTo(2);
        assertThat(messageRepository.countByThreadAndRole(threadAfterFollowUp, MessageRole.ASSISTANT))
                .as("Two assistant messages expected in thread")
                .isEqualTo(2);
    }

    private Attachment loadImageAttachment() throws IOException {
        ClassPathResource resource = new ClassPathResource(IMAGE_RESOURCE);
        byte[] imageBytes = resource.getInputStream().readAllBytes();
        return new Attachment(
                "manual/" + IMAGE_RESOURCE,
                "image/jpeg",
                IMAGE_RESOURCE,
                imageBytes.length,
                AttachmentType.IMAGE,
                imageBytes
        );
    }

    private TelegramCommand createMessageCommand(Long chatId, int messageId, String text, List<Attachment> attachments) {
        Update update = new Update();

        User from = new User();
        from.setId(chatId);
        from.setUserName("manual-openrouter-user");
        from.setFirstName("Manual");
        from.setLastName("OpenRouter");
        from.setLanguageCode("ru");

        Message message = new Message();
        message.setMessageId(messageId);
        Chat chat = new Chat();
        chat.setId(chatId);
        message.setChat(chat);
        message.setFrom(from);
        message.setText(text);
        update.setMessage(message);

        TelegramCommand command = new TelegramCommand(
                null,
                chatId,
                new TelegramCommandType(TelegramCommand.MESSAGE),
                update,
                text,
                false,
                attachments
        );
        command.languageCode("ru");
        return command;
    }

    private String latestAssistantReply(ConversationThread thread) {
        List<OpenDaimonMessage> assistantMessages = messageRepository.findByThreadAndRoleOrderBySequenceNumberAsc(
                thread, MessageRole.ASSISTANT);
        assertThat(assistantMessages)
                .as("Assistant message should be saved")
                .isNotEmpty();
        return assistantMessages.getLast().getContent();
    }
}
