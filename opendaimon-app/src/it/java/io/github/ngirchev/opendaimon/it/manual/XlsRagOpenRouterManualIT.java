package io.github.ngirchev.opendaimon.it.manual;

import io.github.ngirchev.dotenv.DotEnvLoader;
import io.github.ngirchev.opendaimon.ai.springai.rag.FileRAGService;
import io.github.ngirchev.opendaimon.ai.springai.service.DocumentProcessingService;
import io.github.ngirchev.opendaimon.common.ai.command.AICommand;
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
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.reset;

/**
 * Manual E2E integration test for OpenRouter auto + XLS spreadsheet + follow-up RAG.
 *
 * <p>Same scenario as {@code XlsRagOllamaManualIT} but uses {@code openrouter/auto} model
 * via OpenRouter API instead of a local Ollama chat model. Embeddings are handled by
 * {@code intfloat/multilingual-e5-large} via OpenRouter — no local Ollama required.
 *
 * <p>Flow:
 * <ol>
 *   <li>Send a message with {@code file_example_XLS_50.xls} and prompt "какие колонки есть в таблице?"</li>
 *   <li>Apache Tika extracts text from the XLS file; chunks are stored in RAG VectorStore</li>
 *   <li>Model answers via {@code openrouter/auto} using the extracted spreadsheet content</li>
 *   <li>Follow-up: "назови все страны из таблицы" — model uses RAG context to list countries</li>
 * </ol>
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
 *   -Dit.test=XlsRagOpenRouterManualIT \
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
class XlsRagOpenRouterManualIT extends AbstractContainerIT {

    static {
        DotEnvLoader.loadDotEnv(Path.of("../.env"));
    }

    private static final Long TEST_CHAT_ID = 350009009L;
    private static final String XLS_RESOURCE = "attachments/file_example_XLS_50.xls";

    @Autowired
    private MessageTelegramCommandHandler messageHandler;

    @Autowired
    private TelegramUserRepository telegramUserRepository;

    @Autowired
    private ConversationThreadRepository threadRepository;

    @Autowired
    private OpenDaimonMessageRepository messageRepository;

    @Autowired
    private FileRAGService fileRagService;

    @Autowired
    private DocumentProcessingService documentProcessingService;

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
     * 1. Send a Telegram message with an XLS spreadsheet and the prompt: "какие колонки есть в таблице?"
     * 2. Apache Tika extracts text directly from the XLS file (no vision/OCR needed).
     * 3. The extracted text is chunked and stored in RAG VectorStore.
     * 4. Model responds via openrouter/auto.
     * 5. Follow-up: "назови все страны из таблицы" — model uses RAG context to list countries.
     * !!! DO NOT CHANGE THE TEST BEHAVIOR
     * !!! DO NOT CHANGE THE PROMPT
     * !!! DO NOT USE FAKE MOCKS JUST TO FORCE THE RESULT
     */
    @Test
    @Timeout(3 * 60)
    @DisplayName("Manual E2E: OpenRouter auto + XLS spreadsheet + follow-up RAG")
    void xls_openRouterAuto_thenFollowUp_usesRagContext() throws IOException {
        TelegramCommand firstCommand = createMessageCommand(
                TEST_CHAT_ID,
                1,
                "какие колонки есть в таблице?",
                List.of(loadXlsAttachment())
        );

        messageHandler.handle(firstCommand);

        TelegramUser user = telegramUserRepository.findByTelegramId(TEST_CHAT_ID)
                .orElseThrow(() -> new IllegalStateException("Telegram user should be created"));

        ConversationThread thread = threadRepository.findMostRecentActiveThread(user)
                .orElseThrow(() -> new IllegalStateException("Active thread should exist"));

        // Verify RAG stored
        List<OpenDaimonMessage> userMessages = messageRepository
                .findByThreadAndRoleOrderBySequenceNumberAsc(thread, MessageRole.USER);
        assertThat(userMessages)
                .as("At least one USER message should exist in thread")
                .isNotEmpty();

        String ragDocumentIdsRaw = userMessages.stream()
                .filter(m -> m.getMetadata() != null && m.getMetadata().containsKey(AICommand.RAG_DOCUMENT_IDS_FIELD))
                .map(m -> (String) m.getMetadata().get(AICommand.RAG_DOCUMENT_IDS_FIELD))
                .findFirst()
                .orElse(null);

        assertThat(ragDocumentIdsRaw)
                .as("RAG documentId should be stored in USER message metadata")
                .isNotNull()
                .isNotBlank();

        List<String> ragDocumentIds = Arrays.stream(ragDocumentIdsRaw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();

        assertThat(ragDocumentIds)
                .as("Parsed RAG documentIds should be non-empty")
                .isNotEmpty();

        List<String> storedChunkTexts = fileRagService.findAllByDocumentId(ragDocumentIds.getFirst()).stream()
                .map(document -> document.getText() == null ? "" : document.getText())
                .toList();
        assertThat(storedChunkTexts)
                .as("RAG VectorStore should contain extracted text chunks for stored documentId")
                .isNotEmpty();

        String extractedTextForRag = String.join("\n---\n", storedChunkTexts);
        assertThat(extractedTextForRag.toLowerCase())
                .as("Extracted text must contain recognizable column headers from the XLS")
                .satisfiesAnyOf(
                        s -> assertThat(s).contains("name"),
                        s -> assertThat(s).contains("country"),
                        s -> assertThat(s).contains("gender")
                );

        String firstAssistantReply = latestAssistantReply(thread);
        assertThat(firstAssistantReply)
                .as("First answer should not be blank")
                .isNotBlank();

        TelegramCommand secondCommand = createMessageCommand(
                TEST_CHAT_ID,
                2,
                "назови все страны из таблицы",
                List.of()
        );

        messageHandler.handle(secondCommand);

        ConversationThread threadAfterFollowUp = threadRepository.findMostRecentActiveThread(user)
                .orElseThrow(() -> new IllegalStateException("Active thread should exist after follow-up"));
        assertThat(threadAfterFollowUp.getId())
                .as("Follow-up should stay in same thread")
                .isEqualTo(thread.getId());

        String secondAssistantReply = latestAssistantReply(threadAfterFollowUp);
        assertThat(secondAssistantReply)
                .as("Follow-up answer should not be blank")
                .isNotBlank();
        assertThat(secondAssistantReply.toLowerCase())
                .withFailMessage(
                        "Follow-up answer should contain country names from the XLS. Actual reply: [%s]",
                        secondAssistantReply
                )
                .containsAnyOf(
                        "france",
                        "united",
                        "great britain",
                        "china",
                        "germany",
                        "indonesia",
                        "japan"
                );

        assertThat(messageRepository.countByThreadAndRole(threadAfterFollowUp, MessageRole.USER))
                .as("Two user messages expected in thread")
                .isEqualTo(2);
        assertThat(messageRepository.countByThreadAndRole(threadAfterFollowUp, MessageRole.ASSISTANT))
                .as("Two assistant messages expected in thread")
                .isEqualTo(2);
    }

    /**
     * Verifies that XLS RAG can answer questions about specific cell values.
     * Row 7 of file_example_XLS_50.xls has firstName=Etta.
     * This ensures Tika extraction preserves row-level data and RAG can retrieve it.
     */
    @Test
    @Timeout(3 * 60)
    @DisplayName("Manual E2E: OpenRouter + XLS — specific cell lookup (row 7, firstName=Etta)")
    void xls_openRouterAuto_specificCellLookup() throws IOException {
        TelegramCommand command = createMessageCommand(
                TEST_CHAT_ID + 1,
                1,
                "What is the firstName in row 7?",
                List.of(loadXlsAttachment())
        );

        messageHandler.handle(command);

        TelegramUser user = telegramUserRepository.findByTelegramId(TEST_CHAT_ID + 1)
                .orElseThrow(() -> new IllegalStateException("Telegram user should be created"));

        ConversationThread thread = threadRepository.findMostRecentActiveThread(user)
                .orElseThrow(() -> new IllegalStateException("Active thread should exist"));

        String reply = latestAssistantReply(thread);
        assertThat(reply)
                .as("Reply should not be blank")
                .isNotBlank();

        assertThat(reply.toLowerCase())
                .as("Model should find firstName=Etta in row 7 of the XLS file")
                .contains("etta");
    }

    private Attachment loadXlsAttachment() throws IOException {
        ClassPathResource resource = new ClassPathResource(XLS_RESOURCE);
        byte[] xlsBytes = resource.getInputStream().readAllBytes();
        return new Attachment(
                "manual/" + XLS_RESOURCE,
                "application/vnd.ms-excel",
                XLS_RESOURCE,
                xlsBytes.length,
                AttachmentType.PDF,
                xlsBytes
        );
    }

    private TelegramCommand createMessageCommand(Long chatId, int messageId, String text, List<Attachment> attachments) {
        Update update = new Update();

        User from = new User();
        from.setId(chatId);
        from.setUserName("manual-openrouter-xls-user");
        from.setFirstName("Manual");
        from.setLastName("OpenRouterXls");
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
