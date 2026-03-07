package ru.girchev.aibot.it.springai;

import io.github.ngirchev.dotenv.DotEnvLoader;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import ru.girchev.aibot.ai.springai.config.RAGAutoConfig;
import ru.girchev.aibot.ai.springai.config.SpringAIFlywayConfig;
import ru.girchev.aibot.ai.springai.service.DocumentProcessingService;
import ru.girchev.aibot.ai.springai.service.RAGService;
import ru.girchev.aibot.common.config.CoreFlywayConfig;
import ru.girchev.aibot.common.config.CoreJpaConfig;
import ru.girchev.aibot.test.TestDatabaseConfiguration;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Интеграционный тест для RAG (Retrieval-Augmented Generation) с SimpleVectorStore.
 * 
 * <p><b>Цель:</b> Протестировать ETL Pipeline и RAG поиск с реальными embeddings.
 * 
 * <p>Этот тест требует:
 * <ul>
 *   <li>OPENROUTER_KEY в .env файле (для embeddings через OpenAI API)</li>
 *   <li>Или локальный Ollama с моделью nomic-embed-text:v1.5</li>
 * </ul>
 * 
 * <p>Тест по умолчанию отключен (@Disabled), так как требует реального API ключа
 * или локального Ollama для генерации embeddings.
 */
@Slf4j
@Disabled("Требует реальный OPENROUTER_KEY или Ollama для embeddings. Удалите @Disabled для локального запуска.")
@SpringBootTest(
        classes = RAGServiceIT.TestConfig.class,
        properties = {
                "spring.main.banner-mode=off"
        }
)
@ActiveProfiles("integration-test")
@Import({
        TestDatabaseConfiguration.class,
        CoreFlywayConfig.class,
        CoreJpaConfig.class,
        SpringAIFlywayConfig.class,
        RAGAutoConfig.class
})
@TestPropertySource(properties = {
        "spring.autoconfigure.exclude=org.springframework.ai.model.chat.memory.autoconfigure.ChatMemoryAutoConfiguration",
        "ai-bot.common.bulkhead.enabled=false",
        "ai-bot.common.conversation-context.enabled=false",
        "ai-bot.ai.spring-ai.rag.enabled=true",
        "ai-bot.ai.spring-ai.rag.chunk-size=200",
        "ai-bot.ai.spring-ai.rag.chunk-overlap=50",
        "ai-bot.ai.spring-ai.rag.top-k=3",
        "ai-bot.ai.spring-ai.rag.similarity-threshold=0.5"
})
class RAGServiceIT {

    @BeforeAll
    static void loadEnv() {
        DotEnvLoader.loadDotEnv(Path.of("../.env"));
    }

    @Autowired
    private DocumentProcessingService documentProcessingService;

    @Autowired
    private RAGService ragService;

    @Autowired
    private VectorStore vectorStore;

    /**
     * Тест полного цикла: загрузка PDF -> создание embeddings -> поиск релевантных чанков.
     */
    @Test
    void testDocumentProcessing_fullCycle() throws IOException {
        // Arrange
        byte[] pdfData = createTestPdf();
        String originalName = "test-document.pdf";

        log.info("=== Testing RAG Full Cycle ===");

        // Act - обрабатываем PDF
        String documentId = documentProcessingService.processPdf(pdfData, originalName);
        
        // Assert - документ должен быть обработан
        assertNotNull(documentId, "Document ID should not be null");
        log.info("Document processed with ID: {}", documentId);

        // Act - ищем релевантный контекст
        String query = "artificial intelligence machine learning";
        List<Document> relevantChunks = ragService.findRelevantContext(query, documentId);

        // Assert - должны найти релевантные чанки
        assertFalse(relevantChunks.isEmpty(), "Should find relevant chunks");
        log.info("Found {} relevant chunks for query: '{}'", relevantChunks.size(), query);
        
        relevantChunks.forEach(doc -> 
            log.info("Chunk: {} ...", doc.getText().substring(0, Math.min(100, doc.getText().length())))
        );

        // Act - создаем augmented prompt
        String augmentedPrompt = ragService.createAugmentedPrompt(query, relevantChunks);

        // Assert - промпт должен содержать контекст
        assertNotNull(augmentedPrompt);
        assertTrue(augmentedPrompt.contains("Context:"));
        log.info("Augmented prompt created successfully");

        log.info("=== RAG Full Cycle Test Completed ===");
    }

    /**
     * Тест поиска по всем документам (без фильтра по documentId).
     */
    @Test
    void testFindRelevantContext_acrossAllDocuments() throws IOException {
        // Arrange
        byte[] pdfData1 = createTestPdfWithContent("Document about Python programming language and data science.");
        byte[] pdfData2 = createTestPdfWithContent("Document about Java programming and enterprise applications.");

        String docId1 = documentProcessingService.processPdf(pdfData1, "python-doc.pdf");
        String docId2 = documentProcessingService.processPdf(pdfData2, "java-doc.pdf");

        log.info("Processed documents: {} and {}", docId1, docId2);

        // Act - поиск без указания documentId
        String query = "programming language";
        List<Document> results = ragService.findRelevantContext(query);

        // Assert - должны найти результаты из обоих документов
        assertFalse(results.isEmpty(), "Should find relevant chunks from both documents");
        log.info("Found {} chunks across all documents", results.size());
    }

    /**
     * Тест на пустой контекст - createAugmentedPrompt должен вернуть оригинальный запрос.
     */
    @Test
    void testCreateAugmentedPrompt_emptyContext_returnsOriginalQuery() {
        // Arrange
        String userQuery = "What is the meaning of life?";
        List<Document> emptyContext = List.of();

        // Act
        String result = ragService.createAugmentedPrompt(userQuery, emptyContext);

        // Assert
        assertEquals(userQuery, result, "Should return original query when context is empty");
    }

    /**
     * Создает тестовый PDF с текстом об AI и Machine Learning.
     */
    private byte[] createTestPdf() throws IOException {
        return createTestPdfWithContent("""
                Introduction to Artificial Intelligence and Machine Learning
                
                Artificial Intelligence (AI) is a branch of computer science that aims to create 
                intelligent machines that work and react like humans. Machine Learning is a subset 
                of AI that provides systems the ability to automatically learn and improve from 
                experience without being explicitly programmed.
                
                Key concepts in Machine Learning include:
                - Supervised Learning: Learning from labeled data
                - Unsupervised Learning: Finding patterns in unlabeled data
                - Reinforcement Learning: Learning through rewards and penalties
                
                Deep Learning is a subset of Machine Learning that uses neural networks with 
                multiple layers to analyze various factors of data. Applications include 
                image recognition, natural language processing, and autonomous vehicles.
                """);
    }

    /**
     * Создает PDF с указанным содержимым.
     */
    private byte[] createTestPdfWithContent(String content) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);

            try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
                contentStream.beginText();
                contentStream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                contentStream.setLeading(14.5f);
                contentStream.newLineAtOffset(50, 700);

                // Разбиваем текст на строки
                String[] lines = content.split("\n");
                for (String line : lines) {
                    // Ограничиваем длину строки
                    while (line.length() > 80) {
                        contentStream.showText(line.substring(0, 80));
                        contentStream.newLine();
                        line = line.substring(80);
                    }
                    contentStream.showText(line);
                    contentStream.newLine();
                }

                contentStream.endText();
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            document.save(baos);
            return baos.toByteArray();
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(excludeName = {
            "ru.girchev.aibot.telegram.config.TelegramAutoConfig",
            "ru.girchev.aibot.rest.config.RestAutoConfig",
            "ru.girchev.aibot.ui.config.UIAutoConfig",
            "org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration",
            "org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionAutoConfiguration",
            "org.springframework.ai.model.openai.autoconfigure.OpenAiImageAutoConfiguration",
            "org.springframework.ai.model.openai.autoconfigure.OpenAiModerationAutoConfiguration"
    })
    static class TestConfig {
    }
}
