package io.github.ngirchev.opendaimon.it.fixture;

import io.github.ngirchev.opendaimon.ai.springai.rag.FileRAGService;
import io.github.ngirchev.opendaimon.ai.springai.config.RAGProperties;
import io.github.ngirchev.opendaimon.ai.springai.service.DocumentProcessingService;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fixture test for use case: text-pdf-rag.md
 *
 * <p>Verifies the happy path of the text-based PDF RAG pipeline:
 * <ol>
 *   <li>PDF with selectable text is processed via PDFBox ETL</li>
 *   <li>Text is chunked by TokenTextSplitter and stored in VectorStore</li>
 *   <li>Semantic search finds relevant chunks for a query</li>
 *   <li>Augmented prompt contains document context</li>
 * </ol>
 *
 * <p>Uses a deterministic mock EmbeddingModel (no external AI services needed).
 *
 * @see <a href="docs/usecases/text-pdf-rag.md">text-pdf-rag.md</a>
 */
@Tag("fixture")
@SpringBootTest(
        classes = TextPdfRagFixtureIT.RagTestConfig.class,
        properties = "spring.main.banner-mode=off"
)
class TextPdfRagFixtureIT {

    private static final int EMBEDDING_DIMENSIONS = 384;

    @SpringBootConfiguration
    static class RagTestConfig {

        @Bean
        public EmbeddingModel embeddingModel() {
            return new DeterministicEmbeddingModel(EMBEDDING_DIMENSIONS);
        }

        @Bean
        public VectorStore vectorStore(EmbeddingModel embeddingModel) {
            return SimpleVectorStore.builder(embeddingModel).build();
        }

        @Bean
        public RAGProperties ragProperties() {
            var props = new RAGProperties();
            props.setEnabled(true);
            props.setChunkSize(200);
            props.setChunkOverlap(50);
            props.setTopK(5);
            props.setSimilarityThreshold(0.0);
            var prompts = new RAGProperties.RAGPrompts();
            prompts.setAugmentedPromptTemplate("Context:\n%s\n\nQuestion: %s");
            prompts.setDocumentExtractErrorPdf("PDF error: %s");
            prompts.setDocumentExtractErrorDocument("Doc error: %s %s");
            props.setPrompts(prompts);
            return props;
        }

        @Bean
        public DocumentProcessingService documentProcessingService(
                VectorStore vectorStore, RAGProperties ragProperties) {
            return new DocumentProcessingService(vectorStore, ragProperties);
        }

        @Bean
        public FileRAGService fileRagService(
                VectorStore vectorStore, RAGProperties ragProperties) {
            return new FileRAGService(vectorStore, ragProperties);
        }
    }

    @Autowired
    private DocumentProcessingService documentProcessingService;

    @Autowired
    private FileRAGService fileRagService;

    @Test
    @DisplayName("Text PDF — full cycle: extract, index chunks, semantic search, augmented prompt")
    void textPdf_fullCycle_extractIndexSearchAugment() throws IOException {
        // 1. Create a test PDF with selectable text
        byte[] pdfBytes = createTestPdf(
                "Spring Boot simplifies Java application development. " +
                "It provides auto-configuration and embedded servers. " +
                "Developers can focus on business logic instead of boilerplate.");

        // 2. Process PDF: PDFBox extracts text, TokenTextSplitter chunks, VectorStore stores
        String documentId = documentProcessingService.processPdf(pdfBytes, "spring-boot-guide.pdf");

        assertThat(documentId)
                .as("Processing should return a non-null document ID")
                .isNotNull()
                .isNotBlank();

        // 3. Semantic search for relevant chunks by document ID
        List<Document> relevantChunks = fileRagService.findRelevantContext(
                "What is Spring Boot?", documentId);

        assertThat(relevantChunks)
                .as("Search should find chunks from the indexed PDF")
                .isNotEmpty();

        // 4. Create augmented prompt with context
        String augmentedPrompt = fileRagService.createAugmentedPrompt(
                "What is Spring Boot?", relevantChunks);

        assertThat(augmentedPrompt)
                .as("Augmented prompt should contain document context and user query")
                .contains("Spring Boot")
                .contains("What is Spring Boot?");
    }

    @Test
    @DisplayName("Text PDF — search across all documents returns results")
    void textPdf_searchAcrossAllDocuments_returnsResults() throws IOException {
        byte[] pdfBytes = createTestPdf(
                "PostgreSQL is an advanced open-source relational database " +
                "with strong support for ACID transactions and complex queries.");

        documentProcessingService.processPdf(pdfBytes, "postgres-docs.pdf");

        // Search without documentId filter
        List<Document> results = fileRagService.findRelevantContext("PostgreSQL database");

        assertThat(results)
                .as("Cross-document search should find relevant chunks")
                .isNotEmpty();
    }

    @Test
    @DisplayName("Augmented prompt with empty context — returns original query unchanged")
    void emptyContext_augmentedPrompt_returnsOriginalQuery() {
        String prompt = fileRagService.createAugmentedPrompt(
                "What is Spring Boot?", List.of());

        assertThat(prompt)
                .as("Empty context should still produce a prompt with the query")
                .contains("What is Spring Boot?");
    }

    private byte[] createTestPdf(String content) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            var page = new PDPage();
            doc.addPage(page);
            try (var stream = new PDPageContentStream(doc, page)) {
                stream.beginText();
                stream.setFont(
                        new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(50, 700);
                stream.showText(content);
                stream.endText();
            }
            var baos = new ByteArrayOutputStream();
            doc.save(baos);
            return baos.toByteArray();
        }
    }

}
