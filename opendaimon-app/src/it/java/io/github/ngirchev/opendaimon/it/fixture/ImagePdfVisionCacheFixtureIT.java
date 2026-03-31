package io.github.ngirchev.opendaimon.it.fixture;

import io.github.ngirchev.opendaimon.ai.springai.rag.FileRAGService;
import io.github.ngirchev.opendaimon.ai.springai.config.RAGProperties;
import io.github.ngirchev.opendaimon.ai.springai.config.SpringAIModelConfig;
import io.github.ngirchev.opendaimon.ai.springai.retry.SpringAIModelRegistry;
import io.github.ngirchev.opendaimon.ai.springai.service.DocumentProcessingService;
import io.github.ngirchev.opendaimon.ai.springai.service.SpringDocumentPipelineActions;
import io.github.ngirchev.opendaimon.common.ai.ModelCapabilities;
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

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fixture test for use case: image-pdf-vision-cache.md
 *
 * <p>Verifies the happy path of the image-only PDF → vision cache pipeline:
 * <ol>
 *   <li>Image-only PDF (no selectable text) triggers vision fallback</li>
 *   <li>Vision-extracted text is stored as RAG chunks with type="pdf-vision" metadata</li>
 *   <li>Cached chunks are retrievable via semantic search</li>
 *   <li>Re-query uses cached chunks without re-extraction</li>
 * </ol>
 *
 * <p>Tests the {@code processExtractedText} path that the gateway calls after vision extraction.
 *
 * @see <a href="docs/usecases/image-pdf-vision-cache.md">image-pdf-vision-cache.md</a>
 */
@Tag("fixture")
@SpringBootTest(
        classes = ImagePdfVisionCacheFixtureIT.VisionCacheTestConfig.class,
        properties = "spring.main.banner-mode=off"
)
class ImagePdfVisionCacheFixtureIT {

    private static final int EMBEDDING_DIMENSIONS = 384;

    @SpringBootConfiguration
    static class VisionCacheTestConfig {

        @Bean
        public EmbeddingModel embeddingModel() {
            return new DeterministicEmbeddingModel();
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
    @DisplayName("Vision-extracted text — indexed in VectorStore and retrievable via search")
    void visionExtractedText_indexedAndRetrievable() {
        // Simulate what SpringAIGateway does after vision model extracts text from image-only PDF:
        // it calls processExtractedText() with the extracted content.
        String visionExtractedContent =
                "Certificate of Completion. This certifies that John Doe " +
                "has successfully completed the Advanced Java course " +
                "on March 15, 2025. Issued by OpenDaimon Academy.";

        String documentId = documentProcessingService.processExtractedText(
                visionExtractedContent, "certificate-scan.pdf");

        assertThat(documentId)
                .as("processExtractedText should return a valid document ID")
                .isNotNull()
                .isNotBlank();

        // Search for the cached vision-extracted content
        List<Document> results = fileRagService.findRelevantContext(
                "Who completed the Java course?", documentId);

        assertThat(results)
                .as("Vision-extracted chunks should be findable in VectorStore")
                .isNotEmpty();

        // Verify chunk content includes the extracted text
        String allContent = results.stream()
                .map(Document::getText)
                .reduce("", (a, b) -> a + " " + b);
        assertThat(allContent)
                .as("Cached chunks should contain the vision-extracted text")
                .contains("John Doe")
                .contains("Advanced Java");
    }

    @Test
    @DisplayName("Cached chunks — re-query returns same results without re-extraction")
    void cachedChunks_reQueryReturnsSameResults() {
        String extractedText =
                "Medical Report. Patient: Jane Smith. Date: 2025-01-20. " +
                "Diagnosis: All results within normal range. " +
                "Recommendation: Follow-up in 6 months.";

        String documentId = documentProcessingService.processExtractedText(
                extractedText, "medical-scan.pdf");

        // First query
        List<Document> firstQuery = fileRagService.findRelevantContext(
                "Patient diagnosis", documentId);

        // Second query (simulates follow-up — uses cached chunks, no re-extraction)
        List<Document> secondQuery = fileRagService.findRelevantContext(
                "What was the recommendation?", documentId);

        assertThat(firstQuery).isNotEmpty();
        assertThat(secondQuery).isNotEmpty();
        assertThat(secondQuery)
                .as("Re-query should return chunks from the same cached document")
                .hasSameSizeAs(firstQuery);
    }

    @Test
    @DisplayName("Augmented prompt from vision cache — contains extracted context")
    void augmentedPrompt_containsVisionExtractedContext() {
        String extractedText =
                "Invoice #12345. Company: TechCorp. Amount: $5,000. " +
                "Date: 2025-02-15. Payment terms: Net 30.";

        String documentId = documentProcessingService.processExtractedText(
                extractedText, "invoice-scan.pdf");

        List<Document> chunks = fileRagService.findRelevantContext(
                "Invoice amount", documentId);

        String augmentedPrompt = fileRagService.createAugmentedPrompt(
                "What is the invoice amount?", chunks);

        assertThat(augmentedPrompt)
                .as("Augmented prompt should include vision-extracted invoice data")
                .contains("$5,000")
                .contains("What is the invoice amount?");
    }

    /**
     * Reproduces the production bug: findRelevantContext with similarityThreshold=0.7
     * returns 0 chunks when query language differs from extracted text language.
     * The fix: use findAllByDocumentId for freshly vision-extracted text — it bypasses
     * similarity threshold and returns ALL chunks for the document.
     */
    @Test
    @DisplayName("findAllByDocumentId — returns all vision chunks regardless of query similarity")
    void findAllByDocumentId_returnsAllVisionChunks() {
        String extractedText =
                "Certificate of Completion. This certifies that John Doe " +
                "has successfully completed the Advanced Java course " +
                "on March 15, 2025. Issued by OpenDaimon Academy.";

        String documentId = documentProcessingService.processExtractedText(
                extractedText, "certificate-scan.pdf");

        // findAllByDocumentId must return ALL stored chunks regardless of query
        List<Document> allChunks = fileRagService.findAllByDocumentId(documentId);

        assertThat(allChunks)
                .as("findAllByDocumentId should return all stored chunks for the document")
                .isNotEmpty();

        String allContent = allChunks.stream()
                .map(Document::getText)
                .reduce("", (a, b) -> a + " " + b);
        assertThat(allContent)
                .as("All vision-extracted content should be present")
                .contains("John Doe")
                .contains("Advanced Java");
    }

    @Test
    @DisplayName("stripModelInternalTokens — removes gemma3 internal tokens from vision output")
    void stripModelInternalTokens_removesInternalTokens() {
        String dirty = "U.S. Department of Justice\nAntitrust Division\n<start_of_image>";
        String clean = SpringDocumentPipelineActions.stripModelInternalTokens(dirty);
        assertThat(clean).isEqualTo("U.S. Department of Justice\nAntitrust Division");

        String withMultiple = "<start_of_turn>Extract text<end_of_turn><start_of_image>Hello World<end_of_image>";
        assertThat(SpringDocumentPipelineActions.stripModelInternalTokens(withMultiple)).isEqualTo("Extract textHello World");

        assertThat(SpringDocumentPipelineActions.stripModelInternalTokens(null)).isNull();
        assertThat(SpringDocumentPipelineActions.stripModelInternalTokens("  <start_of_image>  ")).isEmpty();
    }

    /**
     * Reproduces the production bug: model registry configured like real deployment
     * (AUTO model without VISION, VISION model without AUTO). When the gateway adds
     * VISION requirement for image-only PDF payload, AUTO must be stripped so that
     * the VISION model is found. Before the fix, searching for [AUTO, VISION] returned
     * no candidates and threw RuntimeException.
     */
    @Test
    @DisplayName("Model selection — AUTO + VISION: registry finds vision model when AUTO is stripped")
    void modelSelection_autoWithVision_findsVisionModel() {
        // Production-like model config: separate text and vision models
        SpringAIModelConfig textModel = new SpringAIModelConfig();
        textModel.setName("qwen2.5:3b");
        textModel.setCapabilities(Set.of(
                ModelCapabilities.AUTO, ModelCapabilities.CHAT,
                ModelCapabilities.TOOL_CALLING, ModelCapabilities.SUMMARIZATION));
        textModel.setProviderType(SpringAIModelConfig.ProviderType.OLLAMA);
        textModel.setPriority(1);

        SpringAIModelConfig visionModel = new SpringAIModelConfig();
        visionModel.setName("gemma3:4b");
        visionModel.setCapabilities(Set.of(ModelCapabilities.CHAT, ModelCapabilities.VISION));
        visionModel.setProviderType(SpringAIModelConfig.ProviderType.OLLAMA);
        visionModel.setPriority(1);

        SpringAIModelRegistry registry = new SpringAIModelRegistry(
                List.of(textModel, visionModel), null, null);

        // The bug scenario: command has AUTO, payload has images → system needs [AUTO, VISION]
        // No model has both → empty candidates
        List<SpringAIModelConfig> bugQuery = registry.getCandidatesByCapabilities(
                Set.of(ModelCapabilities.AUTO, ModelCapabilities.VISION), null);
        assertThat(bugQuery)
                .as("[AUTO, VISION] should return NO candidates — this is the bug scenario")
                .isEmpty();

        // The fix: strip AUTO when VISION is needed → search for [VISION] only
        List<SpringAIModelConfig> fixedQuery = registry.getCandidatesByCapabilities(
                Set.of(ModelCapabilities.VISION), null);
        assertThat(fixedQuery)
                .as("[VISION] alone should find gemma3:4b")
                .isNotEmpty()
                .extracting(SpringAIModelConfig::getName)
                .contains("gemma3:4b");
    }

    /**
     * Mock embedding model — same as in TextPdfRagFixtureIT.
     * Returns deterministic unit vectors so pipeline mechanics are tested
     * without real semantic matching.
     */
    static class DeterministicEmbeddingModel implements EmbeddingModel {

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            var embeddings = IntStream.range(0, request.getInstructions().size())
                    .mapToObj(i -> new Embedding(unitVector(), i))
                    .toList();
            return new EmbeddingResponse(embeddings);
        }

        @Override
        public float[] embed(Document document) {
            return unitVector();
        }

        private float[] unitVector() {
            float[] vector = new float[EMBEDDING_DIMENSIONS];
            Arrays.fill(vector, 1.0f / (float) Math.sqrt(EMBEDDING_DIMENSIONS));
            return vector;
        }
    }
}
