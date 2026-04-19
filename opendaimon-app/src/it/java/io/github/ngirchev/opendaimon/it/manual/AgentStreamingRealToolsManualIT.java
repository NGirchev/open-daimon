package io.github.ngirchev.opendaimon.it.manual;

import io.github.ngirchev.dotenv.DotEnvLoader;
import io.github.ngirchev.opendaimon.common.agent.AgentExecutor;
import io.github.ngirchev.opendaimon.common.agent.AgentRequest;
import io.github.ngirchev.opendaimon.common.agent.AgentStrategy;
import io.github.ngirchev.opendaimon.common.agent.AgentStreamEvent;
import io.github.ngirchev.opendaimon.common.ai.command.AICommand;
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
import io.github.ngirchev.opendaimon.telegram.service.UserModelPreferenceService;
import io.github.ngirchev.opendaimon.bulkhead.model.UserPriority;
import io.github.ngirchev.opendaimon.test.AbstractContainerIT;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.telegram.telegrambots.meta.api.objects.Chat;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboard;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.reset;

/**
 * Manual E2E test with REAL web tools (Serper + fetch_url) and real OpenRouter.
 *
 * <p>Uses explicit model {@code z-ai/glm-4.5v} (not {@code openrouter/auto}) to reproduce
 * production behavior where this model outputs raw XML {@code <tool_call>} tags in its
 * text responses.
 *
 * <p>Tests:
 * <ol>
 *   <li>R1: Agent stream with explicit model — verify no raw XML in events</li>
 *   <li>R2: Full E2E through Telegram handler — verify DB reply has no raw XML</li>
 *   <li>R3: SIMPLE strategy with explicit model — verify meaningful response</li>
 * </ol>
 *
 * <p>Requires both {@code OPENROUTER_KEY} and {@code SERPER_KEY} in .env.
 *
 * <p>Run:
 * <pre>
 * ./mvnw -pl opendaimon-app -am test-compile failsafe:integration-test failsafe:verify \
 *   -Dit.test=AgentStreamingRealToolsManualIT \
 *   -Dfailsafe.failIfNoSpecifiedTests=false \
 *   -Dmanual.openrouter.e2e=true
 * </pre>
 */
@Tag("manual")
@EnabledIfSystemProperty(named = "manual.openrouter.e2e", matches = "true")
@SpringBootTest(
        classes = AgentStreamingRealToolsManualIT.TestConfig.class,
        properties = {
                "open-daimon.agent.enabled=true",
                "open-daimon.agent.max-iterations=10",
                "open-daimon.agent.tools.http-api.enabled=true"
        }
)
@ActiveProfiles({"integration-test", "manual-openrouter-real-tools"})
class AgentStreamingRealToolsManualIT extends AbstractContainerIT {

    static {
        DotEnvLoader.loadDotEnv(Path.of("../.env"));
    }

    /**
     * Explicit model to test. Matches production configuration.
     * Do NOT use {@code openrouter/auto} — it routes to unpredictable models.
     */
    private static final String TEST_MODEL = "z-ai/glm-4.5v";

    private static final Long ADMIN_CHAT_ID = 350009010L;

    @Autowired
    private AgentExecutor agentExecutor;

    @Autowired
    private MessageTelegramCommandHandler messageHandler;

    @Autowired
    private TelegramUserRepository telegramUserRepository;

    @Autowired
    private TelegramUserService telegramUserService;

    @Autowired
    private UserModelPreferenceService userModelPreferenceService;

    @Autowired
    private ConversationThreadRepository threadRepository;

    @Autowired
    private OpenDaimonMessageRepository messageRepository;

    @MockitoBean
    private TelegramBotRegistrar telegramBotRegistrar;

    @MockitoBean
    private TelegramBot telegramBot;

    @BeforeAll
    static void requireKeys() {
        DotEnvLoader.loadDotEnv(Path.of("../.env"));
        String openRouterKey = System.getProperty("OPENROUTER_KEY", System.getenv("OPENROUTER_KEY"));
        Assumptions.assumeTrue(
                openRouterKey != null && !openRouterKey.isBlank() && !openRouterKey.equals("sk-placeholder"),
                "Skipping: OPENROUTER_KEY not set"
        );
        String serperKey = System.getProperty("SERPER_KEY", System.getenv("SERPER_KEY"));
        Assumptions.assumeTrue(
                serperKey != null && !serperKey.isBlank(),
                "Skipping: SERPER_KEY not set"
        );
    }

    @BeforeEach
    void setUpEach() throws TelegramApiException {
        messageRepository.deleteAll();
        threadRepository.deleteAll();
        telegramUserRepository.deleteAll();
        telegramUserService.ensureUserWithLevel(ADMIN_CHAT_ID, UserPriority.ADMIN);

        reset(telegramBot);
        doNothing().when(telegramBot).showTyping(anyLong());
        doNothing().when(telegramBot).sendMessage(anyLong(), anyString(), any(), any(ReplyKeyboard.class));
        doNothing().when(telegramBot).sendMessage(anyLong(), anyString(), any());
        doNothing().when(telegramBot).sendErrorMessage(anyLong(), anyString(), any());
    }

    // ── R1: Agent stream with explicit model ─────────────────────────────

    @Test
    @Timeout(3 * 60)
    @DisplayName("R1: Agent stream with z-ai/glm-4.5v — no raw XML in final answer")
    void agentStream_explicitModel_noRawXmlInFinalAnswer() {
        String conversationId = "test-stream-" + System.currentTimeMillis();
        Map<String, String> metadata = new HashMap<>();
        metadata.put(AICommand.PREFERRED_MODEL_ID_FIELD, TEST_MODEL);

        AgentRequest request = new AgentRequest(
                "Сравни производительность Quarkus и Spring Boot в 2026 году. Поищи в интернете.",
                conversationId,
                metadata,
                5,
                Set.of(),
                AgentStrategy.AUTO
        );

        List<AgentStreamEvent> events = new CopyOnWriteArrayList<>();
        AgentStreamEvent lastEvent = agentExecutor.executeStream(request)
                .doOnNext(events::add)
                .blockLast();

        System.out.println("\n=== STREAM EVENTS ===");
        for (AgentStreamEvent event : events) {
            System.out.printf("[%s] iteration=%d content=%s%n",
                    event.type(), event.iteration(),
                    event.content() != null ? event.content().substring(0, Math.min(200, event.content().length())) : "null");
        }
        System.out.println("=== END EVENTS ===\n");

        // Verify model used — METADATA event should carry the model name
        events.stream()
                .filter(e -> e.type() == AgentStreamEvent.EventType.METADATA)
                .findFirst()
                .ifPresent(e -> {
                    System.out.println("Model used: " + e.content());
                    assertThat(e.content())
                            .as("METADATA event should report the explicit model, not openrouter/auto")
                            .contains("glm");
                });

        assertThat(lastEvent).isNotNull();

        // Should have at least THINKING + one terminal event
        assertThat(events.size()).isGreaterThanOrEqualTo(2);

        // Terminal event should be FINAL_ANSWER or MAX_ITERATIONS (not ERROR)
        assertThat(lastEvent.type())
                .as("Terminal event should not be ERROR")
                .isNotEqualTo(AgentStreamEvent.EventType.ERROR);

        // Agent should attempt at least one tool call in AUTO/REACT mode
        long toolCallCount = events.stream()
                .filter(e -> e.type() == AgentStreamEvent.EventType.TOOL_CALL)
                .count();
        System.out.println("TOOL_CALL events: " + toolCallCount);

        // Final answer must not contain raw XML tool call tags
        if (lastEvent.content() != null) {
            assertThat(lastEvent.content())
                    .as("Final answer must not contain raw XML tool_call markup")
                    .doesNotContain("<tool_call>")
                    .doesNotContain("</tool_call>")
                    .doesNotContain("<arg_key>")
                    .doesNotContain("<arg_value>")
                    .doesNotContain("</arg_key>")
                    .doesNotContain("</arg_value>");
        }

        // Check ALL events for XML leakage (not just the final one)
        for (AgentStreamEvent event : events) {
            if (event.content() != null
                    && event.type() != AgentStreamEvent.EventType.OBSERVATION) {
                assertThat(event.content())
                        .as("Event [%s] iteration=%d must not contain raw XML tool_call markup",
                                event.type(), event.iteration())
                        .doesNotContain("<tool_call>")
                        .doesNotContain("</tool_call>");
            }
        }
    }

    // ── R2: Full E2E through Telegram handler ────────────────────────────

    @Test
    @Timeout(3 * 60)
    @DisplayName("R2: Full E2E with z-ai/glm-4.5v — response saved to DB without raw XML")
    void fullE2E_explicitModel_responseSavedWithoutRawXml() throws TelegramApiException {
        // Set preferred model on the user so TelegramMessageHandlerActions picks it up
        TelegramUser adminUser = telegramUserRepository.findByTelegramId(ADMIN_CHAT_ID)
                .orElseThrow(() -> new IllegalStateException("Admin user should exist after setUp"));
        userModelPreferenceService.setPreferredModel(adminUser.getId(), TEST_MODEL);

        // FSM agent-stream path uses the 4-arg overloads (chatId, text/html, replyTo/Id, boolean)
        // via TelegramMessageSender. Older 3-arg stubs silently returned null / were never
        // matched on verification, masking real behavior. Match the actual overloads.
        doNothing().when(telegramBot).editMessageHtml(anyLong(), any(), anyString(),
                org.mockito.ArgumentMatchers.anyBoolean());
        org.mockito.Mockito.when(telegramBot.sendMessageAndGetId(
                        anyLong(), anyString(), org.mockito.ArgumentMatchers.nullable(Integer.class),
                        org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(999);

        TelegramCommand command = createMessageCommand(
                ADMIN_CHAT_ID,
                100,
                "Сравни производительность Quarkus и Spring Boot в 2026 году. Поищи в интернете."
        );

        messageHandler.handle(command);

        TelegramUser user = telegramUserRepository.findByTelegramId(ADMIN_CHAT_ID)
                .orElseThrow(() -> new IllegalStateException("User should exist"));

        ConversationThread thread = threadRepository.findMostRecentActiveThread(user)
                .orElseThrow(() -> new IllegalStateException("Thread should exist"));

        List<OpenDaimonMessage> assistantMessages = messageRepository
                .findByThreadAndRoleOrderBySequenceNumberAsc(thread, MessageRole.ASSISTANT);

        assertThat(assistantMessages).isNotEmpty();

        String reply = assistantMessages.getLast().getContent();
        System.out.println("\n=== ASSISTANT REPLY ===");
        System.out.println(reply);
        System.out.println("=== END REPLY ===\n");

        assertThat(reply).isNotBlank();

        // Must not contain raw XML tool call markup
        assertThat(reply)
                .as("Assistant reply must not leak raw tool call XML")
                .doesNotContain("<tool_call>")
                .doesNotContain("</tool_call>")
                .doesNotContain("<arg_key>")
                .doesNotContain("<arg_value>");

        // Note: z-ai/glm-4.5v may reply in English despite Russian question.
        // Language compliance is not the goal of this test — raw XML absence is.

        // Verify edit-in-place: first agent event creates a status message, subsequent events edit it.
        org.mockito.Mockito.verify(telegramBot, org.mockito.Mockito.atLeastOnce())
                .sendMessageAndGetId(org.mockito.ArgumentMatchers.eq(ADMIN_CHAT_ID),
                        anyString(), org.mockito.ArgumentMatchers.nullable(Integer.class),
                        org.mockito.ArgumentMatchers.anyBoolean());
        org.mockito.Mockito.verify(telegramBot, org.mockito.Mockito.atLeastOnce())
                .editMessageHtml(org.mockito.ArgumentMatchers.eq(ADMIN_CHAT_ID),
                        org.mockito.ArgumentMatchers.eq(999), anyString(),
                        org.mockito.ArgumentMatchers.anyBoolean());
    }

    // ── R3: SIMPLE strategy with explicit model ──────────────────────────

    @Test
    @Timeout(3 * 60)
    @DisplayName("R3: SIMPLE strategy with z-ai/glm-4.5v — meaningful response without tools")
    void simpleStrategy_explicitModel_meaningfulResponse() {
        String conversationId = "test-simple-" + System.currentTimeMillis();
        Map<String, String> metadata = new HashMap<>();
        metadata.put(AICommand.PREFERRED_MODEL_ID_FIELD, TEST_MODEL);

        AgentRequest request = new AgentRequest(
                "Что такое Java? Ответь 2-3 предложениями.",
                conversationId,
                metadata,
                3,
                Set.of(),
                AgentStrategy.SIMPLE
        );

        List<AgentStreamEvent> events = new ArrayList<>();
        AgentStreamEvent lastEvent = agentExecutor.executeStream(request)
                .doOnNext(events::add)
                .blockLast();

        System.out.println("\n=== SIMPLE STREAM EVENTS ===");
        for (AgentStreamEvent event : events) {
            System.out.printf("[%s] iteration=%d contentLen=%d content=%s%n",
                    event.type(), event.iteration(),
                    event.content() != null ? event.content().length() : 0,
                    event.content() != null ? event.content().substring(0, Math.min(100, event.content().length())) : "null");
        }
        System.out.println("=== END EVENTS ===\n");

        assertThat(lastEvent).isNotNull();
        assertThat(lastEvent.type()).isEqualTo(AgentStreamEvent.EventType.FINAL_ANSWER);
        assertThat(lastEvent.content())
                .as("SIMPLE mode should return a substantive answer")
                .isNotBlank()
                .hasSizeGreaterThan(20);

        // No tool calls in SIMPLE mode
        long toolCallCount = events.stream()
                .filter(e -> e.type() == AgentStreamEvent.EventType.TOOL_CALL)
                .count();
        assertThat(toolCallCount)
                .as("SIMPLE strategy must not invoke any tools")
                .isZero();

        // No raw XML in the answer
        assertThat(lastEvent.content())
                .as("SIMPLE answer must not contain raw XML tool_call markup")
                .doesNotContain("<tool_call>")
                .doesNotContain("</tool_call>");

        // Verify METADATA event reports the correct model
        events.stream()
                .filter(e -> e.type() == AgentStreamEvent.EventType.METADATA)
                .findFirst()
                .ifPresent(e -> {
                    System.out.println("Model used: " + e.content());
                    assertThat(e.content())
                            .as("METADATA event should report the explicit model")
                            .contains("glm");
                });
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private TelegramCommand createMessageCommand(Long chatId, int messageId, String text) {
        Update update = new Update();

        User from = new User();
        from.setId(chatId);
        from.setUserName("manual-stream-user-" + chatId);
        from.setFirstName("Manual");
        from.setLastName("Stream");
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

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestConfig {
        // No WebTools override — uses real Serper API and real HTTP fetching
    }
}
