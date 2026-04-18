package io.github.ngirchev.opendaimon.telegram.integration;

import io.github.ngirchev.opendaimon.bulkhead.model.UserPriority;
import io.github.ngirchev.opendaimon.common.model.ConversationThread;
import io.github.ngirchev.opendaimon.common.model.MessageRole;
import io.github.ngirchev.opendaimon.common.model.OpenDaimonMessage;
import io.github.ngirchev.opendaimon.common.repository.ConversationThreadRepository;
import io.github.ngirchev.opendaimon.common.repository.OpenDaimonMessageRepository;
import io.github.ngirchev.opendaimon.telegram.TelegramBot;
import io.github.ngirchev.opendaimon.telegram.command.handler.impl.MessageTelegramCommandHandler;
import io.github.ngirchev.opendaimon.telegram.config.TelegramProperties;
import io.github.ngirchev.opendaimon.telegram.model.TelegramUser;
import io.github.ngirchev.opendaimon.telegram.repository.TelegramUserRepository;
import io.github.ngirchev.opendaimon.telegram.service.TelegramBotRegistrar;
import io.github.ngirchev.opendaimon.telegram.service.TelegramCommandSyncService;
import io.github.ngirchev.opendaimon.telegram.service.TelegramUserService;
import lombok.extern.slf4j.Slf4j;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.telegram.telegrambots.meta.api.objects.Chat;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboard;
import org.testcontainers.containers.PostgreSQLContainer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Manual integration test for REACT streaming at Telegram API level.
 *
 * <p>Scope:
 * <ul>
 *   <li>Real stack: TelegramBot mapping, command sync, FSM actions, AgentExecutor, DB persistence.</li>
 *   <li>Mocked only Telegram API transport: {@link RecordingTelegramBot} records send/edit calls.</li>
 * </ul>
 *
 * <p>Run explicitly:
 * <pre>
 * mvn test -pl opendaimon-telegram \
 *   -Dtest=TelegramReActStreamingOllamaManualIT#testReActStreamToTelegramApiSnapshots \
 *   -Dmanual.ollama.e2e=true
 * </pre>
 */
@Slf4j
@Tag("manual")
@EnabledIfSystemProperty(named = "manual.ollama.e2e", matches = "true")
@SpringBootTest(
        classes = TelegramReActStreamingOllamaManualIT.TestConfig.class,
        properties = {"spring.main.banner-mode=off"}
)
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
        "spring.ai.ollama.base-url=${OLLAMA_BASE_URL:http://localhost:11434}",
        "spring.ai.ollama.chat.options.model=${manual.ollama.chat-model:qwen3.5:4b}",
        "spring.autoconfigure.exclude=" +
                "org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration," +
                "org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration," +
                "org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionAutoConfiguration," +
                "org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration," +
                "org.springframework.ai.model.openai.autoconfigure.OpenAiImageAutoConfiguration," +
                "org.springframework.ai.model.openai.autoconfigure.OpenAiModerationAutoConfiguration",

        "open-daimon.telegram.enabled=true",
        "open-daimon.telegram.token=test-token",
        "open-daimon.telegram.username=test-bot",
        "open-daimon.telegram.max-message-length=4096",
        "open-daimon.telegram.file-upload.enabled=false",
        "open-daimon.telegram.file-upload.max-file-size-mb=20",
        "open-daimon.telegram.file-upload.supported-image-types=jpeg,png,gif,webp",
        "open-daimon.telegram.file-upload.supported-document-types=pdf",
        "open-daimon.telegram.cache.redis-enabled=false",

        "open-daimon.common.bulkhead.enabled=false",
        "open-daimon.common.storage.enabled=false",
        "open-daimon.common.assistant-role=You are a helpful assistant",
        "open-daimon.common.max-output-tokens=4000",
        "open-daimon.common.max-reasoning-tokens=1500",
        "open-daimon.common.max-user-message-tokens=4000",
        "open-daimon.common.max-total-prompt-tokens=32000",
        "open-daimon.common.summarization.message-window-size=5",
        "open-daimon.common.summarization.max-window-tokens=16000",
        "open-daimon.common.summarization.max-output-tokens=2000",
        "open-daimon.common.summarization.prompt=You are an assistant. Create a summary in JSON. Conversation:",
        "open-daimon.common.chat-routing.ADMIN.max-price=0.5",
        "open-daimon.common.chat-routing.ADMIN.required-capabilities=AUTO",
        "open-daimon.common.chat-routing.ADMIN.optional-capabilities=",
        "open-daimon.common.chat-routing.VIP.max-price=0.5",
        "open-daimon.common.chat-routing.VIP.required-capabilities=CHAT",
        "open-daimon.common.chat-routing.VIP.optional-capabilities=TOOL_CALLING,WEB",
        "open-daimon.common.chat-routing.REGULAR.max-price=0.0",
        "open-daimon.common.chat-routing.REGULAR.required-capabilities=AUTO",
        "open-daimon.common.chat-routing.REGULAR.optional-capabilities=",

        "open-daimon.ai.spring-ai.enabled=true",
        "open-daimon.ai.spring-ai.mock=false",
        "open-daimon.ai.spring-ai.rag.enabled=false",
        "open-daimon.ai.spring-ai.openrouter-auto-rotation.models.enabled=false",
        "open-daimon.ai.spring-ai.serper.api.key=test-key",
        "open-daimon.ai.spring-ai.serper.api.url=https://example.com/search",
        "open-daimon.ai.spring-ai.timeouts.response-timeout-seconds=600",
        "open-daimon.ai.spring-ai.timeouts.stream-timeout-seconds=600",
        "open-daimon.ai.spring-ai.web-tools.max-in-memory-bytes=2097152",
        "open-daimon.ai.spring-ai.web-tools.max-fetch-bytes=1048576",
        "open-daimon.ai.spring-ai.web-tools.user-agent=OpenDaimonBot/1.0 (telegram-react-it)",
        "open-daimon.ai.spring-ai.models.list[0].name=${manual.ollama.chat-model:qwen3.5:4b}",
        "open-daimon.ai.spring-ai.models.list[0].capabilities=AUTO,CHAT,TOOL_CALLING,WEB,SUMMARIZATION,THINKING",
        "open-daimon.ai.spring-ai.models.list[0].provider-type=OLLAMA",
        "open-daimon.ai.spring-ai.models.list[0].priority=1",
        "open-daimon.ai.spring-ai.models.list[0].think=true",

        "open-daimon.agent.enabled=true",
        "open-daimon.agent.max-iterations=10",
        "open-daimon.agent.tools.http-api.enabled=false",

        "open-daimon.rest.enabled=false",
        "open-daimon.ui.enabled=false",
        "open-daimon.ai.gateway-mock.enabled=false"
})
class TelegramReActStreamingOllamaManualIT {

    private static final String CHAT_MODEL_PROPERTY = "manual.ollama.chat-model";
    private static final String DEFAULT_CHAT_MODEL = "qwen3.5:4b";
    private static final String CHAT_MODEL = System.getProperty(CHAT_MODEL_PROPERTY, DEFAULT_CHAT_MODEL);

    private static final Long CHAT_ID = 350009010L;
    private static final int INCOMING_MESSAGE_ID = 101;
    private static final Pattern HTML_TAGS = Pattern.compile("<[^>]+>");
    private static final Pattern MARKDOWN_DECORATION = Pattern.compile("[*_`]");

    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.0");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void configureDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeAll
    static void ensureOllamaIsAvailable() {
        requireLocalOllamaWithModel(CHAT_MODEL);
    }

    @Autowired
    private MessageTelegramCommandHandler messageTelegramCommandHandler;

    @Autowired
    private RecordingTelegramBot telegramBot;

    @Autowired
    private TelegramUserService telegramUserService;

    @Autowired
    private TelegramUserRepository telegramUserRepository;

    @Autowired
    private ConversationThreadRepository threadRepository;

    @Autowired
    private OpenDaimonMessageRepository messageRepository;

    @MockitoBean
    private TelegramBotRegistrar telegramBotRegistrar;

    @BeforeEach
    void setUp() {
        messageRepository.deleteAll();
        threadRepository.deleteAll();
        telegramUserRepository.deleteAll();
        telegramBot.resetRecordedCalls();
        telegramUserService.ensureUserWithLevel(CHAT_ID, UserPriority.ADMIN);
        assertThat(messageTelegramCommandHandler).isNotNull();
    }

    /**
     * mvn test -pl opendaimon-telegram -Dtest=TelegramReActStreamingOllamaManualIT#testReActStreamToTelegramApiSnapshots -Dmanual.ollama.e2e=true (not in idea console!!!)
     * If you run with -am, add: -Dsurefire.failIfNoSpecifiedTests=false
     * Manual test: run locally with Ollama and PostgreSQL Testcontainers to inspect
     * Telegram API-level REACT streaming snapshots.
     */
    @Test
    @Timeout(5 * 60)
    @DisplayName("REACT stream reaches Telegram API as progress + final snapshots")
    void testReActStreamToTelegramApiSnapshots() {
        Update update = createIncomingTextUpdate(CHAT_ID, INCOMING_MESSAGE_ID, "Write a short tale");
        Instant startedAt = Instant.now();

        telegramBot.onUpdateReceived(update);

        List<TelegramApiCall> calls = telegramBot.snapshotCalls();
        assertThat(calls).as("Telegram API must receive at least one call").isNotEmpty();
        printSnapshots(calls, startedAt);

        List<TelegramApiCall> progressCalls = calls.stream()
                .filter(TelegramApiCall::isThinkingLike)
                .toList();
        assertThat(progressCalls)
                .as("Progress updates (thinking) must be sent before final answer")
                .isNotEmpty();

        List<TelegramApiCall> nonThinkingTextCalls = calls.stream()
                .filter(TelegramApiCall::hasText)
                .filter(call -> !call.isThinkingLike())
                .toList();
        assertThat(nonThinkingTextCalls)
                .as("Final/non-progress text updates must be present")
                .isNotEmpty();

        assertThat(progressCalls.getFirst().timestamp())
                .as("Thinking progress must appear before final answer updates")
                .isBefore(nonThinkingTextCalls.getFirst().timestamp());

        TelegramUser user = telegramUserRepository.findByTelegramId(CHAT_ID)
                .orElseThrow(() -> new IllegalStateException("Telegram user should exist"));
        ConversationThread thread = threadRepository.findMostRecentActiveThread(user)
                .orElseThrow(() -> new IllegalStateException("Conversation thread should exist"));
        List<OpenDaimonMessage> assistantMessages = messageRepository
                .findByThreadAndRoleOrderBySequenceNumberAsc(thread, MessageRole.ASSISTANT);

        assertThat(assistantMessages).as("Assistant message must be persisted").isNotEmpty();
        String finalAnswer = assistantMessages.getLast().getContent();
        assertThat(finalAnswer).as("Final answer must not be blank").isNotBlank();

        String normalizedFinalAnswer = normalizeForComparison(finalAnswer);
        String prefix = normalizedFinalAnswer.substring(0, Math.min(normalizedFinalAnswer.length(), 24));
        boolean finalAnswerDelivered = calls.stream()
                .filter(TelegramApiCall::hasText)
                .map(TelegramApiCall::plainText)
                .map(TelegramReActStreamingOllamaManualIT::normalizeForComparison)
                .anyMatch(text -> text.contains(prefix));
        assertThat(finalAnswerDelivered)
                .as("At least one Telegram call should contain final answer content")
                .isTrue();
    }

    private static Update createIncomingTextUpdate(Long chatId, int messageId, String text) {
        Update update = new Update();

        User from = new User();
        from.setId(chatId);
        from.setUserName("telegram-react-it-user-" + chatId);
        from.setFirstName("Telegram");
        from.setLastName("ReAct");
        from.setLanguageCode("en");

        Message message = new Message();
        message.setMessageId(messageId);
        Chat chat = new Chat();
        chat.setId(chatId);
        message.setChat(chat);
        message.setFrom(from);
        message.setText(text);
        update.setMessage(message);
        return update;
    }

    private static void printSnapshots(List<TelegramApiCall> calls, Instant startedAt) {
        for (int i = 0; i < calls.size(); i++) {
            TelegramApiCall call = calls.get(i);
            long elapsedMs = Duration.between(startedAt, call.timestamp()).toMillis();
            System.out.printf(
                    """

                            ===== TELEGRAM SNAPSHOT #%d (+%d ms) =====
                            op: %s
                            chatId: %s
                            messageId: %s
                            replyToMessageId: %s
                            text:
                            %s
                            ===== END SNAPSHOT =====
                            """,
                    i + 1,
                    elapsedMs,
                    call.type(),
                    call.chatId(),
                    call.messageId(),
                    call.replyToMessageId(),
                    truncateForConsole(call.text(), 1200)
            );
            System.out.flush();
        }
    }

    private static String truncateForConsole(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        String normalized = text.trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength) + "...";
    }

    private static String normalizeForComparison(String text) {
        if (text == null) {
            return "";
        }
        String withoutMarkdown = MARKDOWN_DECORATION.matcher(text).replaceAll("");
        return withoutMarkdown.replaceAll("\\s+", " ").trim();
    }

    private static void requireLocalOllamaWithModel(String modelName) {
        String baseUrl = resolveOllamaBaseUrl();
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .GET()
                .timeout(Duration.ofSeconds(5))
                .uri(URI.create(baseUrl + "/api/tags"))
                .build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            boolean statusOk = response.statusCode() == 200;
            boolean modelPresent = response.body() != null && response.body().contains(modelName);
            Assumptions.assumeTrue(
                    statusOk && modelPresent,
                    "Skipping: Ollama/model unavailable at " + baseUrl + " (required model: " + modelName + ")"
            );
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
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestConfig {

        @Bean
        @Primary
        public MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        @Primary
        public RecordingTelegramBot telegramBot(
                TelegramProperties telegramProperties,
                TelegramCommandSyncService commandSyncService,
                TelegramUserService telegramUserService) {
            return new RecordingTelegramBot(telegramProperties, commandSyncService, telegramUserService);
        }
    }

    /**
     * Telegram bot test double that keeps the full Telegram command handling pipeline real,
     * but records outgoing Telegram API calls instead of making network requests.
     */
    static class RecordingTelegramBot extends TelegramBot {

        private final AtomicInteger messageIdGenerator = new AtomicInteger(900);
        private final CopyOnWriteArrayList<TelegramApiCall> calls = new CopyOnWriteArrayList<>();

        RecordingTelegramBot(
                TelegramProperties config,
                TelegramCommandSyncService commandSyncService,
                TelegramUserService userService) {
            super(config, commandSyncService, userService);
        }

        void resetRecordedCalls() {
            calls.clear();
            messageIdGenerator.set(900);
        }

        List<TelegramApiCall> snapshotCalls() {
            return List.copyOf(calls);
        }

        @Override
        public Integer sendMessageAndGetId(
                Long chatId,
                String text,
                Integer replyToMessageId,
                ReplyKeyboard replyMarkup,
                boolean disableWebPagePreview) {
            int messageId = messageIdGenerator.incrementAndGet();
            calls.add(new TelegramApiCall(
                    TelegramCallType.SEND,
                    Instant.now(),
                    chatId,
                    messageId,
                    replyToMessageId,
                    text
            ));
            return messageId;
        }

        @Override
        public void sendMessage(Long chatId, String text, Integer replyToMessageId, ReplyKeyboard replyMarkup) {
            int messageId = messageIdGenerator.incrementAndGet();
            calls.add(new TelegramApiCall(
                    TelegramCallType.SEND,
                    Instant.now(),
                    chatId,
                    messageId,
                    replyToMessageId,
                    text
            ));
        }

        @Override
        public void editMessageHtml(Long chatId, Integer messageId, String htmlText, boolean disableWebPagePreview) {
            calls.add(new TelegramApiCall(
                    TelegramCallType.EDIT,
                    Instant.now(),
                    chatId,
                    messageId,
                    null,
                    htmlText
            ));
        }

        @Override
        public void sendErrorMessage(Long chatId, String errorMessage, Integer replyToMessageId) {
            int messageId = messageIdGenerator.incrementAndGet();
            calls.add(new TelegramApiCall(
                    TelegramCallType.ERROR,
                    Instant.now(),
                    chatId,
                    messageId,
                    replyToMessageId,
                    errorMessage
            ));
        }

        @Override
        public void showTyping(Long chatId) {
            calls.add(new TelegramApiCall(
                    TelegramCallType.TYPING,
                    Instant.now(),
                    chatId,
                    null,
                    null,
                    null
            ));
        }
    }

    private enum TelegramCallType {
        TYPING,
        SEND,
        EDIT,
        ERROR
    }

    private record TelegramApiCall(
            TelegramCallType type,
            Instant timestamp,
            Long chatId,
            Integer messageId,
            Integer replyToMessageId,
            String text
    ) {
        boolean hasText() {
            return text != null && !text.isBlank();
        }

        boolean isThinkingLike() {
            if (!hasText()) {
                return false;
            }
            return text.contains("\uD83E\uDD14") || text.contains("Thinking");
        }

        String plainText() {
            if (text == null) {
                return "";
            }
            String withoutTags = HTML_TAGS.matcher(text).replaceAll("");
            return withoutTags
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
                    .replace("&amp;", "&")
                    .trim();
        }
    }
}
