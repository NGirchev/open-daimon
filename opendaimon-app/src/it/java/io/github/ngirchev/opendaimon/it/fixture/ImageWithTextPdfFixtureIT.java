package io.github.ngirchev.opendaimon.it.fixture;

import io.github.ngirchev.opendaimon.ai.springai.config.RAGProperties;
import io.github.ngirchev.opendaimon.ai.springai.rag.FileRAGService;
import io.github.ngirchev.opendaimon.ai.springai.service.DocumentProcessingService;
import io.github.ngirchev.opendaimon.ai.springai.service.PdfTextDetector;
import io.github.ngirchev.opendaimon.ai.springai.service.SpringDocumentContentAnalyzer;
import io.github.ngirchev.opendaimon.ai.springai.service.SpringDocumentPipelineActions;
import io.github.ngirchev.opendaimon.ai.springai.service.SpringRagQueryAugmenter;
import io.github.ngirchev.opendaimon.common.ai.ModelCapabilities;
import io.github.ngirchev.opendaimon.common.ai.command.AICommand;
import io.github.ngirchev.opendaimon.common.ai.document.IDocumentContentAnalyzer;
import io.github.ngirchev.opendaimon.common.ai.factory.AICommandFactoryRegistry;
import io.github.ngirchev.opendaimon.common.ai.factory.DefaultAICommandFactory;
import io.github.ngirchev.opendaimon.common.ai.pipeline.AIRequestPipeline;
import io.github.ngirchev.opendaimon.common.ai.pipeline.DefaultAIRequestPipelineActions;
import io.github.ngirchev.opendaimon.common.ai.pipeline.IRagQueryAugmenter;
import io.github.ngirchev.opendaimon.common.ai.pipeline.fsm.AIRequestContext;
import io.github.ngirchev.opendaimon.common.ai.pipeline.fsm.AIRequestEvent;
import io.github.ngirchev.opendaimon.common.ai.pipeline.fsm.AIRequestPipelineFsmFactory;
import io.github.ngirchev.opendaimon.common.ai.pipeline.fsm.AIRequestState;
import io.github.ngirchev.opendaimon.common.ai.pipeline.fsm.AttachmentEvent;
import io.github.ngirchev.opendaimon.common.ai.pipeline.fsm.AttachmentProcessingContext;
import io.github.ngirchev.opendaimon.common.ai.pipeline.fsm.AttachmentState;
import io.github.ngirchev.opendaimon.common.ai.pipeline.fsm.DocumentPipelineActions;
import io.github.ngirchev.opendaimon.common.ai.pipeline.fsm.DocumentPipelineFsmFactory;
import io.github.ngirchev.fsm.impl.extended.ExDomainFsm;
import io.github.ngirchev.opendaimon.common.config.CoreCommonProperties;
import io.github.ngirchev.opendaimon.common.model.Attachment;
import io.github.ngirchev.opendaimon.common.model.AttachmentType;
import io.github.ngirchev.opendaimon.bulkhead.model.UserPriority;
import io.github.ngirchev.opendaimon.bulkhead.service.IUserPriorityService;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fixture test: IMAGE attachment + text-extractable PDF in the same request.
 *
 * <p>Verifies that:
 * <ul>
 *   <li>PDF is processed through RAG (text extraction, chunking, indexing)</li>
 *   <li>IMAGE is NOT processed through RAG (goes directly to vision model)</li>
 *   <li>VISION capability is added because of IMAGE, not because of PDF content</li>
 *   <li>Pipeline does not confuse IMAGE-triggered VISION with PDF content analysis</li>
 * </ul>
 *
 * <p>This is a regression test for the bug where gateway incorrectly sent text-extractable
 * PDFs to vision OCR when VISION capability was present from an IMAGE attachment.
 */
@Tag("fixture")
@SpringBootTest(
        classes = ImageWithTextPdfFixtureIT.TestConfig.class,
        properties = "spring.main.banner-mode=off"
)
class ImageWithTextPdfFixtureIT {

    private static final int EMBEDDING_DIMENSIONS = 384;

    @SpringBootConfiguration
    static class TestConfig {

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
            prompts.setVisionExtractionPrompt("Extract text");
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

        @Bean
        public PdfTextDetector pdfTextDetector() {
            return new PdfTextDetector();
        }

        @Bean
        public IDocumentContentAnalyzer documentContentAnalyzer(PdfTextDetector pdfTextDetector) {
            return new SpringDocumentContentAnalyzer(pdfTextDetector);
        }

        @Bean
        public DocumentPipelineActions documentPipelineActions(
                IDocumentContentAnalyzer analyzer, DocumentProcessingService dps,
                FileRAGService rag, RAGProperties ragProps) {
            // No vision model registry or chat service in fixture test — OCR will fail gracefully
            return new SpringDocumentPipelineActions(analyzer, dps, rag, null, null, ragProps);
        }

        @Bean
        public ExDomainFsm<AttachmentProcessingContext, AttachmentState, AttachmentEvent> documentFsm(
                DocumentPipelineActions actions) {
            return DocumentPipelineFsmFactory.create(actions);
        }

        @Bean
        public IRagQueryAugmenter ragQueryAugmenter(FileRAGService rag, RAGProperties ragProps) {
            return new SpringRagQueryAugmenter(rag, ragProps);
        }

        @Bean
        public IUserPriorityService userPriorityService() {
            return userId -> UserPriority.ADMIN;
        }

        @Bean
        public CoreCommonProperties coreCommonProperties() {
            var props = new CoreCommonProperties();
            props.setMaxOutputTokens(4096);
            props.setMaxUserMessageTokens(32000);
            props.setMaxTotalPromptTokens(65000);
            var routing = new CoreCommonProperties.ChatRoutingProperties();
            var admin = new CoreCommonProperties.PriorityChatRoutingProperties();
            admin.setMaxPrice(5.0);
            admin.setRequiredCapabilities(List.of(ModelCapabilities.CHAT));
            admin.setOptionalCapabilities(List.of());
            routing.setAdmin(admin);
            routing.setVip(admin);
            routing.setRegular(admin);
            props.setChatRouting(routing);
            return props;
        }

        @Bean
        public DefaultAICommandFactory defaultAICommandFactory(
                IUserPriorityService ups, IDocumentContentAnalyzer analyzer,
                CoreCommonProperties props) {
            return new DefaultAICommandFactory(ups, null, analyzer, props);
        }

        @Bean
        public AICommandFactoryRegistry factoryRegistry(DefaultAICommandFactory factory) {
            return new AICommandFactoryRegistry(List.of(factory));
        }

        @Bean
        public DefaultAIRequestPipelineActions aiRequestPipelineActions(
                ExDomainFsm<AttachmentProcessingContext, AttachmentState, AttachmentEvent> documentFsm,
                IRagQueryAugmenter augmenter,
                AICommandFactoryRegistry registry) {
            return new DefaultAIRequestPipelineActions(documentFsm, augmenter, registry);
        }

        @Bean
        public ExDomainFsm<AIRequestContext, AIRequestState, AIRequestEvent> requestFsm(
                DefaultAIRequestPipelineActions actions) {
            return AIRequestPipelineFsmFactory.create(actions);
        }

        @Bean
        public AIRequestPipeline aiRequestPipeline(
                ExDomainFsm<AIRequestContext, AIRequestState, AIRequestEvent> requestFsm,
                AICommandFactoryRegistry registry) {
            return new AIRequestPipeline(requestFsm, registry);
        }
    }

    @Autowired
    private AIRequestPipeline pipeline;

    @Autowired
    private FileRAGService fileRagService;

    @Autowired
    private IDocumentContentAnalyzer analyzer;

    /**
     * IMAGE + text PDF together via FSM pipeline: PDF goes through RAG text extraction,
     * IMAGE does NOT trigger RAG. VISION is added from IMAGE, not from PDF.
     */
    @Test
    @DisplayName("IMAGE + text PDF — PDF indexed in RAG, IMAGE skipped by RAG, VISION from IMAGE")
    void imageAndTextPdf_pdfIndexedInRag_imageSkippedByRag() throws IOException {
        byte[] imageData = new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        Attachment imageAttachment = new Attachment(
                "img/photo.png", "image/png", "photo.png",
                imageData.length, AttachmentType.IMAGE, imageData);

        byte[] pdfData = createTextPdf("Project Report: Q1 revenue was $2.5M with 15% growth.");
        Attachment pdfAttachment = new Attachment(
                "doc/report.pdf", "application/pdf", "report.pdf",
                pdfData.length, AttachmentType.PDF, pdfData);

        // Process via FSM pipeline
        Map<String, String> metadata = new HashMap<>();
        TestChatCommand command = new TestChatCommand(
                1L, "What was the Q1 revenue?",
                List.of(imageAttachment, pdfAttachment));

        AICommand aiCommand = pipeline.prepareCommand(command, metadata);

        // PDF processed — documentIds stored in metadata
        String rawDocIds = metadata.get(AICommand.RAG_DOCUMENT_IDS_FIELD);
        assertThat(rawDocIds)
                .as("Text PDF should produce a documentId in metadata")
                .isNotNull()
                .isNotBlank();

        // RAG chunks contain PDF text
        String docId = rawDocIds.split(",")[0].trim();
        List<Document> chunks = fileRagService.findAllByDocumentId(docId);
        assertThat(chunks).isNotEmpty();
        String allText = chunks.stream().map(Document::getText).reduce("", (a, b) -> a + " " + b);
        assertThat(allText).contains("$2.5M");

        // AICommand options should have augmented text with RAG context
        if (aiCommand.options() instanceof io.github.ngirchev.opendaimon.common.ai.command.OpenDaimonChatOptions opts) {
            assertThat(opts.userRole())
                    .as("Augmented query should contain RAG context")
                    .contains("$2.5M")
                    .contains("Q1 revenue");
        }

        // No PDF-as-image fallback (text PDF should not trigger vision OCR)
        assertThat(metadata.get("pdfAsImageFilenames"))
                .as("Text PDF should NOT be converted to images")
                .isNull();
    }

    /**
     * Pipeline: IMAGE + text PDF → factory adds VISION from IMAGE (not from PDF analysis).
     */
    @Test
    @DisplayName("Pipeline: IMAGE + text PDF — VISION from IMAGE, PDF text-extracted via RAG")
    void pipeline_imageAndTextPdf_visionFromImage_pdfViaRag() throws IOException {
        byte[] imageData = new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        Attachment imageAttachment = new Attachment(
                "img/diagram.png", "image/png", "diagram.png",
                imageData.length, AttachmentType.IMAGE, imageData);

        byte[] pdfData = createTextPdf("API documentation: GET /users returns a list of users.");
        Attachment pdfAttachment = new Attachment(
                "doc/api.pdf", "application/pdf", "api.pdf",
                pdfData.length, AttachmentType.PDF, pdfData);

        Map<String, String> metadata = new HashMap<>();
        TestChatCommand command = new TestChatCommand(
                42L, "Describe the diagram and summarize the API docs",
                List.of(imageAttachment, pdfAttachment));

        AICommand aiCommand = pipeline.prepareCommand(command, metadata);

        // VISION should be in capabilities (from IMAGE attachment)
        assertThat(aiCommand.modelCapabilities())
                .as("VISION should be added because of IMAGE attachment")
                .contains(ModelCapabilities.VISION);

        // RAG docIds should be in metadata
        assertThat(metadata.get(AICommand.RAG_DOCUMENT_IDS_FIELD))
                .as("PDF should be indexed in RAG")
                .isNotNull()
                .isNotBlank();

        // User text should be augmented with RAG context from PDF
        if (aiCommand.options() instanceof io.github.ngirchev.opendaimon.common.ai.command.OpenDaimonChatOptions opts) {
            assertThat(opts.userRole())
                    .as("User query should contain RAG context from PDF")
                    .contains("GET /users");
        }
    }

    /**
     * Analyzer: text PDF detected as TEXT_EXTRACTABLE (not IMAGE_ONLY).
     */
    @Test
    @DisplayName("Analyzer: text PDF with IMAGE attachment — PDF is TEXT_EXTRACTABLE")
    void analyzer_textPdfIsTextExtractable() throws IOException {
        byte[] pdfData = createTextPdf("Hello World");
        Attachment pdfAttachment = new Attachment(
                "doc/test.pdf", "application/pdf", "test.pdf",
                pdfData.length, AttachmentType.PDF, pdfData);

        var result = analyzer.analyze(pdfAttachment);
        assertThat(result.contentType())
                .as("Text PDF must be TEXT_EXTRACTABLE")
                .isEqualTo(io.github.ngirchev.opendaimon.common.ai.document.DocumentContentType.TEXT_EXTRACTABLE);
        assertThat(result.needsVision())
                .as("Text PDF should NOT need VISION")
                .isFalse();
    }

    private static byte[] createTextPdf(String text) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 700);
                cs.showText(text);
                cs.endText();
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            return baos.toByteArray();
        }
    }

    private record TestChatCommand(
            Long userId, String userText, List<Attachment> attachments
    ) implements io.github.ngirchev.opendaimon.common.command.IChatCommand<io.github.ngirchev.opendaimon.common.command.ICommandType> {
        @Override public io.github.ngirchev.opendaimon.common.command.ICommandType commandType() { return null; }
        @Override public boolean stream() { return false; }
    }

}
