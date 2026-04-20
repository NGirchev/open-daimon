package io.github.ngirchev.opendaimon.it.manual;

import io.github.ngirchev.dotenv.DotEnvLoader;
import io.github.ngirchev.opendaimon.ai.springai.tool.WebTools;
import io.github.ngirchev.opendaimon.bulkhead.service.IWhitelistService;
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
import lombok.extern.slf4j.Slf4j;
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
import org.springframework.context.annotation.Primary;
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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.reset;

/**
 * Manual E2E regression test for gateway (passthrough, non-agent) path with
 * {@code z-ai/glm-4.5v} via OpenRouter.
 *
 * <p>Reproduces the production bug where the model, in combination with
 * {@code extra_body.reasoning.max_tokens} being sent by the gateway, emits:
 * <ol>
 *   <li>A {@code web_search} tool call with <b>empty arguments</b> → Spring AI
 *       calls {@code WebTools.webSearch(null)} → returns empty result → no real search.</li>
 *   <li>The final text answer contains reasoning preamble leaked from the thinking
 *       channel (e.g. "Я помогу вам найти… мне нужно выполнить поиск").</li>
 * </ol>
 *
 * <p>Uses {@link MockWebServer} for the Serper API — only {@code OPENROUTER_KEY}
 * is required (no {@code SERPER_KEY} needed). A {@link SpyWebTools} wrapper records
 * every {@code webSearch} invocation so assertions can inspect the query arguments.
 *
 * <p>The test MUST FAIL with the current code (reproducing the bug). After applying the
 * fix (disabling reasoning budget for {@code z-ai/glm-4.5v} in {@code application.yml}),
 * the test MUST PASS.
 *
 * <p>Run:
 * <pre>
 * ./mvnw -pl opendaimon-app -am test-compile failsafe:integration-test failsafe:verify \
 *   -Dit.test=GatewayPassthroughOpenRouterManualIT \
 *   -Dfailsafe.failIfNoSpecifiedTests=false \
 *   -Dmanual.openrouter.e2e=true
 * </pre>
 */
@Tag("manual")
@EnabledIfSystemProperty(named = "manual.openrouter.e2e", matches = "true")
@SpringBootTest(
        classes = GatewayPassthroughOpenRouterManualIT.TestConfig.class,
        properties = {
                "open-daimon.agent.enabled=false",
                // Allow VIP users to access paid models like z-ai/glm-4.5v.
                // The integration-test profile caps VIP at $0.50, which may exclude
                // glm-4.5v. Raise to $5.0 so the model selector can pick it.
                "open-daimon.common.chat-routing.VIP.max-price=5.0"
        }
)
@ActiveProfiles({"integration-test", "manual-openrouter-real-tools"})
@Slf4j
class GatewayPassthroughOpenRouterManualIT extends AbstractContainerIT {

    static {
        DotEnvLoader.loadDotEnv(Path.of("../.env"));
    }

    // Use an ID that is in telegram.access.ADMIN.ids of application-manual-openrouter.yaml
    // so TelegramUserPriorityService resolves the test user to ADMIN tier — matching the
    // real-prod scenario (admin with preferred model z-ai/glm-4.5v) that exhibits the bug.
    private static final Long TEST_CHAT_ID = 350009004L;

    /** Fake Serper search response — realistic enough for the model to produce an answer. */
    private static final String SERPER_RESPONSE_JSON = """
            {
              "organic": [
                {
                  "title": "Cyprus theatres 2026 — season schedule",
                  "link": "https://www.theatrescu.org/season/2026",
                  "snippet": "The Limassol Municipal Theatre presents three Russian-language productions in April 2026."
                },
                {
                  "title": "Russian drama in Cyprus — upcoming events",
                  "link": "https://ru.cyprusevents.com/drama/2026",
                  "snippet": "Russian-speaking theatre community in Cyprus announces upcoming shows in April and May 2026."
                }
              ]
            }
            """;

    // Started eagerly so TestConfig can read the port during context initialization.
    private static final MockWebServer mockWebServer = createMockWebServer();

    @Autowired
    private MessageTelegramCommandHandler messageHandler;

    @Autowired
    private TelegramUserRepository telegramUserRepository;

    @Autowired
    private ConversationThreadRepository threadRepository;

    @Autowired
    private OpenDaimonMessageRepository messageRepository;

    @Autowired
    private SpyWebTools spyWebTools;

    @Autowired
    private io.github.ngirchev.opendaimon.telegram.service.UserModelPreferenceService userModelPreferenceService;

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
        spyWebTools.clearCapturedQueries();

        // Pre-create the test user. TEST_CHAT_ID is in telegram.access.ADMIN.ids
        // (application-manual-openrouter.yaml) so TelegramUserPriorityService resolves
        // this user to ADMIN tier. Additionally, set preferred model so the factory
        // routes through FixedModelChatAICommand (with the model's own caps = WEB,
        // TOOL_CALLING), matching the real-prod path where webEnabled=true.
        TelegramUser adminUser = new TelegramUser();
        adminUser.setTelegramId(TEST_CHAT_ID);
        adminUser.setUsername("gateway-passthrough-user-" + TEST_CHAT_ID);
        adminUser.setFirstName("Gateway");
        adminUser.setLastName("Passthrough");
        adminUser.setLanguageCode("ru");
        adminUser.setIsAdmin(true);
        adminUser.setIsPremium(true);
        adminUser.setIsBlocked(false);
        adminUser.setCreatedAt(java.time.OffsetDateTime.now());
        adminUser.setUpdatedAt(java.time.OffsetDateTime.now());
        adminUser.setLastActivityAt(java.time.OffsetDateTime.now());
        TelegramUser savedUser = telegramUserRepository.save(adminUser);

        // Pin z-ai/glm-4.5v as the preferred model — this is the real-prod path that
        // triggers the empty-tool_call bug (FixedModelChatAICommand with caps=WEB).
        userModelPreferenceService.setPreferredModel(savedUser.getId(), "z-ai/glm-4.5v");

        reset(telegramBot);
        doNothing().when(telegramBot).showTyping(anyLong());
        doNothing().when(telegramBot).sendMessage(anyLong(), anyString(), any(), any(ReplyKeyboard.class));
        doNothing().when(telegramBot).sendMessage(anyLong(), anyString(), any());
        doNothing().when(telegramBot).sendErrorMessage(anyLong(), anyString(), any());
    }

    // ── W1: gateway passthrough — web_search called with non-empty query ────

    /**
     * W1: When the user asks a question that requires web search (user is REGULAR,
     * model has WEB capability), the gateway must pass a <b>non-blank</b> query to
     * {@code WebTools.webSearch}.
     *
     * <p>Before the fix: {@code z-ai/glm-4.5v} emits {@code web_search({})} with
     * empty args (because the reasoning budget causes it to emit a structural tool
     * call before forming the query). Spring AI calls {@code webSearch(null)}.
     * The captured query list contains only {@code null} or blank entries → assertion FAILS.
     *
     * <p>After the fix ({@code max-reasoning-tokens: 0} on the model config):
     * the model emits a proper {@code web_search("Какие спектакли…")} call.
     * The captured queries contain at least one non-blank entry → assertion PASSES.
     */
    @Test
    @Timeout(3 * 60)
    @DisplayName("W1: gateway passthrough — web_search invoked with non-empty query for current-events prompt")
    void shouldCallWebSearchWithNonEmptyQueryWhenAskedForCurrentEvents() {
        TelegramCommand command = createMessageCommand(
                TEST_CHAT_ID,
                1,
                "Какие спектакли на русском языке будут на Кипре в ближайшее время"
        );

        messageHandler.handle(command);

        // Log all captured web_search invocations for diagnostics
        List<String> capturedQueries = spyWebTools.getCapturedQueries();
        log.info("W1: captured webSearch queries ({}): {}", capturedQueries.size(), capturedQueries);

        TelegramUser user = telegramUserRepository.findByTelegramId(TEST_CHAT_ID)
                .orElseThrow(() -> new IllegalStateException("Telegram user should be created"));

        ConversationThread thread = threadRepository.findMostRecentActiveThread(user)
                .orElseThrow(() -> new IllegalStateException("Active thread should exist"));

        String finalReply = latestAssistantReply(thread);
        log.info("W1: final reply ({}): {}", finalReply.length(), finalReply);

        // Primary assertion: at least one webSearch call with a non-blank query.
        // Fails before the fix (all captured queries are null/blank).
        assertThat(capturedQueries)
                .as("Gateway must invoke webSearch with at least one non-blank query. "
                        + "Captured queries: " + capturedQueries + ". "
                        + "Likely cause: model emitting empty tool_call args due to reasoning budget leak.")
                .anyMatch(q -> q != null && !q.isBlank());

        // Secondary assertion: final answer must not contain reasoning preamble
        // leaked from the thinking channel into main text.
        assertThat(finalReply)
                .as("Final answer must not contain reasoning preamble leaked from the thinking channel")
                .doesNotContainIgnoringCase("Я помогу вам найти")
                .doesNotContainIgnoringCase("I will help you find")
                .doesNotContainIgnoringCase("мне нужно выполнить поиск")
                .doesNotContainIgnoringCase("need to perform a search");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private TelegramCommand createMessageCommand(Long chatId, int messageId, String text) {
        Update update = new Update();

        User from = new User();
        from.setId(chatId);
        from.setUserName("gateway-passthrough-user-" + chatId);
        from.setFirstName("Gateway");
        from.setLastName("Passthrough");
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
                .as("Assistant message should be saved to DB")
                .isNotEmpty();
        return assistantMessages.getLast().getContent();
    }

    // ── MockWebServer ─────────────────────────────────────────────────────────

    private static MockWebServer createMockWebServer() {
        MockWebServer server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                // POST → Serper web_search endpoint
                return new MockResponse()
                        .setBody(SERPER_RESPONSE_JSON)
                        .addHeader("Content-Type", "application/json");
            }
        });
        try {
            server.start();
        } catch (IOException e) {
            throw new RuntimeException("Failed to start MockWebServer for Serper", e);
        }
        return server;
    }

    // ── Spring Boot test configuration ───────────────────────────────────────

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestConfig {

        /**
         * Allow-all whitelist so the FSM does not try to call Telegram's
         * {@code getChatMember} API on a mocked bot (which would NPE).
         * The whitelist check is irrelevant to the gateway/reasoning-budget bug
         * this test is covering.
         */
        @Bean
        @Primary
        public IWhitelistService allowAllWhitelistService() {
            return new IWhitelistService() {
                @Override
                public boolean isUserAllowed(Long userId) {
                    return true;
                }

                @Override
                public boolean checkUserInChannel(Long userId) {
                    return true;
                }

                @Override
                public boolean checkUserInChannel(Long userId, String channelId) {
                    return true;
                }

                @Override
                public void addToWhitelist(Long userId) {
                    // no-op in test
                }
            };
        }

        /**
         * {@link SpyWebTools} replaces the production {@link WebTools} bean.
         * Delegates all method calls to the real implementation but records
         * every {@code webSearch} query for post-call assertion.
         * Points the Serper URL at the local {@link MockWebServer} so no real
         * Serper API key is required for this test.
         */
        @Bean
        @Primary
        public SpyWebTools webTools() {
            String mockBaseUrl = "http://localhost:" + mockWebServer.getPort();
            WebClient webClient = WebClient.builder().build();
            return new SpyWebTools(webClient, "fake-serper-key", mockBaseUrl + "/search");
        }
    }

    // ── SpyWebTools ───────────────────────────────────────────────────────────

    /**
     * Instrumented subclass of {@link WebTools} that records every {@code webSearch}
     * query argument for test assertions.
     *
     * <p>This is the primary observable for the bug: before the fix, all captured
     * queries are {@code null} or blank (empty tool_call args from {@code z-ai/glm-4.5v}).
     * After the fix, at least one captured query is non-blank.
     */
    static class SpyWebTools extends WebTools {

        private final CopyOnWriteArrayList<String> capturedQueries = new CopyOnWriteArrayList<>();

        public SpyWebTools(WebClient webClient, String apiKey, String apiUrl) {
            super(webClient, apiKey, apiUrl);
        }

        @Override
        public SearchResult webSearch(String query) {
            capturedQueries.add(query);
            log.info("SpyWebTools.webSearch captured query=[{}]", query);
            return super.webSearch(query);
        }

        public List<String> getCapturedQueries() {
            // List.copyOf throws NPE on null elements; use ArrayList copy to preserve nulls
            // (null entries represent the buggy empty tool_call args from the model).
            return new java.util.ArrayList<>(capturedQueries);
        }

        public void clearCapturedQueries() {
            capturedQueries.clear();
        }
    }
}
