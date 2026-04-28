package io.github.ngirchev.opendaimon.it.manual;

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
import io.github.ngirchev.opendaimon.it.manual.config.OllamaSimpleManualTestConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.telegram.telegrambots.meta.api.objects.Chat;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboard;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.reset;

/**
 * Manual E2E integration test for conversation history in gateway (non-agent) mode
 * with local Ollama.
 *
 * <p>Agent mode is NOT enabled — requests go through {@code SpringAiGateway} with
 * {@code MessageChatMemoryAdvisor}. Verifies that text-only multi-turn conversations
 * retain context via {@code ChatMemory}.
 *
 * <p>Requires local Ollama with {@code qwen2.5:3b} and {@code nomic-embed-text:v1.5}.
 *
 * <p>Run explicitly:
 * <pre>
 * ./mvnw -pl opendaimon-app -am test-compile failsafe:integration-test failsafe:verify \
 *   -Dit.test=ConversationHistoryGatewayOllamaManualIT \
 *   -Dfailsafe.failIfNoSpecifiedTests=false \
 *   -Dmanual.ollama.e2e=true
 * </pre>
 */
@Tag("manual")
@EnabledIfSystemProperty(named = "manual.ollama.e2e", matches = "true")
@SpringBootTest(
        classes = OllamaSimpleManualTestConfig.class,
        properties = "open-daimon.agent.enabled=false"
)
@ActiveProfiles({"integration-test", "manual-ollama"})
class ConversationHistoryGatewayOllamaManualIT extends AbstractContainerIT {

    private static final Long TEST_CHAT_ID = 350009008L;
    private static final Duration OLLAMA_TIMEOUT = Duration.ofSeconds(5);
    private static final String CHAT_MODEL_PROPERTY = "manual.ollama.chat-model";
    private static final String DEFAULT_CHAT_MODEL = "qwen2.5:3b";
    private static final String CHAT_MODEL = System.getProperty(CHAT_MODEL_PROPERTY, DEFAULT_CHAT_MODEL);
    private static final List<String> REQUIRED_OLLAMA_MODELS = Stream.of(CHAT_MODEL, "nomic-embed-text:v1.5")
            .distinct()
            .toList();

    private static final String SECRET_CODE = "HELIX-6620-QUASAR";
    private static final String SECRET_NUMBER = "3847";

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
    static void checkOllama() {
        requireLocalOllamaWithModels();
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
    @DisplayName("G1-Ollama: Gateway retains text conversation history across turns")
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
    @DisplayName("G2-Ollama: Gateway retains deep history — third turn references fact from first turn")
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
                List.of()
        );
        command.languageCode("ru");
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

    static void requireLocalOllamaWithModels() {
        String baseUrl = resolveOllamaBaseUrl();
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(OLLAMA_TIMEOUT)
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .GET()
                .timeout(OLLAMA_TIMEOUT)
                .uri(URI.create(baseUrl + "/api/tags"))
                .build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            boolean statusOk = response.statusCode() == 200;
            boolean modelsPresent = REQUIRED_OLLAMA_MODELS.stream().allMatch(response.body()::contains);
            Assumptions.assumeTrue(statusOk && modelsPresent,
                    "Skipping: Ollama/models unavailable at " + baseUrl + ". Required: " + REQUIRED_OLLAMA_MODELS);
        } catch (Exception ex) {
            Assumptions.assumeTrue(false,
                    "Skipping: cannot connect to Ollama at " + baseUrl + ". " + ex.getMessage());
        }
    }

    private static String resolveOllamaBaseUrl() {
        String baseUrl = System.getenv("OLLAMA_BASE_URL");
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "http://localhost:11434";
        }
        if (baseUrl.endsWith("/")) {
            return baseUrl.substring(0, baseUrl.length() - 1);
        }
        return baseUrl;
    }
}
