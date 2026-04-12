package io.github.ngirchev.opendaimon.it.manual;

import io.github.ngirchev.dotenv.DotEnvLoader;
import io.github.ngirchev.opendaimon.ai.springai.tool.HttpApiTool;
import io.github.ngirchev.opendaimon.ai.springai.tool.WebTools;
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
import io.github.ngirchev.opendaimon.telegram.service.TelegramUserService;
import io.github.ngirchev.opendaimon.bulkhead.model.UserPriority;
import io.github.ngirchev.opendaimon.test.TestDatabaseConfiguration;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.reactive.function.client.WebClient;
import org.telegram.telegrambots.meta.api.objects.Chat;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboard;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.reset;

/**
 * Manual E2E integration test for conversation history across multiple turns.
 *
 * <p>Verifies that the LLM receives prior conversation context when the user
 * sends a follow-up message in the same thread. Tests both agent (REACT/SIMPLE)
 * and gateway (non-agent) modes.
 *
 * <p>Test strategy: send a unique fact in message 1, then ask about it in
 * message 2. If conversation history is correctly passed to the LLM, the
 * second response will reference the fact. If not, it cannot.
 *
 * <p>Requires:
 * <ul>
 *   <li>{@code OPENROUTER_KEY} environment variable with a valid OpenRouter API key (set in .env)</li>
 * </ul>
 *
 * <p>Run explicitly:
 * <pre>
 * ./mvnw -pl opendaimon-app -am test-compile failsafe:integration-test failsafe:verify \
 *   -Dit.test=ConversationHistoryOpenRouterManualIT \
 *   -Dfailsafe.failIfNoSpecifiedTests=false \
 *   -Dmanual.openrouter.e2e=true
 * </pre>
 */
@Tag("manual")
@EnabledIfSystemProperty(named = "manual.openrouter.e2e", matches = "true")
@SpringBootTest(
        classes = ConversationHistoryOpenRouterManualIT.TestConfig.class,
        properties = {
                "open-daimon.agent.enabled=true",
                "open-daimon.agent.max-iterations=10",
                "open-daimon.agent.tools.http-api.enabled=true"
        }
)
@ActiveProfiles({"integration-test", "manual-openrouter"})
@Import({
        TestDatabaseConfiguration.class
})
class ConversationHistoryOpenRouterManualIT {

    static {
        DotEnvLoader.loadDotEnv(Path.of("../.env"));
    }

    /** ADMIN user — resolves to AUTO capability → REACT agent strategy. */
    private static final Long ADMIN_CHAT_ID = 350009010L;

    /** REGULAR user — resolves to CHAT-only capability → SIMPLE agent strategy. */
    private static final Long REGULAR_CHAT_ID = 350009012L;

    /**
     * Unique facts used in conversation history tests.
     * These are nonsensical so the model cannot guess them from training data.
     */
    private static final String SECRET_CODE = "ZEPHYR-4491-KRONOS";
    private static final String SECRET_CITY = "Luminara";
    private static final String SECRET_NUMBER = "7742";

    private static final MockWebServer mockWebServer = createMockWebServer();

    @Autowired
    private MessageTelegramCommandHandler messageHandler;

    @Autowired
    private TelegramUserRepository telegramUserRepository;

    @Autowired
    private TelegramUserService telegramUserService;

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

    @AfterAll
    static void tearDown() throws IOException {
        mockWebServer.shutdown();
    }

    @BeforeEach
    void setUpEach() throws TelegramApiException {
        messageRepository.deleteAll();
        threadRepository.deleteAll();
        telegramUserRepository.deleteAll();
        telegramUserService.ensureUserWithLevel(ADMIN_CHAT_ID, UserPriority.ADMIN);
        telegramUserService.ensureUserWithLevel(REGULAR_CHAT_ID, UserPriority.REGULAR);

        reset(telegramBot);
        doNothing().when(telegramBot).showTyping(anyLong());
        doNothing().when(telegramBot).sendMessage(anyLong(), anyString(), any(), any(ReplyKeyboard.class));
        doNothing().when(telegramBot).sendMessage(anyLong(), anyString(), any());
        doNothing().when(telegramBot).sendErrorMessage(anyLong(), anyString(), any());
    }

    // ==================== H1: Agent REACT multi-turn history ====================

    @Test
    @Timeout(3 * 60)
    @DisplayName("H1: REACT agent retains conversation history across turns — follow-up references prior fact")
    void admin_agentReact_multiTurn_retainsHistory() {
        // Turn 1: Tell the agent a unique secret code
        TelegramCommand firstCommand = createMessageCommand(
                ADMIN_CHAT_ID,
                1,
                "Remember this secret code, I will ask you about it later: " + SECRET_CODE
        );
        messageHandler.handle(firstCommand);

        TelegramUser user = telegramUserRepository.findByTelegramId(ADMIN_CHAT_ID)
                .orElseThrow(() -> new IllegalStateException("Telegram user should be created"));
        ConversationThread thread = threadRepository.findMostRecentActiveThread(user)
                .orElseThrow(() -> new IllegalStateException("Active thread should exist"));

        String firstReply = latestAssistantReply(thread);
        assertThat(firstReply)
                .as("First response should acknowledge the secret code")
                .isNotBlank();

        // Turn 2: Ask for the code back — model must use conversation history
        TelegramCommand secondCommand = createMessageCommand(
                ADMIN_CHAT_ID,
                2,
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
                .as("REACT agent must recall the secret code from conversation history")
                .containsIgnoringCase(SECRET_CODE);

        // Verify message count: 2 user + 2 assistant = 4 total
        assertThat(messageRepository.countByThreadAndRole(threadAfterFollowUp, MessageRole.USER))
                .as("Two user messages expected in thread")
                .isEqualTo(2);
        assertThat(messageRepository.countByThreadAndRole(threadAfterFollowUp, MessageRole.ASSISTANT))
                .as("Two assistant messages expected in thread")
                .isEqualTo(2);
    }

    // ==================== H2: Agent SIMPLE multi-turn history ====================

    @Test
    @Timeout(3 * 60)
    @DisplayName("H2: SIMPLE agent retains conversation history across turns — follow-up references prior fact")
    void regular_agentSimple_multiTurn_retainsHistory() {
        // Turn 1: Tell the agent about a fictional city
        TelegramCommand firstCommand = createMessageCommand(
                REGULAR_CHAT_ID,
                1,
                "I was born in a city called " + SECRET_CITY + ". Please remember this."
        );
        messageHandler.handle(firstCommand);

        TelegramUser user = telegramUserRepository.findByTelegramId(REGULAR_CHAT_ID)
                .orElseThrow(() -> new IllegalStateException("Telegram user should be created"));
        ConversationThread thread = threadRepository.findMostRecentActiveThread(user)
                .orElseThrow(() -> new IllegalStateException("Active thread should exist"));

        String firstReply = latestAssistantReply(thread);
        assertThat(firstReply)
                .as("First response should not be blank")
                .isNotBlank();

        // Turn 2: Ask where the user was born
        TelegramCommand secondCommand = createMessageCommand(
                REGULAR_CHAT_ID,
                2,
                "Where was I born? Answer with the city name only."
        );
        messageHandler.handle(secondCommand);

        ConversationThread threadAfterFollowUp = threadRepository.findMostRecentActiveThread(user)
                .orElseThrow(() -> new IllegalStateException("Active thread should exist after follow-up"));

        assertThat(threadAfterFollowUp.getId())
                .as("Follow-up should stay in the same thread")
                .isEqualTo(thread.getId());

        String secondReply = latestAssistantReply(threadAfterFollowUp);
        assertThat(secondReply)
                .as("SIMPLE agent must recall the city from conversation history")
                .containsIgnoringCase(SECRET_CITY);

        assertThat(messageRepository.countByThreadAndRole(threadAfterFollowUp, MessageRole.USER))
                .as("Two user messages expected in thread")
                .isEqualTo(2);
        assertThat(messageRepository.countByThreadAndRole(threadAfterFollowUp, MessageRole.ASSISTANT))
                .as("Two assistant messages expected in thread")
                .isEqualTo(2);
    }

    // ==================== H3: REACT 3-turn deep history ====================

    @Test
    @Timeout(5 * 60)
    @DisplayName("H3: REACT agent retains deep history — third turn references fact from first turn")
    void admin_agentReact_threeTurns_deepHistory() {
        // Turn 1: Establish a fact
        TelegramCommand turn1 = createMessageCommand(
                ADMIN_CHAT_ID,
                1,
                "My lucky number is " + SECRET_NUMBER + ". Remember it."
        );
        messageHandler.handle(turn1);

        TelegramUser user = telegramUserRepository.findByTelegramId(ADMIN_CHAT_ID)
                .orElseThrow(() -> new IllegalStateException("Telegram user should be created"));
        ConversationThread thread = threadRepository.findMostRecentActiveThread(user)
                .orElseThrow(() -> new IllegalStateException("Active thread should exist"));

        assertThat(latestAssistantReply(thread))
                .as("Turn 1 response should not be blank")
                .isNotBlank();

        // Turn 2: Unrelated question to push the fact deeper in history
        TelegramCommand turn2 = createMessageCommand(
                ADMIN_CHAT_ID,
                2,
                "What is the capital of France?"
        );
        messageHandler.handle(turn2);

        String turn2Reply = latestAssistantReply(thread);
        assertThat(turn2Reply)
                .as("Turn 2 should answer about Paris")
                .isNotBlank();

        // Turn 3: Ask about the fact from turn 1
        TelegramCommand turn3 = createMessageCommand(
                ADMIN_CHAT_ID,
                3,
                "What is my lucky number? Reply with just the number."
        );
        messageHandler.handle(turn3);

        String turn3Reply = latestAssistantReply(thread);
        assertThat(turn3Reply)
                .as("REACT agent must recall the lucky number from turn 1 via deep conversation history")
                .contains(SECRET_NUMBER);

        // Verify: 3 user + 3 assistant = 6 messages
        assertThat(messageRepository.countByThreadAndRole(thread, MessageRole.USER))
                .as("Three user messages expected in thread")
                .isEqualTo(3);
        assertThat(messageRepository.countByThreadAndRole(thread, MessageRole.ASSISTANT))
                .as("Three assistant messages expected in thread")
                .isEqualTo(3);
    }

    // ==================== H4: REACT multi-turn with tool use ====================

    @Test
    @Timeout(3 * 60)
    @DisplayName("H4: REACT agent retains history when tools were used in prior turn")
    void admin_agentReact_multiTurn_afterToolUse() {
        // Turn 1: Trigger a web_search via agent
        TelegramCommand firstCommand = createMessageCommand(
                ADMIN_CHAT_ID,
                1,
                "Search the internet for the latest Spring Boot version. " +
                "Also remember this code: " + SECRET_CODE
        );
        messageHandler.handle(firstCommand);

        TelegramUser user = telegramUserRepository.findByTelegramId(ADMIN_CHAT_ID)
                .orElseThrow(() -> new IllegalStateException("Telegram user should be created"));
        ConversationThread thread = threadRepository.findMostRecentActiveThread(user)
                .orElseThrow(() -> new IllegalStateException("Active thread should exist"));

        String firstReply = latestAssistantReply(thread);
        assertThat(firstReply)
                .as("First response should contain search results")
                .isNotBlank();

        // Turn 2: Ask about the code — model must remember despite tool-heavy first turn
        TelegramCommand secondCommand = createMessageCommand(
                ADMIN_CHAT_ID,
                2,
                "What was the code I asked you to remember? Reply with just the code."
        );
        messageHandler.handle(secondCommand);

        String secondReply = latestAssistantReply(thread);
        assertThat(secondReply)
                .as("REACT agent must recall the code even after a tool-heavy prior turn")
                .containsIgnoringCase(SECRET_CODE);
    }

    // ==================== H5: SIMPLE 3-turn deep history ====================

    @Test
    @Timeout(5 * 60)
    @DisplayName("H5: SIMPLE agent retains deep history — third turn references fact from first turn")
    void regular_agentSimple_threeTurns_deepHistory() {
        TelegramCommand turn1 = createMessageCommand(
                REGULAR_CHAT_ID,
                1,
                "My favorite color is turquoise. Remember it."
        );
        messageHandler.handle(turn1);

        TelegramUser user = telegramUserRepository.findByTelegramId(REGULAR_CHAT_ID)
                .orElseThrow(() -> new IllegalStateException("Telegram user should be created"));
        ConversationThread thread = threadRepository.findMostRecentActiveThread(user)
                .orElseThrow(() -> new IllegalStateException("Active thread should exist"));

        assertThat(latestAssistantReply(thread))
                .as("Turn 1 response should not be blank")
                .isNotBlank();

        TelegramCommand turn2 = createMessageCommand(
                REGULAR_CHAT_ID,
                2,
                "What is 2 + 2?"
        );
        messageHandler.handle(turn2);

        assertThat(latestAssistantReply(thread))
                .as("Turn 2 should answer")
                .isNotBlank();

        TelegramCommand turn3 = createMessageCommand(
                REGULAR_CHAT_ID,
                3,
                "What is my favorite color? Reply with just the color."
        );
        messageHandler.handle(turn3);

        String turn3Reply = latestAssistantReply(thread);
        assertThat(turn3Reply.toLowerCase())
                .as("SIMPLE agent must recall the color from turn 1 via deep conversation history")
                .contains("turquoise");

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
        from.setUserName("history-test-user-" + chatId);
        from.setFirstName("History");
        from.setLastName("Test");
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

    private static MockWebServer createMockWebServer() {
        MockWebServer server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                if ("POST".equals(request.getMethod())) {
                    return new MockResponse()
                            .setBody("""
                                    {
                                      "organic": [
                                        {
                                          "title": "Spring Boot 4.0 Released",
                                          "link": "https://spring.io/blog/spring-boot-4-0",
                                          "snippet": "Spring Boot 4.0 is the latest release with virtual threads."
                                        }
                                      ]
                                    }
                                    """)
                            .addHeader("Content-Type", "application/json");
                }
                return new MockResponse()
                        .setBody("<html><body><h1>Mock Page</h1></body></html>")
                        .addHeader("Content-Type", "text/html");
            }
        });
        try {
            server.start();
        } catch (IOException e) {
            throw new RuntimeException("Failed to start MockWebServer", e);
        }
        return server;
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestConfig {

        @Bean
        public WebTools webTools() {
            String mockBaseUrl = "http://localhost:" + mockWebServer.getPort();
            WebClient webClient = WebClient.builder().build();
            return new WebTools(webClient, "fake-serper-key", mockBaseUrl + "/search");
        }

        @Bean
        public HttpApiTool httpApiTool() {
            WebClient webClient = WebClient.builder()
                    .baseUrl("http://localhost:" + mockWebServer.getPort())
                    .build();
            return new HttpApiTool(webClient, Set.of("localhost")) {
                @Override
                public String httpGet(String url) {
                    try {
                        String response = webClient.get()
                                .uri(url)
                                .retrieve()
                                .bodyToMono(String.class)
                                .timeout(Duration.ofSeconds(10))
                                .block();
                        return response != null ? response : "";
                    } catch (Exception e) {
                        return "Error: " + e.getMessage();
                    }
                }
            };
        }
    }
}
