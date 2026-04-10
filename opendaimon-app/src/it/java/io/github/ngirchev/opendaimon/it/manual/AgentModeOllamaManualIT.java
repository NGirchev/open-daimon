package io.github.ngirchev.opendaimon.it.manual;

import io.github.ngirchev.opendaimon.ai.springai.tool.WebTools;
import io.github.ngirchev.opendaimon.common.agent.AgentExecutor;
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
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.reset;

/**
 * Manual E2E integration test for agent mode with real Ollama.
 *
 * <p>Verifies all agent scenarios:
 * <ol>
 *   <li>ADMIN (AUTO capability) → REACT strategy with web tools</li>
 *   <li>REGULAR (CHAT-only capability) → SIMPLE strategy, no tools</li>
 *   <li>Agent response saved to DB and sent via Telegram handler</li>
 * </ol>
 *
 * <p>Uses MockWebServer for web tools (web_search, fetch_url) to avoid real HTTP calls.
 * Agent mode is enabled via test properties.
 *
 * <p>Run explicitly:
 * <pre>
 * ./mvnw -pl opendaimon-app -am test-compile failsafe:integration-test failsafe:verify \
 *   -Dit.test=AgentModeOllamaManualIT \
 *   -Dfailsafe.failIfNoSpecifiedTests=false \
 *   -Dmanual.ollama.e2e=true \
 *   -Dmanual.ollama.chat-model=qwen2.5:3b
 * </pre>
 */
@Tag("manual")
@EnabledIfSystemProperty(named = "manual.ollama.e2e", matches = "true")
@SpringBootTest(classes = AgentModeOllamaManualIT.TestConfig.class)
@ActiveProfiles({"integration-test", "manual-ollama"})
@Import({
        TestDatabaseConfiguration.class
})
class AgentModeOllamaManualIT {

    private static final Long ADMIN_CHAT_ID = 350009010L;
    private static final Long REGULAR_CHAT_ID = 350009011L;
    private static final Duration OLLAMA_TIMEOUT = Duration.ofSeconds(5);
    private static final String CHAT_MODEL_PROPERTY = "manual.ollama.chat-model";
    private static final String DEFAULT_CHAT_MODEL = "qwen2.5:3b";
    private static final String CHAT_MODEL = System.getProperty(CHAT_MODEL_PROPERTY, DEFAULT_CHAT_MODEL);
    private static final List<String> REQUIRED_OLLAMA_MODELS = Stream.of(CHAT_MODEL, "nomic-embed-text:v1.5")
            .distinct()
            .toList();

    private static final String SERPER_RESPONSE_JSON = """
            {
              "organic": [
                {
                  "title": "Spring Boot 4.0 Released",
                  "link": "https://spring.io/blog/spring-boot-4-0",
                  "snippet": "Spring Boot 4.0 is the latest release in 2026 with virtual threads support."
                }
              ]
            }
            """;

    static final AtomicBoolean WEB_SEARCH_CALLED = new AtomicBoolean(false);
    static final AtomicBoolean FETCH_URL_CALLED = new AtomicBoolean(false);
    static final AtomicInteger TOOL_CALL_COUNT = new AtomicInteger(0);

    private static final MockWebServer mockWebServer = createMockWebServer();

    @Autowired
    private MessageTelegramCommandHandler messageHandler;

    @Autowired
    private AgentExecutor agentExecutor;

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

    @AfterAll
    static void tearDown() throws IOException {
        mockWebServer.shutdown();
    }

    @BeforeEach
    void setUpEach() throws TelegramApiException {
        messageRepository.deleteAll();
        threadRepository.deleteAll();
        telegramUserRepository.deleteAll();
        WEB_SEARCH_CALLED.set(false);
        FETCH_URL_CALLED.set(false);
        TOOL_CALL_COUNT.set(0);

        reset(telegramBot);
        doNothing().when(telegramBot).showTyping(anyLong());
        doNothing().when(telegramBot).sendMessage(anyLong(), anyString(), any(), any(ReplyKeyboard.class));
        doNothing().when(telegramBot).sendMessage(anyLong(), anyString(), any());
        doNothing().when(telegramBot).sendErrorMessage(anyLong(), anyString(), any());
    }

    // --- Scenario 1: ADMIN with AUTO capability → REACT + web tools ---

    @Test
    @Timeout(3 * 60)
    @DisplayName("ADMIN: agent uses REACT strategy and invokes web_search tool")
    void admin_agentReact_invokesWebSearch() {
        TelegramCommand command = createMessageCommand(
                ADMIN_CHAT_ID,
                1,
                "Какая последняя версия Spring Boot вышла в 2026 году? Поищи в интернете."
        );

        messageHandler.handle(command);

        TelegramUser user = telegramUserRepository.findByTelegramId(ADMIN_CHAT_ID)
                .orElseThrow(() -> new IllegalStateException("Telegram user should be created"));

        ConversationThread thread = threadRepository.findMostRecentActiveThread(user)
                .orElseThrow(() -> new IllegalStateException("Active thread should exist"));

        String assistantReply = latestAssistantReply(thread);

        assertThat(assistantReply)
                .as("Agent should produce a non-blank response")
                .isNotBlank();

        assertThat(WEB_SEARCH_CALLED.get() || FETCH_URL_CALLED.get())
                .as("ADMIN (AUTO) agent should invoke at least one web tool via REACT strategy")
                .isTrue();

        assertThat(TOOL_CALL_COUNT.get())
                .as("REACT strategy should make at least one tool call")
                .isGreaterThanOrEqualTo(1);
    }

    // --- Scenario 2: REGULAR with CHAT-only capability → SIMPLE, no tools ---

    @Test
    @Timeout(3 * 60)
    @DisplayName("REGULAR: agent uses SIMPLE strategy without tools")
    void regular_agentSimple_noTools() {
        TelegramCommand command = createMessageCommand(
                REGULAR_CHAT_ID,
                2,
                "Привет, расскажи анекдот"
        );

        messageHandler.handle(command);

        TelegramUser user = telegramUserRepository.findByTelegramId(REGULAR_CHAT_ID)
                .orElseThrow(() -> new IllegalStateException("Telegram user should be created"));

        ConversationThread thread = threadRepository.findMostRecentActiveThread(user)
                .orElseThrow(() -> new IllegalStateException("Active thread should exist"));

        String assistantReply = latestAssistantReply(thread);

        assertThat(assistantReply)
                .as("SIMPLE agent should produce a non-blank response")
                .isNotBlank();

        assertThat(WEB_SEARCH_CALLED.get())
                .as("REGULAR (CHAT-only) should NOT invoke web_search")
                .isFalse();

        assertThat(FETCH_URL_CALLED.get())
                .as("REGULAR (CHAT-only) should NOT invoke fetch_url")
                .isFalse();
    }

    // --- Scenario 3: Agent response is persisted to DB ---

    @Test
    @Timeout(3 * 60)
    @DisplayName("Agent response saved to DB with correct structure")
    void agentResponse_persistedToDb() {
        TelegramCommand command = createMessageCommand(
                ADMIN_CHAT_ID,
                3,
                "Скажи одним словом: работает ли агент?"
        );

        messageHandler.handle(command);

        TelegramUser user = telegramUserRepository.findByTelegramId(ADMIN_CHAT_ID)
                .orElseThrow();

        ConversationThread thread = threadRepository.findMostRecentActiveThread(user)
                .orElseThrow();

        List<OpenDaimonMessage> userMessages = messageRepository
                .findByThreadAndRoleOrderBySequenceNumberAsc(thread, MessageRole.USER);
        List<OpenDaimonMessage> assistantMessages = messageRepository
                .findByThreadAndRoleOrderBySequenceNumberAsc(thread, MessageRole.ASSISTANT);

        assertThat(userMessages)
                .as("User message should be saved")
                .hasSize(1);

        assertThat(assistantMessages)
                .as("Assistant message should be saved")
                .hasSize(1);

        assertThat(assistantMessages.getFirst().getContent())
                .as("Assistant content should not be blank")
                .isNotBlank();
    }

    // --- Scenario 4: AgentExecutor bean is properly wired ---

    @Test
    @DisplayName("AgentExecutor is injected into application context")
    void agentExecutor_isWired() {
        assertThat(agentExecutor)
                .as("AgentExecutor should be available in the application context")
                .isNotNull();
    }

    // --- Helpers ---

    private TelegramCommand createMessageCommand(Long chatId, int messageId, String text) {
        Update update = new Update();

        User from = new User();
        from.setId(chatId);
        from.setUserName("manual-agent-user-" + chatId);
        from.setFirstName("Manual");
        from.setLastName("Agent");
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

    private static MockWebServer createMockWebServer() {
        MockWebServer server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                TOOL_CALL_COUNT.incrementAndGet();
                if ("POST".equals(request.getMethod())) {
                    WEB_SEARCH_CALLED.set(true);
                    return new MockResponse()
                            .setBody(SERPER_RESPONSE_JSON)
                            .addHeader("Content-Type", "application/json");
                }
                FETCH_URL_CALLED.set(true);
                return new MockResponse()
                        .setBody("<html><body><h1>Spring Boot 4.0</h1><p>Released March 2026.</p></body></html>")
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

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestConfig {

        @Bean
        public WebTools webTools() {
            String mockBaseUrl = "http://localhost:" + mockWebServer.getPort();
            WebClient webClient = WebClient.builder().build();
            return new WebTools(webClient, "fake-serper-key", mockBaseUrl + "/search");
        }
    }
}
