package io.github.ngirchev.opendaimon.it.manual;

import io.github.ngirchev.opendaimon.ai.springai.rag.FileRAGService;
import io.github.ngirchev.opendaimon.ai.springai.service.DocumentProcessingService;
import io.github.ngirchev.opendaimon.ai.springai.service.SpringAIGateway;
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
 * Manual E2E-like integration test for real Ollama + real PDF + follow-up RAG.
 *
 * <p>Not intended for regular CI runs.
 * Run explicitly:
 * <pre>
 * ./mvnw -pl opendaimon-app -am test-compile failsafe:integration-test failsafe:verify \
 *   -Dit.test=ImagePdfVisionRagOllamaManualIT \
 *   -Dfailsafe.failIfNoSpecifiedTests=false \
 *   -Dmanual.ollama.e2e=true \
 *   -Dmanual.ollama.chat-model=qwen2.5:3b \
 *   -Dmanual.ollama.vision-model=gemma3:4b
 * </pre>
 */
@Tag("manual")
@EnabledIfSystemProperty(named = "manual.ollama.e2e", matches = "true")
@SpringBootTest(classes = ImagePdfVisionRagOllamaManualIT.TestConfig.class)
@ActiveProfiles({"integration-test", "manual-ollama-e2e"})
@Import({
        TestDatabaseConfiguration.class
})
class ImagePdfVisionRagOllamaManualIT {
    private static final Long TEST_CHAT_ID = 350009001L;
    private static final String PDF_RESOURCE = "image-based-pdf-sample.pdf";
    private static final String RAG_PREFIX = "[RAG:documentId:";
    private static final Duration OLLAMA_TIMEOUT = Duration.ofSeconds(5);
    private static final String EXPECTED_FOLLOW_UP_PHRASE = "(as far as they know)";
    private static final String CHAT_MODEL_PROPERTY = "manual.ollama.chat-model";
    private static final String DEFAULT_CHAT_MODEL = "qwen2.5:3b";
    private static final String VISION_MODEL_PROPERTY = "manual.ollama.vision-model";
    private static final String DEFAULT_VISION_MODEL = "gemma3:4b";
    private static final String CHAT_MODEL = System.getProperty(CHAT_MODEL_PROPERTY, DEFAULT_CHAT_MODEL);
    private static final String VISION_MODEL = System.getProperty(VISION_MODEL_PROPERTY, DEFAULT_VISION_MODEL);
    private static final List<String> REQUIRED_OLLAMA_MODELS =
            Stream.of(CHAT_MODEL, VISION_MODEL, "nomic-embed-text:v1.5")
                    .distinct()
                    .toList();

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
                    "Skipping manual test: Ollama/models unavailable at " + baseUrl + ". Required: " + REQUIRED_OLLAMA_MODELS);
        } catch (Exception ex) {
            Assumptions.assumeTrue(false,
                    "Skipping manual test: cannot connect to Ollama at " + baseUrl + ". " + ex.getMessage());
        }
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
     * 1. Send a Telegram message with a PDF attachment and the prompt: "что в первом предложении?"
     * 2. The PDF cannot be read as text and is converted to an image.
     * 3. The image is sent to an OCR-capable model to extract text.
     * 4. The extracted text is stored in RAG.
     * 5. Ask a follow-up: "а что было в последнем предложении в скобках?";
     *    RAG context is available to the model and it answers "as far as they know".
     * !!! DO NOT CHANGE THE TEST BEHAVIOR
     * !!! DO NOT CHANGE THE OLLAMA PROMPT
     * !!! DO NOT USE FAKE MOCKS JUST TO FORCE THE RESULT
     *
     */
    @Test
    @Timeout(6 * 60)
    @DisplayName("Manual E2E: real PDF + follow-up question uses stored RAG context")
    void realPdf_thenFollowUp_usesRagContext() throws IOException {
        Attachment pdfAttachment = loadPdfAttachment();

        TelegramCommand firstCommand = createMessageCommand(
                TEST_CHAT_ID,
                1,
                "что в первом предложении?",
                List.of(pdfAttachment)
        );

        messageHandler.handle(firstCommand);

        TelegramUser user = telegramUserRepository.findByTelegramId(TEST_CHAT_ID)
                .orElseThrow(() -> new IllegalStateException("Telegram user should be created"));

        ConversationThread thread = threadRepository.findMostRecentActiveThread(user)
                .orElseThrow(() -> new IllegalStateException("Active thread should exist"));

        assertThat(thread.getMemoryBullets())
                .as("Thread should contain stored RAG document marker")
                .isNotNull()
                .anyMatch(bullet -> bullet != null && bullet.startsWith(RAG_PREFIX) && bullet.contains(PDF_RESOURCE));

        List<String> ragDocumentIds = SpringAIGateway.extractRagDocumentIds(thread.getMemoryBullets());
        assertThat(ragDocumentIds)
                .as("RAG documentId should be stored in thread memoryBullets")
                .isNotEmpty();

        List<String> storedChunkTexts = fileRagService.findAllByDocumentId(ragDocumentIds.getFirst()).stream()
                .map(document -> document.getText() == null ? "" : document.getText())
                .toList();
        assertThat(storedChunkTexts)
                .as("RAG VectorStore should contain extracted text chunks for stored documentId")
                .isNotEmpty();

        String extractedTextForRag = String.join("\n---\n", storedChunkTexts);
        assertThat(containsExpectedFollowUpAnswer(extractedTextForRag))
                .withFailMessage(
                        "OCR/vision extracted text in RAG does not contain expected phrase. Extracted text: [%s]",
                        extractedTextForRag
                )
                .isTrue();

        String firstAssistantReply = latestAssistantReply(thread);
        assertThat(firstAssistantReply)
                .as("First answer should not be blank")
                .isNotBlank();

        TelegramCommand secondCommand = createMessageCommand(
                TEST_CHAT_ID,
                2,
                "а что было в последнем предложении в скобках?",
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
        assertThat(containsExpectedFollowUpAnswer(secondAssistantReply))
                .withFailMessage(
                        "Follow-up answer should include expected bracket phrase meaning. Actual reply: [%s]",
                        secondAssistantReply
                )
                .isTrue();

        assertThat(messageRepository.countByThreadAndRole(threadAfterFollowUp, MessageRole.USER))
                .as("Two user messages expected in thread")
                .isEqualTo(2);
        assertThat(messageRepository.countByThreadAndRole(threadAfterFollowUp, MessageRole.ASSISTANT))
                .as("Two assistant messages expected in thread")
                .isEqualTo(2);
    }

    private Attachment loadPdfAttachment() throws IOException {
        ClassPathResource resource = new ClassPathResource(PDF_RESOURCE);
        byte[] pdfBytes = resource.getInputStream().readAllBytes();
        return new Attachment(
                "manual/" + PDF_RESOURCE,
                "application/pdf",
                PDF_RESOURCE,
                pdfBytes.length,
                AttachmentType.PDF,
                pdfBytes
        );
    }

    private TelegramCommand createMessageCommand(Long chatId, int messageId, String text, List<Attachment> attachments) {
        Update update = new Update();

        User from = new User();
        from.setId(chatId);
        from.setUserName("manual-ollama-user");
        from.setFirstName("Manual");
        from.setLastName("Ollama");
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

    private static boolean containsExpectedFollowUpAnswer(String text) {
        return text != null && text.contains(EXPECTED_FOLLOW_UP_PHRASE);
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
    }
}
