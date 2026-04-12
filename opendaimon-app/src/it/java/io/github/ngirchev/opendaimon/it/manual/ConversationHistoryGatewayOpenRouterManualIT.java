package io.github.ngirchev.opendaimon.it.manual;

import io.github.ngirchev.dotenv.DotEnvLoader;
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
import io.github.ngirchev.opendaimon.test.TestDatabaseConfiguration;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.telegram.telegrambots.meta.api.objects.Chat;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboard;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.reset;

/**
 * Manual E2E integration test for conversation history in gateway (non-agent) mode.
 *
 * <p>Agent mode is NOT enabled — requests go through {@code SpringAiGateway} with
 * {@code MessageChatMemoryAdvisor}. Verifies that text-only multi-turn conversations
 * retain context via {@code ChatMemory}.
 *
 * <p>Complements the vision-based multi-turn test in
 * {@link ObjectsImageVisionOpenRouterManualIT} with a pure text scenario.
 *
 * <p>Requires:
 * <ul>
 *   <li>{@code OPENROUTER_KEY} environment variable with a valid OpenRouter API key (set in .env)</li>
 * </ul>
 *
 * <p>Run explicitly:
 * <pre>
 * ./mvnw -pl opendaimon-app -am test-compile failsafe:integration-test failsafe:verify \
 *   -Dit.test=ConversationHistoryGatewayOpenRouterManualIT \
 *   -Dfailsafe.failIfNoSpecifiedTests=false \
 *   -Dmanual.openrouter.e2e=true
 * </pre>
 */
@Tag("manual")
@EnabledIfSystemProperty(named = "manual.openrouter.e2e", matches = "true")
@SpringBootTest(
        classes = ConversationHistoryGatewayOpenRouterManualIT.TestConfig.class,
        properties = "open-daimon.agent.enabled=false"
)
@ActiveProfiles({"integration-test", "manual-openrouter"})
@Import({
        TestDatabaseConfiguration.class
})
class ConversationHistoryGatewayOpenRouterManualIT {

    static {
        DotEnvLoader.loadDotEnv(Path.of("../.env"));
    }

    private static final Long TEST_CHAT_ID = 350009008L;

    private static final String SECRET_CODE = "PULSAR-3307-OMEGA";
    private static final String SECRET_NUMBER = "9156";

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
        DotEnvLoader.loadDotEnv(Path.of("../.env"));
        String openRouterKey = System.getProperty("OPENROUTER_KEY", System.getenv("OPENROUTER_KEY"));
        Assumptions.assumeTrue(
                openRouterKey != null && !openRouterKey.isBlank() && !openRouterKey.equals("sk-placeholder"),
                "Skipping manual test: OPENROUTER_KEY not set in .env or environment"
        );
    }

    @BeforeEach
    void setUpEach() throws TelegramApiException {
        messageRepository.deleteAll();
        threadRepository.deleteAll();
        telegramUserRepository.deleteAll();

        reset(telegramBot);
        doNothing().when(telegramBot).showTyping(anyLong());
        doNothing().when(telegramBot).sendMessage(anyLong(), anyString(), any(), any(ReplyKeyboard.class));
        doNothing().when(telegramBot).sendMessage(anyLong(), anyString(), any());
        doNothing().when(telegramBot).sendErrorMessage(anyLong(), anyString(), any());
    }

    // ==================== G1: Gateway 2-turn text history ====================

    @Test
    @Timeout(3 * 60)
    @DisplayName("G1: Gateway retains text conversation history across turns")
    void gateway_multiTurn_retainsHistory() {
        TelegramCommand firstCommand = createMessageCommand(
                TEST_CHAT_ID, 1,
                "Remember this secret code, I will ask you about it later: " + SECRET_CODE
        );
        messageHandler.handle(firstCommand);

        TelegramUser user = telegramUserRepository.findByTelegramId(TEST_CHAT_ID)
                .orElseThrow(() -> new IllegalStateException("Telegram user should be created"));
        ConversationThread thread = threadRepository.findMostRecentActiveThread(user)
                .orElseThrow(() -> new IllegalStateException("Active thread should exist"));

        assertThat(latestAssistantReply(thread))
                .as("First response should not be blank")
                .isNotBlank();

        TelegramCommand secondCommand = createMessageCommand(
                TEST_CHAT_ID, 2,
                "What was the secret code I told you? Reply with just the code."
        );
        messageHandler.handle(secondCommand);

        ConversationThread threadAfterFollowUp = threadRepository.findMostRecentActiveThread(user)
                .orElseThrow(() -> new IllegalStateException("Active thread should exist after follow-up"));

        assertThat(threadAfterFollowUp.getId())
                .as("Follow-up should stay in the same thread")
                .isEqualTo(thread.getId());

        String secondReply = latestAssistantReply(threadAfterFollowUp);
        assertThat(secondReply)
                .as("Gateway must recall the secret code from conversation history")
                .containsIgnoringCase(SECRET_CODE);

        assertThat(messageRepository.countByThreadAndRole(threadAfterFollowUp, MessageRole.USER))
                .as("Two user messages expected")
                .isEqualTo(2);
        assertThat(messageRepository.countByThreadAndRole(threadAfterFollowUp, MessageRole.ASSISTANT))
                .as("Two assistant messages expected")
                .isEqualTo(2);
    }

    // ==================== G2: Gateway 3-turn deep history ====================

    @Test
    @Timeout(5 * 60)
    @DisplayName("G2: Gateway retains deep history — third turn references fact from first turn")
    void gateway_threeTurns_deepHistory() {
        TelegramCommand turn1 = createMessageCommand(
                TEST_CHAT_ID, 1,
                "My lucky number is " + SECRET_NUMBER + ". Remember it."
        );
        messageHandler.handle(turn1);

        TelegramUser user = telegramUserRepository.findByTelegramId(TEST_CHAT_ID)
                .orElseThrow(() -> new IllegalStateException("Telegram user should be created"));
        ConversationThread thread = threadRepository.findMostRecentActiveThread(user)
                .orElseThrow(() -> new IllegalStateException("Active thread should exist"));

        assertThat(latestAssistantReply(thread))
                .as("Turn 1 response should not be blank")
                .isNotBlank();

        TelegramCommand turn2 = createMessageCommand(
                TEST_CHAT_ID, 2,
                "What is the capital of France?"
        );
        messageHandler.handle(turn2);

        assertThat(latestAssistantReply(thread))
                .as("Turn 2 should answer")
                .isNotBlank();

        TelegramCommand turn3 = createMessageCommand(
                TEST_CHAT_ID, 3,
                "What is my lucky number? Reply with just the number."
        );
        messageHandler.handle(turn3);

        String turn3Reply = latestAssistantReply(thread);
        assertThat(turn3Reply)
                .as("Gateway must recall the lucky number from turn 1 via deep conversation history")
                .contains(SECRET_NUMBER);

        assertThat(messageRepository.countByThreadAndRole(thread, MessageRole.USER))
                .as("Three user messages expected")
                .isEqualTo(3);
        assertThat(messageRepository.countByThreadAndRole(thread, MessageRole.ASSISTANT))
                .as("Three assistant messages expected")
                .isEqualTo(3);
    }

    // --- Helpers ---

    private TelegramCommand createMessageCommand(Long chatId, int messageId, String text) {
        Update update = new Update();

        User from = new User();
        from.setId(chatId);
        from.setUserName("gateway-history-user-" + chatId);
        from.setFirstName("Gateway");
        from.setLastName("History");
        from.setLanguageCode("en");

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
                List.of()
        );
        command.languageCode("en");
        return command;
    }

    private String latestAssistantReply(ConversationThread thread) {
        List<OpenDaimonMessage> assistantMessages = messageRepository
                .findByThreadAndRoleOrderBySequenceNumberAsc(thread, MessageRole.ASSISTANT);
        assertThat(assistantMessages)
                .as("Assistant message should be saved")
                .isNotEmpty();
        return assistantMessages.getLast().getContent();
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestConfig {
    }
}
