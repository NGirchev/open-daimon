package io.github.ngirchev.opendaimon.it.springai;

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
import io.github.ngirchev.opendaimon.ai.springai.config.RAGAutoConfig;
import io.github.ngirchev.opendaimon.ai.springai.config.SpringAIFlywayConfig;
import io.github.ngirchev.opendaimon.ai.springai.service.DocumentProcessingService;
import io.github.ngirchev.opendaimon.ai.springai.rag.FileRAGService;
import io.github.ngirchev.opendaimon.common.config.CoreFlywayConfig;
import io.github.ngirchev.opendaimon.common.config.CoreJpaConfig;
import io.github.ngirchev.opendaimon.test.TestDatabaseConfiguration;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for RAG (Retrieval-Augmented Generation) with SimpleVectorStore.
 *
 * <p><b>Goal:</b> Test ETL pipeline and RAG search with real embeddings.
 *
 * <p>This test requires:
 * <ul>
 *   <li>OPENROUTER_KEY in .env (for embeddings via OpenAI API)</li>
 *   <li>Or local Ollama with model nomic-embed-text:v1.5</li>
 * </ul>
 *
 * <p>Test is disabled by default (@Disabled) as it requires a real API key or local Ollama for embeddings.
 */
@Slf4j
@Disabled("Requires real OPENROUTER_KEY or Ollama for embeddings. Remove @Disabled for local run.")
@SpringBootTest(
        classes = FileRAGServiceIT.TestConfig.class,
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
        "open-daimon.common.bulkhead.enabled=false",
        "open-daimon.common.manual-conversation-history.enabled=false",
        "open-daimon.ai.spring-ai.rag.enabled=true",
        "open-daimon.ai.spring-ai.rag.chunk-size=200",
        "open-daimon.ai.spring-ai.rag.chunk-overlap=50",
        "open-daimon.ai.spring-ai.rag.top-k=3",
        "open-daimon.ai.spring-ai.rag.similarity-threshold=0.5"
})
class FileRAGServiceIT {

    @BeforeAll
    static void loadEnv() {
        DotEnvLoader.loadDotEnv(Path.of("../.env"));
    }

    @Autowired
    private DocumentProcessingService documentProcessingService;

    @Autowired
    private FileRAGService fileRagService;

    @Autowired
    private VectorStore vectorStore;

    /**
     * Full cycle test: load PDF -> create embeddings -> search relevant chunks.
     */
    @Test
    void testDocumentProcessing_fullCycle() throws IOException {
        // Arrange
        byte[] pdfData = createTestPdf();
        String originalName = "test-document.pdf";

        log.info("=== Testing RAG Full Cycle ===");

        // Act - process PDF
        String documentId = documentProcessingService.processPdf(pdfData, originalName);
        
        // Assert - document must be processed
        assertNotNull(documentId, "Document ID should not be null");
        log.info("Document processed with ID: {}", documentId);

        // Act - search relevant context
        String query = "artificial intelligence machine learning";
        List<Document> relevantChunks = fileRagService.findRelevantContext(query, documentId);

        // Assert - should find relevant chunks
        assertFalse(relevantChunks.isEmpty(), "Should find relevant chunks");
        log.info("Found {} relevant chunks for query: '{}'", relevantChunks.size(), query);
        
        relevantChunks.forEach(doc -> 
            log.info("Chunk: {} ...", doc.getText().substring(0, Math.min(100, doc.getText().length())))
        );

        // Act - create augmented prompt
        String augmentedPrompt = fileRagService.createAugmentedPrompt(query, relevantChunks);

        // Assert - prompt must contain context
        assertNotNull(augmentedPrompt);
        assertTrue(augmentedPrompt.contains("Context:"));
        log.info("Augmented prompt created successfully");

        log.info("=== RAG Full Cycle Test Completed ===");
    }

    /**
     * Test search across all documents (no documentId filter).
     */
    @Test
    void testFindRelevantContext_acrossAllDocuments() throws IOException {
        // Arrange
        byte[] pdfData1 = createTestPdfWithContent("Document about Python programming language and data science.");
        byte[] pdfData2 = createTestPdfWithContent("Document about Java programming and enterprise applications.");

        String docId1 = documentProcessingService.processPdf(pdfData1, "python-doc.pdf");
        String docId2 = documentProcessingService.processPdf(pdfData2, "java-doc.pdf");

        log.info("Processed documents: {} and {}", docId1, docId2);

        // Act - search without documentId
        String query = "programming language";
        List<Document> results = fileRagService.findRelevantContext(query);

        // Assert - should find results from both documents
        assertFalse(results.isEmpty(), "Should find relevant chunks from both documents");
        log.info("Found {} chunks across all documents", results.size());
    }

    /**
     * Test empty context - createAugmentedPrompt should return original query.
     */
    @Test
    void testCreateAugmentedPrompt_emptyContext_returnsOriginalQuery() {
        // Arrange
        String userQuery = "What is the meaning of life?";
        List<Document> emptyContext = List.of();

        // Act
        String result = fileRagService.createAugmentedPrompt(userQuery, emptyContext);

        // Assert
        assertEquals(userQuery, result, "Should return original query when context is empty");
    }

    /**
     * Creates test PDF with text about AI and Machine Learning.
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
     * Creates PDF with given content.
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

                // Split text into lines
                String[] lines = content.split("\n");
                for (String line : lines) {
                    // Limit line length
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
            "io.github.ngirchev.opendaimon.telegram.config.TelegramAutoConfig",
            "io.github.ngirchev.opendaimon.rest.config.RestAutoConfig",
            "io.github.ngirchev.opendaimon.ui.config.UIAutoConfig",
            "org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration",
            "org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionAutoConfiguration",
            "org.springframework.ai.model.openai.autoconfigure.OpenAiImageAutoConfiguration",
            "org.springframework.ai.model.openai.autoconfigure.OpenAiModerationAutoConfiguration"
    })
    static class TestConfig {
    }
}
