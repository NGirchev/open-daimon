package io.github.ngirchev.aibot.ai.springai.service;

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
import io.github.ngirchev.aibot.ai.springai.config.RAGProperties;
import io.github.ngirchev.aibot.ai.springai.config.SpringAIModelConfig;
import io.github.ngirchev.aibot.ai.springai.config.SpringAIProperties;
import io.github.ngirchev.aibot.ai.springai.rag.FileRAGService;
import io.github.ngirchev.aibot.ai.springai.retry.SpringAIModelRegistry;
import io.github.ngirchev.aibot.common.ai.ModelCapabilities;
import io.github.ngirchev.aibot.common.ai.command.ChatAICommand;
import io.github.ngirchev.aibot.common.ai.response.SpringAIStreamResponse;
import io.github.ngirchev.aibot.common.exception.DocumentContentNotExtractableException;
import io.github.ngirchev.aibot.common.model.Attachment;
import io.github.ngirchev.aibot.common.model.AttachmentType;
import io.github.ngirchev.aibot.common.service.AIGatewayRegistry;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

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

    @BeforeEach
    void setUp() {
        when(springAIProperties.getMock()).thenReturn(false);

        @SuppressWarnings("unchecked")
        ObjectProvider<DocumentProcessingService> docProvider = mock(ObjectProvider.class);
        when(docProvider.getIfAvailable()).thenReturn(documentProcessingService);

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
        modelConfig.setCapabilities(List.of(ModelCapabilities.CHAT));
        modelConfig.setProviderType(SpringAIModelConfig.ProviderType.OPENAI);
        when(springAIModelRegistry.getCandidatesByCapabilities(any(), any())).thenReturn(List.of(modelConfig));

        when(chatService.streamChat(any(), any(), any(), any())).thenReturn(new SpringAIStreamResponse(Flux.empty()));

        RAGProperties ragProperties = new RAGProperties();
        ragProperties.setChunkSize(800);
        ragProperties.setChunkOverlap(100);
        ragProperties.setTopK(5);
        ragProperties.setSimilarityThreshold(0.7);
        RAGProperties.RAGPrompts prompts = new RAGProperties.RAGPrompts();
        prompts.setDocumentExtractErrorPdf("Could not extract text from file \"%s\".");
        prompts.setDocumentExtractErrorDocument("Could not extract text from file \"%s\" (type: %s).");
        prompts.setAugmentedPromptTemplate("Context:\n%s\n\nQuestion: %s");
        ragProperties.setPrompts(prompts);

        springAIGateway = new SpringAIGateway(
                springAIProperties,
                aiGatewayRegistry,
                springAIModelRegistry,
                chatService,
                chatMemoryProvider,
                ragProperties,
                docProvider,
                ragProvider
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
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("someKey", "value");
        ChatAICommand command = new ChatAICommand(
                Set.of(ModelCapabilities.CHAT),
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
        byte[] pdfData = new byte[]{'%', 'P', 'D', 'F', '-', '1', '.', '4', 0, 0};
        Attachment pdfAttachment = new Attachment(
                "doc/scan.pdf",
                "application/pdf",
                "scan.pdf",
                pdfData.length,
                AttachmentType.PDF,
                pdfData
        );

        when(documentProcessingService.processPdf(any(byte[].class), anyString()))
                .thenThrow(new DocumentContentNotExtractableException("No text in PDF"));

        Map<String, Object> body = new java.util.HashMap<>();
        body.put("someKey", "value");
        ChatAICommand command = new ChatAICommand(
                Set.of(ModelCapabilities.CHAT, ModelCapabilities.VISION),
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

        verify(documentProcessingService, times(1)).processPdf(any(byte[].class), eq("scan.pdf"));
        
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
        
        List<org.springframework.ai.chat.messages.Message> messages = messagesCaptor.getValue();
        assertNotNull(messages);
        assertTrue(messages.size() >= 2, "Should have at least SystemMessage with attachment context and UserMessage");
        
        // Verify SystemMessage with PDF context is present
        boolean hasSystemMessageWithPdfContext = messages.stream()
                .filter(msg -> msg instanceof org.springframework.ai.chat.messages.SystemMessage)
                .map(msg -> ((org.springframework.ai.chat.messages.SystemMessage) msg).getText())
                .anyMatch(text -> text.contains("PDF document") && text.contains("scan.pdf") && text.contains("images"));
        
        assertTrue(hasSystemMessageWithPdfContext, "Should have SystemMessage with PDF attachment context");

        // In this test ChatMemory is not used because there is no threadKey in metadata
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

        Map<String, Object> body = new java.util.HashMap<>();
        body.put("someKey", "value");
        ChatAICommand command = new ChatAICommand(
                Set.of(ModelCapabilities.CHAT, ModelCapabilities.VISION),
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

        List<org.springframework.ai.chat.messages.Message> messages = messagesCaptor.getValue();
        assertNotNull(messages);
        assertTrue(messages.size() >= 2, "Should have at least SystemMessage with attachment context and UserMessage");

        // Verify SystemMessage with image context is present
        boolean hasSystemMessageWithImageContext = messages.stream()
                .filter(msg -> msg instanceof org.springframework.ai.chat.messages.SystemMessage)
                .map(msg -> ((org.springframework.ai.chat.messages.SystemMessage) msg).getText())
                .anyMatch(text -> text.contains("attached") && text.contains("image"));

        assertTrue(hasSystemMessageWithImageContext, "Should have SystemMessage with image attachment context");

        // In this test ChatMemory is not used because there is no threadKey in metadata
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

        byte[] pdfData = new byte[]{'%', 'P', 'D', 'F', '-', '1', '.', '4', 0, 0};
        Attachment pdfAttachment = new Attachment(
                "doc/scan.pdf",
                "application/pdf",
                "scan.pdf",
                pdfData.length,
                AttachmentType.PDF,
                pdfData
        );

        when(documentProcessingService.processPdf(any(byte[].class), anyString()))
                .thenThrow(new DocumentContentNotExtractableException("No text in PDF"));

        Map<String, Object> body = new java.util.HashMap<>();
        body.put("someKey", "value");
        ChatAICommand command = new ChatAICommand(
                Set.of(ModelCapabilities.CHAT, ModelCapabilities.VISION),
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

        List<org.springframework.ai.chat.messages.Message> messages = messagesCaptor.getValue();
        assertNotNull(messages);

        // Verify SystemMessage with both PDF and image context is present
        boolean hasSystemMessageWithBothContexts = messages.stream()
                .filter(msg -> msg instanceof org.springframework.ai.chat.messages.SystemMessage)
                .map(msg -> ((org.springframework.ai.chat.messages.SystemMessage) msg).getText())
                .anyMatch(text -> text.contains("PDF document") && text.contains("scan.pdf") && 
                                 text.contains("attached") && text.contains("image"));

        assertTrue(hasSystemMessageWithBothContexts, "Should have SystemMessage with both PDF and image contexts");

        // In this test ChatMemory is not used because there is no threadKey in metadata
    }
}
