package io.github.ngirchev.opendaimon.ai.springai.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;
import io.github.ngirchev.opendaimon.ai.springai.config.SpringAIModelConfig;
import io.github.ngirchev.opendaimon.ai.springai.config.SpringAIProperties;
import io.github.ngirchev.opendaimon.ai.springai.retry.SpringAIModelRegistry;
import io.github.ngirchev.opendaimon.common.ai.ModelCapabilities;
import io.github.ngirchev.opendaimon.common.ai.command.ChatAICommand;
import io.github.ngirchev.opendaimon.common.ai.response.SpringAIStreamResponse;
import io.github.ngirchev.opendaimon.common.model.Attachment;
import io.github.ngirchev.opendaimon.common.model.AttachmentType;
import io.github.ngirchev.opendaimon.common.service.AIGatewayRegistry;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for SpringAIGateway behavior when handling commands that have already been
 * processed by AIRequestPipeline (document orchestration happens in the pipeline, not here).
 *
 * Verifies:
 * 1. Gateway passes through augmented userRole text to UserMessage
 * 2. When pdfAsImageFilenames metadata is present, gateway adds PDF context SystemMessage
 * 3. Image attachments (pre-processed from PDF by pipeline) flow through to UserMessage
 * 4. When VISION capability is present and images are in attachments, a vision model is selected
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
    private SpringAIGateway springAIGateway;

    @BeforeEach
    void setUp() {
        when(springAIProperties.getMock()).thenReturn(false);

        @SuppressWarnings("unchecked")
        ObjectProvider<ChatMemory> chatMemoryProvider = mock(ObjectProvider.class);
        lenient().when(chatMemoryProvider.getIfAvailable()).thenReturn(null);

        SpringAIModelConfig modelConfig = new SpringAIModelConfig();
        modelConfig.setName("test-model");
        modelConfig.setCapabilities(Set.of(ModelCapabilities.CHAT, ModelCapabilities.VISION));
        modelConfig.setProviderType(SpringAIModelConfig.ProviderType.OPENAI);
        lenient().when(springAIModelRegistry.getCandidatesByCapabilities(any(), any(), any())).thenReturn(List.of(modelConfig));

        lenient().when(chatService.streamChat(any(), any(), any(), any())).thenReturn(new SpringAIStreamResponse(Flux.empty()));

        springAIGateway = new SpringAIGateway(
                springAIProperties,
                aiGatewayRegistry,
                springAIModelRegistry,
                chatService,
                chatMemoryProvider
        );
    }

    /**
     * Gateway must pass through the augmented userRole text unchanged to the UserMessage.
     * The pipeline augments userRole with RAG context before the command reaches the gateway.
     */
    @Test
    void whenCommandHasAugmentedUserRole_thenUserMessageContainsAugmentedText() {
        String augmentedUserRole = "Context from document:\nSome PDF content here.\n\nUser question: What is in the file?";

        ChatAICommand command = new ChatAICommand(
                Set.of(ModelCapabilities.CHAT), Set.of(),
                0.35, 1000, null,
                "You are a helpful assistant", augmentedUserRole,
                true, Map.of(), new HashMap<>(), List.of()
        );

        springAIGateway.generateResponse(command);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Message>> messagesCaptor = ArgumentCaptor.forClass(List.class);
        verify(chatService, times(1)).streamChat(any(), any(), any(), messagesCaptor.capture());

        List<Message> messages = messagesCaptor.getValue();
        boolean hasAugmentedUserMessage = messages.stream()
                .filter(UserMessage.class::isInstance)
                .map(UserMessage.class::cast)
                .anyMatch(m -> augmentedUserRole.equals(m.getText()));
        assertTrue(hasAugmentedUserMessage,
                "UserMessage must contain the augmented userRole text passed by pipeline");
    }

    /**
     * When the command metadata contains pdfAsImageFilenames (set by pipeline after PDF-to-image conversion),
     * the gateway must add a SystemMessage with PDF attachment context before the UserMessage.
     */
    @Test
    void whenMetadataHasPdfAsImageFilenames_thenSystemMessageWithPdfContextAdded() {
        byte[] pngHeader = new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        Attachment imageAttachment = new Attachment(
                null, "image/png", "page_1_scan.png", pngHeader.length, AttachmentType.IMAGE, pngHeader
        );

        Map<String, String> metadata = new HashMap<>();
        metadata.put("pdfAsImageFilenames", "scan.pdf");

        ChatAICommand command = new ChatAICommand(
                Set.of(ModelCapabilities.CHAT, ModelCapabilities.VISION), Set.of(),
                0.35, 1000, null,
                "You are a helpful assistant", "What is in the document?",
                true, metadata, new HashMap<>(), List.of(imageAttachment)
        );

        springAIGateway.generateResponse(command);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Message>> messagesCaptor = ArgumentCaptor.forClass(List.class);
        verify(chatService, times(1)).streamChat(any(), any(), any(), messagesCaptor.capture());

        List<Message> messages = messagesCaptor.getValue();
        boolean hasSystemMessageWithPdfContext = messages.stream()
                .filter(SystemMessage.class::isInstance)
                .map(SystemMessage.class::cast)
                .anyMatch(m -> m.getText().contains("PDF document") && m.getText().contains("scan.pdf") && m.getText().contains("images"));
        assertTrue(hasSystemMessageWithPdfContext,
                "Gateway must add a SystemMessage with PDF context when pdfAsImageFilenames metadata is present");
    }

    /**
     * Image attachments pre-processed by the pipeline (PDF rendered to images) must be forwarded
     * to the UserMessage so the vision model can analyze them.
     */
    @Test
    void whenImageAttachmentsPresent_thenUserMessageHasMedia() {
        byte[] pngHeader = new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        Attachment imageAttachment = new Attachment(
                null, "image/png", "page_1_scan.png", pngHeader.length, AttachmentType.IMAGE, pngHeader
        );

        Map<String, String> metadata = new HashMap<>();
        metadata.put("pdfAsImageFilenames", "scan.pdf");

        ChatAICommand command = new ChatAICommand(
                Set.of(ModelCapabilities.CHAT, ModelCapabilities.VISION), Set.of(),
                0.35, 1000, null,
                null, "Describe the document",
                true, metadata, new HashMap<>(), List.of(imageAttachment)
        );

        springAIGateway.generateResponse(command);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Message>> messagesCaptor = ArgumentCaptor.forClass(List.class);
        verify(chatService, times(1)).streamChat(any(), any(), any(), messagesCaptor.capture());

        List<Message> messages = messagesCaptor.getValue();
        boolean hasMediaInUserMessage = messages.stream()
                .filter(UserMessage.class::isInstance)
                .map(UserMessage.class::cast)
                .anyMatch(m -> m.getMedia() != null && !m.getMedia().isEmpty());
        assertTrue(hasMediaInUserMessage,
                "UserMessage must carry image media when image attachments are present");
    }

    /**
     * When a command carries VISION capability and image attachments, the model selection
     * must require VISION (images present in payload take priority over AUTO).
     */
    @Test
    void whenVisionCapabilityAndImages_thenVisionModelSelected() {
        byte[] pngHeader = new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        Attachment imageAttachment = new Attachment(
                null, "image/png", "page_1_scan.png", pngHeader.length, AttachmentType.IMAGE, pngHeader
        );

        ChatAICommand command = new ChatAICommand(
                Set.of(ModelCapabilities.CHAT, ModelCapabilities.VISION), Set.of(),
                0.35, 1000, null,
                null, "Describe the document",
                true, Map.of(), new HashMap<>(), List.of(imageAttachment)
        );

        springAIGateway.generateResponse(command);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Set<ModelCapabilities>> capsCaptor = ArgumentCaptor.forClass(Set.class);
        verify(springAIModelRegistry, atLeastOnce()).getCandidatesByCapabilities(capsCaptor.capture(), isNull(), any());

        Set<ModelCapabilities> usedCaps = capsCaptor.getValue();
        assertTrue(usedCaps.contains(ModelCapabilities.VISION),
                "VISION must be in capability query when image attachments are present");
    }

    /**
     * When the command has no attachments (plain text, PDF already extracted to RAG by pipeline),
     * the UserMessage must not carry any media.
     */
    @Test
    void whenNoAttachments_thenUserMessageHasNoMedia() {
        ChatAICommand command = new ChatAICommand(
                Set.of(ModelCapabilities.CHAT), Set.of(),
                0.35, 1000, null,
                "You are a helpful assistant", "What is 2+2?",
                false, Map.of(), new HashMap<>(), List.of()
        );

        springAIGateway.generateResponse(command);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Message>> messagesCaptor = ArgumentCaptor.forClass(List.class);
        verify(chatService, times(1)).callChat(any(), any(), any(), messagesCaptor.capture());

        List<Message> messages = messagesCaptor.getValue();
        boolean hasMedia = messages.stream()
                .filter(UserMessage.class::isInstance)
                .map(UserMessage.class::cast)
                .anyMatch(m -> m.getMedia() != null && !m.getMedia().isEmpty());
        assertFalse(hasMedia, "UserMessage must have no media when there are no attachments");
    }

    /**
     * When there are no attachments and no pdfAsImageFilenames in metadata,
     * no extra SystemMessage for PDF context should be added.
     */
    @Test
    void whenNoPdfMetadata_thenNoPdfContextSystemMessage() {
        ChatAICommand command = new ChatAICommand(
                Set.of(ModelCapabilities.CHAT), Set.of(),
                0.35, 1000, null,
                "You are a helpful assistant", "Hello",
                false, Map.of(), new HashMap<>(), List.of()
        );

        springAIGateway.generateResponse(command);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Message>> messagesCaptor = ArgumentCaptor.forClass(List.class);
        verify(chatService, times(1)).callChat(any(), any(), any(), messagesCaptor.capture());

        List<Message> messages = messagesCaptor.getValue();
        boolean hasPdfContextMessage = messages.stream()
                .filter(SystemMessage.class::isInstance)
                .map(SystemMessage.class::cast)
                .anyMatch(m -> m.getText().contains("PDF document"));
        assertFalse(hasPdfContextMessage, "No PDF context SystemMessage should be added without pdfAsImageFilenames metadata");
    }
}
