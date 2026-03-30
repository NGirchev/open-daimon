package io.github.ngirchev.opendaimon.ai.springai.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;
import io.github.ngirchev.opendaimon.ai.springai.config.RAGProperties;
import io.github.ngirchev.opendaimon.ai.springai.config.SpringAIModelConfig;
import io.github.ngirchev.opendaimon.ai.springai.config.SpringAIProperties;
import io.github.ngirchev.opendaimon.ai.springai.rag.FileRAGService;
import io.github.ngirchev.opendaimon.ai.springai.retry.SpringAIModelRegistry;
import io.github.ngirchev.opendaimon.common.ai.ModelCapabilities;
import io.github.ngirchev.opendaimon.common.ai.command.ChatAICommand;
import io.github.ngirchev.opendaimon.common.ai.command.FixedModelChatAICommand;
import io.github.ngirchev.opendaimon.common.ai.document.IDocumentPreprocessor;
import io.github.ngirchev.opendaimon.common.ai.response.SpringAIStreamResponse;
import io.github.ngirchev.opendaimon.common.exception.DocumentContentNotExtractableException;
import io.github.ngirchev.opendaimon.common.exception.UnsupportedModelCapabilityException;
import io.github.ngirchev.opendaimon.common.model.Attachment;
import io.github.ngirchev.opendaimon.common.model.AttachmentType;
import io.github.ngirchev.opendaimon.common.service.AIGatewayRegistry;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Minimal valid PDF (one blank page) that PDFBox can parse and render.
 * Used by tests that exercise the image-only PDF fallback path.
 */

/**
 * Test for PDF attachment flow from command to DocumentProcessingService.processPdf call.
 * Verifies that when ChatAICommand has PDF attachment and empty body.messages,
 * gateway calls processRagIfEnabled and processPdf with non-empty data.
 */
@ExtendWith(MockitoExtension.class)
class SpringAIGatewayDocumentRagTest {

    @Mock
    private SpringAIProperties springAIProperties;
    @Mock
    private AIGatewayRegistry aiGatewayRegistry;
    @Mock
    private SpringAIModelRegistry springAIModelRegistry;
    @Mock
    private SpringAIChatService chatService;
    @Mock
    private DocumentProcessingService documentProcessingService;
    @Mock
    private FileRAGService fileRagService;
    @Mock
    private ChatMemory chatMemory;
    private SpringAIGateway springAIGateway;

    /**
     * Creates a minimal valid PDF (one blank page) that PDFBox can parse and render.
     * Required for tests that exercise the image-only PDF → vision fallback path,
     * because renderPdfToImageAttachments uses PDFBox internally.
     */
    private static byte[] createMinimalPdf() {
        try {
            PDDocument doc = new PDDocument();
            doc.addPage(new PDPage());
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            doc.close();
            return baos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to create minimal test PDF", e);
        }
    }

    @BeforeEach
    void setUp() {
        when(springAIProperties.getMock()).thenReturn(false);

        @SuppressWarnings("unchecked")
        ObjectProvider<FileRAGService> ragProvider = mock(ObjectProvider.class);
        when(ragProvider.getIfAvailable()).thenReturn(fileRagService);

        @SuppressWarnings("unchecked")
        ObjectProvider<ChatMemory> chatMemoryProvider = mock(ObjectProvider.class);

        lenient().when(documentProcessingService.processPdf(any(byte[].class), anyString())).thenReturn("doc-id-1");
        lenient().when(fileRagService.findRelevantContext(anyString(), anyString())).thenReturn(List.of(new Document("chunk text")));
        lenient().when(fileRagService.createAugmentedPrompt(anyString(), anyList())).thenReturn("Augmented prompt with context from document.");

        SpringAIModelConfig modelConfig = new SpringAIModelConfig();
        modelConfig.setName("test-model");
        modelConfig.setCapabilities(Set.of(ModelCapabilities.CHAT, ModelCapabilities.VISION));
        modelConfig.setProviderType(SpringAIModelConfig.ProviderType.OPENAI);
        when(springAIModelRegistry.getCandidatesByCapabilities(any(), any(), any())).thenReturn(List.of(modelConfig));

        when(chatService.streamChat(any(), any(), any(), any())).thenReturn(new SpringAIStreamResponse(Flux.empty()));

        RAGProperties ragProperties = new RAGProperties();
        ragProperties.setEnabled(true);
        ragProperties.setChunkSize(800);
        ragProperties.setChunkOverlap(100);
        ragProperties.setTopK(5);
        ragProperties.setSimilarityThreshold(0.7);
        RAGProperties.RAGPrompts prompts = new RAGProperties.RAGPrompts();
        prompts.setDocumentExtractErrorPdf("Could not extract text from file \"%s\".");
        prompts.setDocumentExtractErrorDocument("Could not extract text from file \"%s\" (type: %s).");
        prompts.setAugmentedPromptTemplate("Context:\n%s\n\nQuestion: %s");
        prompts.setVisionExtractionPrompt("I need text from this image");
        ragProperties.setPrompts(prompts);

        // Avoid native PDF renderer instability (Abort trap) in unit tests:
        // we only need deterministic "PDF rendered to image attachment" behavior here.
        SpringDocumentPreprocessor preprocessorSpy = spy(new SpringDocumentPreprocessor(
                documentProcessingService,
                fileRagService,
                springAIModelRegistry,
                chatService,
                ragProperties
        ));
        lenient().doAnswer(invocation -> {
            String filename = invocation.getArgument(1, String.class);
            byte[] pngHeader = new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
            String imageFilename = String.format("page_1_%s.png", filename.replaceAll("\\.pdf$", ""));
            return List.of(new Attachment(
                    null,
                    "image/png",
                    imageFilename,
                    pngHeader.length,
                    AttachmentType.IMAGE,
                    pngHeader
            ));
        }).when(preprocessorSpy).renderPdfToImageAttachments(any(byte[].class), anyString());

        @SuppressWarnings("unchecked")
        ObjectProvider<IDocumentPreprocessor> preprocessorProvider = mock(ObjectProvider.class);
        when(preprocessorProvider.getIfAvailable()).thenReturn(preprocessorSpy);

        springAIGateway = new SpringAIGateway(
                springAIProperties,
                aiGatewayRegistry,
                springAIModelRegistry,
                chatService,
                chatMemoryProvider,
                ragProperties,
                ragProvider,
                preprocessorProvider
        );
    }

    @Test
    void whenChatAICommandWithPdfAttachment_thenProcessPdfIsCalledOnceWithNonEmptyData() {
        byte[] pdfData = new byte[]{'%', 'P', 'D', 'F', '-', '1', '.', '4', 0, 0};
        Attachment pdfAttachment = new Attachment(
                "doc/key.pdf",
                "application/pdf",
                "test.pdf",
                pdfData.length,
                AttachmentType.PDF,
                pdfData
        );
        Map<String, Object> body = new HashMap<>();
        body.put("someKey", "value");
        ChatAICommand command = new ChatAICommand(
                Set.of(ModelCapabilities.CHAT),
                Set.of(),
                0.35,
                1000,
                null,
                null,
                "What is in the file?",
                true,
                Map.of(),
                body,
                List.of(pdfAttachment)
        );

        springAIGateway.generateResponse(command);

        ArgumentCaptor<byte[]> dataCaptor = ArgumentCaptor.forClass(byte[].class);
        ArgumentCaptor<String> filenameCaptor = ArgumentCaptor.forClass(String.class);
        verify(documentProcessingService, times(1)).processPdf(dataCaptor.capture(), filenameCaptor.capture());
        assertTrue(dataCaptor.getValue().length > 0, "processPdf must be called with non-empty byte array");
        assertEquals("test.pdf", filenameCaptor.getValue());
    }

    @Test
    void whenPdfHasNoTextLayer_thenRenderedAsImagesForVision() {
        byte[] pdfData = createMinimalPdf();
        Attachment pdfAttachment = new Attachment(
                "doc/scan.pdf",
                "application/pdf",
                "scan.pdf",
                pdfData.length,
                AttachmentType.PDF,
                pdfData
        );

        // With VISION in capabilities, gateway routes PDF through image-only preprocessing path;
        // DefaultAICommandFactory would have added VISION after detecting the image-only PDF.
        Map<String, Object> body = new HashMap<>();
        body.put("someKey", "value");
        ChatAICommand command = new ChatAICommand(
                Set.of(ModelCapabilities.CHAT, ModelCapabilities.VISION),
                Set.of(),
                0.35,
                1000,
                null,
                null,
                "What is in the file?",
                true,
                Map.of(),
                body,
                List.of(pdfAttachment)
        );

        springAIGateway.generateResponse(command);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Set<ModelCapabilities>> requiredCapsCaptor = ArgumentCaptor.forClass(Set.class);
        verify(springAIModelRegistry, atLeastOnce()).getCandidatesByCapabilities(requiredCapsCaptor.capture(), isNull(), any());
        assertTrue(requiredCapsCaptor.getValue().contains(ModelCapabilities.VISION),
                "Gateway should require VISION when final user message contains rendered PDF page images");

        // Verify chatService was called with correct messages
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Message>> messagesCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(chatService, times(1)).streamChat(
                any(SpringAIModelConfig.class),
                any(),
                any(),
                messagesCaptor.capture()
        );

        List<Message> messages = messagesCaptor.getValue();
        assertNotNull(messages);
        assertTrue(messages.size() >= 2, "Should have at least SystemMessage with attachment context and UserMessage");

        // Verify SystemMessage with PDF context is present
        boolean hasSystemMessageWithPdfContext = messages.stream()
                .filter(msg -> msg instanceof SystemMessage)
                .map(msg -> ((SystemMessage) msg).getText())
                .anyMatch(text -> text.contains("PDF document") && text.contains("scan.pdf") && text.contains("images"));

        assertTrue(hasSystemMessageWithPdfContext, "Should have SystemMessage with PDF attachment context");

        // In this test ChatMemory is not used because there is no threadKey in metadata
    }

    /**
     * Reproduces the production bug: Telegram sends AUTO capability, image-only PDF adds VISION,
     * resulting in [AUTO, VISION] — which no model satisfies unless AUTO is stripped.
     * Before the fix this threw: "No model found for capabilities: [AUTO, VISION]"
     */
    @Test
    void whenAutoModeAndPdfHasNoTextLayer_thenAutoStrippedAndVisionModelSelected() {
        byte[] pdfData = createMinimalPdf();
        Attachment pdfAttachment = new Attachment(
                "doc/scan.pdf",
                "application/pdf",
                "scan.pdf",
                pdfData.length,
                AttachmentType.PDF,
                pdfData
        );

        // Configure registry: VISION model does NOT have AUTO (realistic setup matching production)
        SpringAIModelConfig visionModel = new SpringAIModelConfig();
        visionModel.setName("vision-model");
        visionModel.setCapabilities(Set.of(ModelCapabilities.CHAT, ModelCapabilities.VISION));
        visionModel.setProviderType(SpringAIModelConfig.ProviderType.OLLAMA);

        // [VISION] returns the vision model — AUTO was stripped by the factory before creating the command
        when(springAIModelRegistry.getCandidatesByCapabilities(
                eq(Set.of(ModelCapabilities.VISION)), isNull(), any()))
                .thenReturn(List.of(visionModel));
        // 2-arg variant used by extractTextFromImagesViaVision inside the preprocessor (lenient: may not trigger)
        lenient().doReturn(List.of(visionModel)).when(springAIModelRegistry)
                .getCandidatesByCapabilities(any(Set.class), nullable(String.class));

        Map<String, Object> body = new HashMap<>();
        body.put("someKey", "value");
        // DefaultAICommandFactory adds VISION after detecting the image-only PDF;
        // AUTO is the original Telegram capability, VISION is added by the factory.
        ChatAICommand command = new ChatAICommand(
                Set.of(ModelCapabilities.AUTO, ModelCapabilities.VISION),
                Set.of(),
                0.35,
                1000,
                null,
                null,
                "Analyze this document",
                true,
                Map.of(),
                body,
                List.of(pdfAttachment)
        );

        // Before the fix this threw RuntimeException: "No model found for capabilities: [AUTO, VISION]"
        springAIGateway.generateResponse(command);

        // Verify AUTO was stripped and VISION-only query was used
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Set<ModelCapabilities>> capsCaptor = ArgumentCaptor.forClass(Set.class);
        verify(springAIModelRegistry, atLeastOnce()).getCandidatesByCapabilities(capsCaptor.capture(), isNull(), any());

        Set<ModelCapabilities> lastQuery = capsCaptor.getValue();
        assertTrue(lastQuery.contains(ModelCapabilities.VISION), "Should require VISION");
        assertFalse(lastQuery.contains(ModelCapabilities.AUTO), "AUTO should be stripped when VISION is needed");

        verify(chatService, times(1)).streamChat(
                argThat(config -> "vision-model".equals(config.getName())),
                any(), any(), any());
    }

    /**
     * When vision extraction fails (e.g. model returns HTTP 500), the system should continue
     * without RAG cache. Images are still sent to the final model for direct visual analysis.
     */
    @Test
    void whenVisionExtractionFails_thenContinuesWithoutRagCache() {
        byte[] pdfData = createMinimalPdf();
        Attachment pdfAttachment = new Attachment(
                "doc/scan.pdf",
                "application/pdf",
                "scan.pdf",
                pdfData.length,
                AttachmentType.PDF,
                pdfData
        );

        // extractTextFromImagesViaVision calls the 2-arg getCandidatesByCapabilities(Set, String);
        // mock it so a vision model is found and callSimpleVision is actually invoked
        SpringAIModelConfig visionModel = new SpringAIModelConfig();
        visionModel.setName("vision-model");
        visionModel.setCapabilities(Set.of(ModelCapabilities.CHAT, ModelCapabilities.VISION));
        visionModel.setProviderType(SpringAIModelConfig.ProviderType.OLLAMA);
        doReturn(List.of(visionModel)).when(springAIModelRegistry)
                .getCandidatesByCapabilities(any(Set.class), nullable(String.class));

        // Simulate vision extraction failure (callSimpleVision throws after retries)
        when(chatService.callSimpleVision(any(), any()))
                .thenThrow(new RuntimeException("HTTP 500 - model is missing data required for image input"));

        Map<String, Object> body = new HashMap<>();
        body.put("someKey", "value");
        // DefaultAICommandFactory adds VISION after detecting the image-only PDF
        ChatAICommand command = new ChatAICommand(
                Set.of(ModelCapabilities.CHAT, ModelCapabilities.VISION),
                Set.of(),
                0.35,
                1000,
                null,
                null,
                "What is in the file?",
                true,
                Map.of(),
                body,
                List.of(pdfAttachment)
        );

        // Should NOT throw — vision extraction failure is handled gracefully
        springAIGateway.generateResponse(command);

        // Verify extractTextFromImagesViaVision was invoked (it calls the 2-arg getCandidatesByCapabilities)
        verify(springAIModelRegistry, atLeastOnce())
                .getCandidatesByCapabilities(any(Set.class), nullable(String.class));
        // Vision extraction was attempted
        verify(chatService, times(1)).callSimpleVision(any(), any());
        // No text was extracted, so processExtractedText should NOT be called
        verify(documentProcessingService, never()).processExtractedText(anyString(), anyString());
        // Final chat call still happens (images are in the payload for direct visual analysis)
        verify(chatService, times(1)).streamChat(any(), any(), any(), any());
    }

    /**
     * After successful vision extraction, images are no longer needed — the text is in RAG.
     * The final message should NOT contain images, so a TEXT model (not VISION) is selected.
     * The user gets an augmented prompt with RAG context answered by the text model.
     */
    @Test
    void whenVisionExtractionSucceeds_thenImagesRemovedAndTextModelSelected() {
        byte[] pdfData = createMinimalPdf();
        Attachment pdfAttachment = new Attachment(
                "doc/scan.pdf",
                "application/pdf",
                "scan.pdf",
                pdfData.length,
                AttachmentType.PDF,
                pdfData
        );

        // Vision model for extraction step
        SpringAIModelConfig visionModel = new SpringAIModelConfig();
        visionModel.setName("vision-model");
        visionModel.setCapabilities(Set.of(ModelCapabilities.CHAT, ModelCapabilities.VISION));
        visionModel.setProviderType(SpringAIModelConfig.ProviderType.OLLAMA);
        doReturn(List.of(visionModel)).when(springAIModelRegistry)
                .getCandidatesByCapabilities(any(Set.class), nullable(String.class));

        // Model for final answer: needs VISION in capabilities because VISION is in command.modelCapabilities()
        // (added by DefaultAICommandFactory). The key test assertion is that no images are in the final
        // message payload — not which model capability is declared.
        SpringAIModelConfig textModel = new SpringAIModelConfig();
        textModel.setName("text-model");
        textModel.setCapabilities(Set.of(ModelCapabilities.CHAT, ModelCapabilities.VISION));
        textModel.setProviderType(SpringAIModelConfig.ProviderType.OLLAMA);
        when(springAIModelRegistry.getCandidatesByCapabilities(any(Set.class), isNull(), any()))
                .thenReturn(List.of(textModel));

        // Vision extraction succeeds — text is now in RAG, images no longer needed.
        // Response must be ≥ VISION_EXTRACTION_LIKELY_COMPLETE_MIN_CHARS (600) to avoid retries.
        String visionExtractedText = "Certificate of Completion. John Doe completed Advanced Java. "
                + "This certifies that the above-named individual has successfully fulfilled all requirements "
                + "for the Advanced Java Programming course offered by OpenDaimon Academy. "
                + "The course covered topics including concurrency, design patterns, JVM internals, "
                + "performance tuning, and modern Java features from Java 17 through Java 21. "
                + "The participant demonstrated exceptional understanding of the material and completed "
                + "all practical assignments with distinction. Issued on March 15, 2025 by the Board "
                + "of Certification at OpenDaimon Academy, Silicon Valley Campus.";
        when(chatService.callSimpleVision(any(), any()))
                .thenReturn(visionExtractedText);

        when(documentProcessingService.processExtractedText(anyString(), anyString()))
                .thenReturn("vision-doc-id");

        Document visionChunk = new Document(visionExtractedText);
        when(fileRagService.findAllByDocumentId("vision-doc-id"))
                .thenReturn(List.of(visionChunk));

        Map<String, Object> body = new HashMap<>();
        body.put("someKey", "value");
        // DefaultAICommandFactory adds VISION after detecting the image-only PDF
        ChatAICommand command = new ChatAICommand(
                Set.of(ModelCapabilities.CHAT, ModelCapabilities.VISION),
                Set.of(),
                0.35,
                1000,
                null,
                null,
                "Сколько раз встречается слово Attorney?",
                true,
                Map.of(),
                body,
                List.of(pdfAttachment)
        );

        springAIGateway.generateResponse(command);

        // Vision extraction was used
        verify(chatService, times(1)).callSimpleVision(any(), any());
        // RAG stores extracted text
        verify(fileRagService, times(1)).findAllByDocumentId("vision-doc-id");
        // createAugmentedPrompt is no longer called — RAG context goes into transient SystemMessage
        verify(fileRagService, never()).createAugmentedPrompt(anyString(), anyList());

        // KEY: final message has NO images — text model is used, not vision
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Message>> messagesCaptor = ArgumentCaptor.forClass(List.class);
        verify(chatService, times(1)).streamChat(any(), any(), any(), messagesCaptor.capture());

        List<Message> finalMessages = messagesCaptor.getValue();
        boolean hasMedia = finalMessages.stream()
                .filter(UserMessage.class::isInstance)
                .map(UserMessage.class::cast)
                .anyMatch(m -> m.getMedia() != null && !m.getMedia().isEmpty());
        assertFalse(hasMedia, "After successful vision extraction, images should NOT be in the final message");
    }

    @Test
    void whenFixedModelAndPdfFallbackAddsImages_thenModelWithoutVisionFails() {
        byte[] pdfData = createMinimalPdf();
        Attachment pdfAttachment = new Attachment(
                "doc/scan.pdf",
                "application/pdf",
                "scan.pdf",
                pdfData.length,
                AttachmentType.PDF,
                pdfData
        );

        SpringAIModelConfig fixedModelWithoutVision = new SpringAIModelConfig();
        fixedModelWithoutVision.setName("fixed-chat-only");
        fixedModelWithoutVision.setCapabilities(Set.of(ModelCapabilities.CHAT));
        fixedModelWithoutVision.setProviderType(SpringAIModelConfig.ProviderType.OPENAI);
        when(springAIModelRegistry.getByModelName("fixed-chat-only"))
                .thenReturn(Optional.of(fixedModelWithoutVision));

        // DefaultAICommandFactory adds VISION after detecting the image-only PDF
        FixedModelChatAICommand command = new FixedModelChatAICommand(
                "fixed-chat-only",
                Set.of(ModelCapabilities.VISION),
                0.35,
                1000,
                null,
                null,
                "What is in the file?",
                true,
                Map.of(),
                Map.of(),
                List.of(pdfAttachment)
        );

        assertThrows(UnsupportedModelCapabilityException.class, () -> springAIGateway.generateResponse(command));
        verify(chatService, never()).streamChat(any(), any(), any(), any());
    }

    @Test
    void whenImageAttachment_thenSystemMessageWithImageContext() {
        byte[] imageData = new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        Attachment imageAttachment = new Attachment(
                "img/photo.png",
                "image/png",
                "photo.png",
                imageData.length,
                AttachmentType.IMAGE,
                imageData
        );

        Map<String, Object> body = new HashMap<>();
        body.put("someKey", "value");
        ChatAICommand command = new ChatAICommand(
                Set.of(ModelCapabilities.CHAT, ModelCapabilities.VISION),
                Set.of(),
                0.35,
                1000,
                null,
                null,
                "What is in the image?",
                true,
                Map.of(),
                body,
                List.of(imageAttachment)
        );

        springAIGateway.generateResponse(command);

        // Verify chatService was called with correct messages
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<org.springframework.ai.chat.messages.Message>> messagesCaptor = 
                ArgumentCaptor.forClass(List.class);
        verify(chatService, times(1)).streamChat(
                any(SpringAIModelConfig.class), 
                any(), 
                any(), 
                messagesCaptor.capture()
        );

        List<Message> messages = messagesCaptor.getValue();
        assertNotNull(messages);
        assertTrue(messages.size() >= 2, "Should have at least SystemMessage with attachment context and UserMessage");

        // Verify SystemMessage with image context is present
        boolean hasSystemMessageWithImageContext = messages.stream()
                .filter(msg -> msg instanceof SystemMessage)
                .map(msg -> ((SystemMessage) msg).getText())
                .anyMatch(text -> text.contains("attached") && text.contains("image"));

        assertTrue(hasSystemMessageWithImageContext, "Should have SystemMessage with image attachment context");

        // In this test ChatMemory is not used because there is no threadKey in metadata
    }

    /**
     * When a PDF is processed for RAG, the gateway should publish the documentId into
     * command.metadata() so the caller can persist it on the USER message.
     * The UserMessage should NOT contain the full document text — only a placeholder reference.
     */
    @Test
    void whenPdfProcessedForRag_thenDocumentIdStoredInCommandMetadataAndUserMessageHasPlaceholder() {
        byte[] pdfData = new byte[]{'%', 'P', 'D', 'F', '-', '1', '.', '4', 0, 0};
        Attachment pdfAttachment = new Attachment(
                "doc/key.pdf",
                "application/pdf",
                "report.pdf",
                pdfData.length,
                AttachmentType.PDF,
                pdfData
        );

        when(documentProcessingService.processPdf(any(byte[].class), anyString())).thenReturn("rag-doc-123");
        // Preprocessor calls findAllByDocumentId (not findRelevantContext) to retrieve chunks
        when(fileRagService.findAllByDocumentId("rag-doc-123"))
                .thenReturn(List.of(new Document("chunk text from document")));

        Map<String, String> metadata = new HashMap<>();
        metadata.put("threadKey", "thread-abc");

        ChatAICommand command = new ChatAICommand(
                Set.of(ModelCapabilities.CHAT),
                Set.of(),
                0.35,
                1000,
                null,
                null,
                "What is in the file?",
                true,
                metadata,
                Map.of(),
                List.of(pdfAttachment)
        );

        springAIGateway.generateResponse(command);

        // Verify documentId was published into command.metadata() for the caller to persist
        assertTrue(metadata.containsKey("ragDocumentIds"),
                "command.metadata() should contain ragDocumentIds after RAG processing");
        assertTrue(metadata.get("ragDocumentIds").contains("rag-doc-123"),
                "ragDocumentIds should contain the processed documentId");

        // Verify UserMessage does NOT contain full document text (inline RAG)
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Message>> messagesCaptor = ArgumentCaptor.forClass(List.class);
        verify(chatService, times(1)).streamChat(any(), any(), any(), messagesCaptor.capture());

        List<Message> finalMessages = messagesCaptor.getValue();
        String userMessageText = finalMessages.stream()
                .filter(UserMessage.class::isInstance)
                .map(UserMessage.class::cast)
                .map(UserMessage::getText)
                .findFirst()
                .orElse("");

        // RAG context is now prepended to the UserMessage (not a separate SystemMessage)
        // so that small models (qwen2.5:3b) reliably see the context.
        assertTrue(userMessageText.contains("chunk text from document"),
                "UserMessage should contain RAG context as prefix");
        assertTrue(userMessageText.contains("Context:"),
                "UserMessage should contain context header from augmented-prompt-template");
        assertTrue(userMessageText.contains("report.pdf"),
                "UserMessage should contain a placeholder referencing the document");
    }

    /**
     * On follow-up messages (no new attachments), if the handler has injected RAG documentIds
     * into command.metadata["ragDocumentIds"], the gateway should fetch relevant chunks from
     * VectorStore and inject them as a prefix in the UserMessage.
     */
    @Test
    void whenFollowUpMessageWithRagDocumentIdsInMetadata_thenFetchesFromVectorStore() {
        // No attachments — this is a follow-up message
        when(fileRagService.findAllByDocumentId("rag-doc-456"))
                .thenReturn(List.of(new Document("Invoice total: $5,000")));

        Map<String, String> metadata = new HashMap<>();
        metadata.put("threadKey", "thread-xyz");
        // Handler resolves RAG documentIds from message history and injects them here
        metadata.put("ragDocumentIds", "rag-doc-456");

        ChatAICommand command = new ChatAICommand(
                Set.of(ModelCapabilities.CHAT),
                Set.of(),
                0.35,
                1000,
                null,
                null,
                "What was the total?",
                true,
                metadata,
                Map.of(),
                List.of()  // No attachments — follow-up
        );

        springAIGateway.generateResponse(command);

        // Should fetch ALL chunks by documentId (threshold=0.0) to bypass cross-language similarity mismatch
        verify(fileRagService).findAllByDocumentId("rag-doc-456");

        // Should inject RAG context as prefix in UserMessage (not SystemMessage)
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Message>> messagesCaptor = ArgumentCaptor.forClass(List.class);
        verify(chatService, times(1)).streamChat(any(), any(), any(), messagesCaptor.capture());

        List<Message> finalMessages = messagesCaptor.getValue();
        String userMessageText = finalMessages.stream()
                .filter(UserMessage.class::isInstance)
                .map(UserMessage.class::cast)
                .map(UserMessage::getText)
                .findFirst()
                .orElse("");
        assertTrue(userMessageText.contains("Invoice total: $5,000"),
                "UserMessage should contain RAG document content as prefix");
        assertTrue(userMessageText.contains("Context:"),
                "UserMessage should contain context header from augmented-prompt-template");
        assertTrue(userMessageText.contains("What was the total?"),
                "UserMessage should contain the original query after RAG prefix");
    }

    @Test
    void whenBothImageAndPdfAsImage_thenSystemMessageWithBothContexts() {
        byte[] imageData = new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        Attachment imageAttachment = new Attachment(
                "img/photo.png",
                "image/png",
                "photo.png",
                imageData.length,
                AttachmentType.IMAGE,
                imageData
        );

        byte[] pdfData = createMinimalPdf();
        Attachment pdfAttachment = new Attachment(
                "doc/scan.pdf",
                "application/pdf",
                "scan.pdf",
                pdfData.length,
                AttachmentType.PDF,
                pdfData
        );

        Map<String, Object> body = new HashMap<>();
        body.put("someKey", "value");
        ChatAICommand command = new ChatAICommand(
                Set.of(ModelCapabilities.CHAT, ModelCapabilities.VISION),
                Set.of(),
                0.35,
                1000,
                null,
                null,
                "What is in the files?",
                true,
                Map.of(),
                body,
                List.of(imageAttachment, pdfAttachment)
        );

        springAIGateway.generateResponse(command);

        // Verify chatService was called with correct messages
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<org.springframework.ai.chat.messages.Message>> messagesCaptor = 
                ArgumentCaptor.forClass(List.class);
        verify(chatService, times(1)).streamChat(
                any(SpringAIModelConfig.class), 
                any(), 
                any(), 
                messagesCaptor.capture()
        );

        List<Message> messages = messagesCaptor.getValue();
        assertNotNull(messages);

        // Verify SystemMessage with both PDF and image context is present
        boolean hasSystemMessageWithBothContexts = messages.stream()
                .filter(msg -> msg instanceof SystemMessage)
                .map(msg -> ((SystemMessage) msg).getText())
                .anyMatch(text -> text.contains("PDF document") && text.contains("scan.pdf") && 
                                 text.contains("attached") && text.contains("image"));

        assertTrue(hasSystemMessageWithBothContexts, "Should have SystemMessage with both PDF and image contexts");

        // In this test ChatMemory is not used because there is no threadKey in metadata
    }
}
