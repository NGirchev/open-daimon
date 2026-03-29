package io.github.ngirchev.opendaimon.ai.springai.service;

import io.github.ngirchev.opendaimon.ai.springai.config.SpringAIModelConfig;
import io.github.ngirchev.opendaimon.ai.springai.config.SpringAIProperties;
import io.github.ngirchev.opendaimon.ai.springai.retry.SpringAIModelRegistry;
import io.github.ngirchev.opendaimon.common.ai.ModelCapabilities;
import io.github.ngirchev.opendaimon.common.ai.command.AICommand;
import io.github.ngirchev.opendaimon.common.ai.command.ChatAICommand;
import io.github.ngirchev.opendaimon.common.ai.command.FixedModelChatAICommand;
import io.github.ngirchev.opendaimon.common.ai.response.AIResponse;
import io.github.ngirchev.opendaimon.common.ai.response.SpringAIResponse;
import io.github.ngirchev.opendaimon.common.ai.response.SpringAIStreamResponse;
import io.github.ngirchev.opendaimon.common.exception.UnsupportedModelCapabilityException;
import io.github.ngirchev.opendaimon.common.model.Attachment;
import io.github.ngirchev.opendaimon.common.model.AttachmentType;
import io.github.ngirchev.opendaimon.common.service.AIGatewayRegistry;
import reactor.core.publisher.Flux;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.github.ngirchev.opendaimon.common.ai.LlmParamNames.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

import java.util.Optional;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SpringAIGatewayTest {

    @Mock
    private SpringAIProperties springAIProperties;

    @Mock
    private AIGatewayRegistry aiGatewayRegistry;

    @Mock
    private SpringAIModelRegistry springAIModelRegistry;

    @Mock
    private SpringAIChatService chatService;

    private SpringAIGateway gateway;
    private SpringAIModelConfig modelConfig;

    @BeforeEach
    void setUp() {
        when(springAIProperties.getMock()).thenReturn(false);
        modelConfig = new SpringAIModelConfig();
        modelConfig.setName("test-model");
        modelConfig.setCapabilities(Set.of(ModelCapabilities.CHAT));
        modelConfig.setProviderType(SpringAIModelConfig.ProviderType.OLLAMA);
        modelConfig.setPriority(1);
        when(springAIModelRegistry.getCandidatesByCapabilities(any(), any(), any())).thenReturn(List.of(modelConfig));
        when(springAIModelRegistry.getCandidatesByCapabilities(any(), any())).thenReturn(List.of(modelConfig));
        when(springAIModelRegistry.getByModelName(any())).thenReturn(java.util.Optional.of(modelConfig));

        @SuppressWarnings("unchecked")
        ObjectProvider<org.springframework.ai.chat.memory.ChatMemory> chatMemoryProvider = mock(ObjectProvider.class);
        when(chatMemoryProvider.getIfAvailable()).thenReturn(null);

        @SuppressWarnings("unchecked")
        ObjectProvider<DocumentProcessingService> docProvider = mock(ObjectProvider.class);
        when(docProvider.getIfAvailable()).thenReturn(null);

        @SuppressWarnings("unchecked")
        ObjectProvider<io.github.ngirchev.opendaimon.ai.springai.rag.FileRAGService> ragProvider = mock(ObjectProvider.class);
        when(ragProvider.getIfAvailable()).thenReturn(null);

        gateway = new SpringAIGateway(
                springAIProperties,
                aiGatewayRegistry,
                springAIModelRegistry,
                chatService,
                chatMemoryProvider,
                null,
                docProvider,
                ragProvider
        );
    }

    @Test
    void init_registersGatewayWithRegistry() {
        gateway.init();
        verify(aiGatewayRegistry, times(1)).registerAiGateway(eq(gateway));
    }

    @Test
    void supports_emptyCandidatesReturnsFalse() {
        when(springAIModelRegistry.getCandidatesByCapabilities(any(), any())).thenReturn(List.of());
        AICommand command = new ChatAICommand(Set.of(ModelCapabilities.CHAT), 0.7, 1000, null, "Hi", false, Map.of(), Map.of());
        assertFalse(gateway.supports(command));
    }

    @Test
    void supports_hasCandidatesReturnsTrue() {
        AICommand command = new ChatAICommand(Set.of(ModelCapabilities.CHAT), 0.7, 1000, null, "Hi", false, Map.of(), Map.of());
        assertTrue(gateway.supports(command));
    }

    @Test
    void generateResponse_mockTrueReturnsMockResponse() {
        when(springAIProperties.getMock()).thenReturn(true);
        ChatAICommand command = new ChatAICommand(
                Set.of(ModelCapabilities.CHAT), 0.7, 1000,
                "System", "User msg", false, Map.of(), Map.of()
        );
        AIResponse response = gateway.generateResponse(command);
        assertNotNull(response);
        assertInstanceOf(SpringAIResponse.class, response);
        assertEquals("Mocked response", ((SpringAIResponse) response).chatResponse().getResult().getOutput().getText());
        verify(chatService, never()).callChat(any(), any(), any(), any());
        verify(chatService, never()).streamChat(any(), any(), any(), any());
    }

    @Test
    void generateResponse_mapBodyWithModelKey_callsChatService() {
        when(springAIProperties.getMock()).thenReturn(false);
        ChatResponse chatResponse = ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage("OK"))))
                .build();
        when(chatService.callChatFromBody(any(), any(), any(), anyBoolean(), any())).thenReturn(new SpringAIResponse(chatResponse));

        Map<String, Object> body = Map.of(
                MODEL, "test-model",
                MESSAGES, List.of(
                        Map.of(ROLE, "user", CONTENT, "Hello")
                )
        );
        AIResponse response = gateway.generateResponse(body);
        assertNotNull(response);
        assertInstanceOf(SpringAIResponse.class, response);
        verify(chatService, times(1)).callChatFromBody(eq(modelConfig), eq(body), isNull(), eq(true), any());
    }

    @Test
    void generateResponse_mapBodyWithOptionsModel_callsChatService() {
        when(springAIProperties.getMock()).thenReturn(false);
        ChatResponse chatResponse = ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage("OK"))))
                .build();
        when(chatService.callChatFromBody(any(), any(), any(), anyBoolean(), any())).thenReturn(new SpringAIResponse(chatResponse));

        Map<String, Object> options = Map.of(MODEL, "test-model");
        Map<String, Object> body = Map.of(
                OPTIONS, options,
                MESSAGES, List.of(Map.of(ROLE, "user", CONTENT, "Hi"))
        );
        AIResponse response = gateway.generateResponse(body);
        assertNotNull(response);
        verify(chatService, times(1)).callChatFromBody(eq(modelConfig), eq(body), isNull(), eq(true), any());
    }

    @Test
    void generateResponse_mapBodyMissingModel_throws() {
        Map<String, Object> body = Map.of(MESSAGES, List.of(Map.of(ROLE, "user", CONTENT, "Hi")));
        RuntimeException ex = assertThrows(RuntimeException.class, () -> gateway.generateResponse(body));
        assertInstanceOf(IllegalArgumentException.class, ex.getCause());
        assertTrue(ex.getCause().getMessage().contains("Model name is required"));
    }

    @Test
    void generateResponse_mapBodyEmptyMessages_createsEmptyList() {
        when(springAIProperties.getMock()).thenReturn(false);
        ChatResponse chatResponse = ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage("OK"))))
                .build();
        when(chatService.callChatFromBody(any(), any(), any(), anyBoolean(), any())).thenReturn(new SpringAIResponse(chatResponse));

        Map<String, Object> body = Map.of(MODEL, "test-model");
        AIResponse response = gateway.generateResponse(body);
        assertNotNull(response);
        ArgumentCaptor<List> messagesCaptor = ArgumentCaptor.forClass(List.class);
        verify(chatService).callChatFromBody(eq(modelConfig), eq(body), isNull(), eq(true), messagesCaptor.capture());
        assertTrue(messagesCaptor.getValue().isEmpty());
    }

    @Test
    void generateResponse_mapBodyContentParts_userMessageWithMedia() {
        when(springAIProperties.getMock()).thenReturn(false);
        ChatResponse chatResponse = ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage("OK"))))
                .build();
        when(chatService.callChatFromBody(any(), any(), any(), anyBoolean(), any())).thenReturn(new SpringAIResponse(chatResponse));

        List<Map<String, Object>> contentParts = List.of(
                Map.of("type", "text", "text", "Describe this"),
                Map.of("type", "image_url", "image_url", Map.of("url", "data:image/png;base64,aGk="))
        );
        Map<String, Object> body = Map.of(
                MODEL, "test-model",
                MESSAGES, List.of(Map.of(ROLE, "user", CONTENT, contentParts))
        );
        AIResponse response = gateway.generateResponse(body);
        assertNotNull(response);
        ArgumentCaptor<List> messagesCaptor = ArgumentCaptor.forClass(List.class);
        verify(chatService).callChatFromBody(eq(modelConfig), eq(body), isNull(), eq(true), messagesCaptor.capture());
        assertEquals(1, messagesCaptor.getValue().size());
    }

    @Test
    void generateResponse_command_noCandidates_throws() {
        when(springAIProperties.getMock()).thenReturn(false);
        when(springAIModelRegistry.getCandidatesByCapabilities(any(), any(), any())).thenReturn(List.of());
        ChatAICommand command = new ChatAICommand(
                Set.of(ModelCapabilities.CHAT), 0.7, 1000, "Sys", "User", false, Map.of(), Map.of());
        RuntimeException ex = assertThrows(RuntimeException.class, () -> gateway.generateResponse(command));
        assertNotNull(ex.getCause());
        assertTrue(ex.getCause().getMessage().contains("No model found"));
        verify(chatService, never()).callChat(any(), any(), any(), any());
        verify(chatService, never()).streamChat(any(), any(), any(), any());
    }

    @Test
    void generateResponse_commandWithStreamTrue_callsStreamChat() {
        when(springAIProperties.getMock()).thenReturn(false);
        when(chatService.streamChat(any(), any(), any(), any())).thenReturn(new SpringAIStreamResponse(Flux.empty()));
        ChatAICommand command = new ChatAICommand(
                Set.of(ModelCapabilities.CHAT), 0.7, 1000,
                "System", "User", true, Map.of(), Map.of()
        );
        AIResponse response = gateway.generateResponse(command);
        assertNotNull(response);
        verify(chatService, times(1)).streamChat(eq(modelConfig), eq(command), any(), any());
        verify(chatService, never()).callChat(any(), any(), any(), any());
    }

    @Test
    void generateResponse_commandOptionsNotOpenDaimonChatOptions_throws() {
        AICommand command = mock(AICommand.class);
        when(command.options()).thenReturn(null);
        when(command.modelCapabilities()).thenReturn(Set.of(ModelCapabilities.CHAT));
        RuntimeException ex = assertThrows(RuntimeException.class, () -> gateway.generateResponse(command));
        assertInstanceOf(IllegalArgumentException.class, ex.getCause());
    }

    @Test
    void generateResponse_mapBody_unknownModel_throws() {
        when(springAIProperties.getMock()).thenReturn(false);
        when(springAIModelRegistry.getByModelName(eq("unknown-model"))).thenReturn(Optional.empty());
        when(springAIModelRegistry.getCandidatesByCapabilities(eq(Set.of(ModelCapabilities.AUTO)), any())).thenReturn(List.of());
        Map<String, Object> body = Map.of(MODEL, "unknown-model", MESSAGES, List.of());
        RuntimeException ex = assertThrows(RuntimeException.class, () -> gateway.generateResponse(body));
        assertInstanceOf(IllegalArgumentException.class, ex.getCause());
        assertTrue(ex.getCause().getMessage().contains("Unknown model"));
    }

    // -----------------------------------------------------------------------
    // FixedModelChatAICommand tests — bypass capability filter via command type
    // -----------------------------------------------------------------------

    private FixedModelChatAICommand fixedCommand(String modelId, List<Attachment> attachments) {
        return new FixedModelChatAICommand(
                modelId, Set.of(), 0.7, 1000, null, "Sys", "User", false, Map.of(), Map.of(), attachments);
    }

    @Test
    void fixedModel_bypassesCapabilityFilter_usesModelDirectly() {
        // qwen has only CHAT — capability filter would exclude it for {CHAT, WEB}.
        // FixedModelChatAICommand must call getByModelName() directly.
        SpringAIModelConfig qwen = new SpringAIModelConfig();
        qwen.setName("qwen3.5");
        qwen.setCapabilities(Set.of(ModelCapabilities.CHAT));
        qwen.setProviderType(SpringAIModelConfig.ProviderType.OPENAI);
        qwen.setPriority(1);

        when(springAIModelRegistry.getByModelName(eq("qwen3.5"))).thenReturn(Optional.of(qwen));
        when(chatService.callChat(any(), any(), any(), any())).thenReturn(
                new SpringAIResponse(ChatResponse.builder()
                        .generations(List.of(new Generation(new AssistantMessage("OK"))))
                        .build()));

        FixedModelChatAICommand command = fixedCommand("qwen3.5", List.of());
        AIResponse response = gateway.generateResponse(command);

        assertNotNull(response);
        verify(springAIModelRegistry, never()).getCandidatesByCapabilities(any(), any(), any());
        verify(springAIModelRegistry).getByModelName(eq("qwen3.5"));
        verify(chatService).callChat(eq(qwen), eq(command), any(), any());
    }

    @Test
    void fixedModel_notFoundInRegistry_throwsRuntimeException() {
        when(springAIModelRegistry.getByModelName(eq("unknown-model"))).thenReturn(Optional.empty());

        FixedModelChatAICommand command = fixedCommand("unknown-model", List.of());
        RuntimeException ex = assertThrows(RuntimeException.class, () -> gateway.generateResponse(command));
        assertTrue(ex.getMessage().contains("Failed to generate response from Spring AI"));
        assertTrue(ex.getCause().getMessage().contains("Selected model not found in registry: unknown-model"));
    }

    @Test
    void fixedModel_withImageAttachment_modelLacksVision_throwsUnsupportedModelCapabilityException() {
        SpringAIModelConfig qwen = new SpringAIModelConfig();
        qwen.setName("qwen3.5");
        qwen.setCapabilities(Set.of(ModelCapabilities.CHAT)); // no VISION
        qwen.setProviderType(SpringAIModelConfig.ProviderType.OPENAI);
        qwen.setPriority(1);

        when(springAIModelRegistry.getByModelName(eq("qwen3.5"))).thenReturn(Optional.of(qwen));

        Attachment img = new Attachment("k", "image/png", "photo.png", 100, AttachmentType.IMAGE, new byte[]{1});
        FixedModelChatAICommand command = fixedCommand("qwen3.5", List.of(img));

        assertThrows(UnsupportedModelCapabilityException.class, () -> gateway.generateResponse(command));
        verify(chatService, never()).callChat(any(), any(), any(), any());
    }

    @Test
    void fixedModel_withImageAttachment_modelHasVision_succeeds() {
        SpringAIModelConfig visionModel = new SpringAIModelConfig();
        visionModel.setName("glm-vision");
        visionModel.setCapabilities(Set.of(ModelCapabilities.CHAT, ModelCapabilities.VISION));
        visionModel.setProviderType(SpringAIModelConfig.ProviderType.OPENAI);
        visionModel.setPriority(1);

        when(springAIModelRegistry.getByModelName(eq("glm-vision"))).thenReturn(Optional.of(visionModel));
        when(chatService.callChat(any(), any(), any(), any())).thenReturn(
                new SpringAIResponse(ChatResponse.builder()
                        .generations(List.of(new Generation(new AssistantMessage("OK"))))
                        .build()));

        Attachment img = new Attachment("k", "image/png", "photo.png", 100, AttachmentType.IMAGE, new byte[]{1});
        FixedModelChatAICommand command = fixedCommand("glm-vision", List.of(img));

        AIResponse response = gateway.generateResponse(command);
        assertNotNull(response);
        verify(chatService).callChat(eq(visionModel), eq(command), any(), any());
    }

    @Test
    void noFixedModel_usesCapabilityBasedSelection() {
        when(chatService.callChat(any(), any(), any(), any())).thenReturn(
                new SpringAIResponse(ChatResponse.builder()
                        .generations(List.of(new Generation(new AssistantMessage("OK"))))
                        .build()));

        ChatAICommand command = new ChatAICommand(
                Set.of(ModelCapabilities.CHAT), 0.7, 1000, "Sys", "User", false, Map.of(), Map.of());

        gateway.generateResponse(command);

        verify(springAIModelRegistry).getCandidatesByCapabilities(any(), isNull(), any());
        verify(springAIModelRegistry, never()).getByModelName(any());
    }

    @Test
    void supports_fixedModel_presentInRegistry_returnsTrue() {
        SpringAIModelConfig qwen = new SpringAIModelConfig();
        qwen.setName("qwen3.5");
        qwen.setCapabilities(Set.of(ModelCapabilities.CHAT));
        qwen.setProviderType(SpringAIModelConfig.ProviderType.OPENAI);
        when(springAIModelRegistry.getByModelName(eq("qwen3.5"))).thenReturn(Optional.of(qwen));

        FixedModelChatAICommand command = fixedCommand("qwen3.5", List.of());
        assertTrue(gateway.supports(command));
        verify(springAIModelRegistry, never()).getCandidatesByCapabilities(any(), any());
    }

    @Test
    void supports_fixedModel_notInRegistry_returnsFalse() {
        when(springAIModelRegistry.getByModelName(eq("ghost-model"))).thenReturn(Optional.empty());

        FixedModelChatAICommand command = fixedCommand("ghost-model", List.of());
        assertFalse(gateway.supports(command));
    }

    @Test
    void generateResponse_command_whenChatServiceThrowsWebClientResponseException_wrapsInRuntimeException() {
        when(springAIProperties.getMock()).thenReturn(false);
        WebClientResponseException error = WebClientResponseException.create(429, "Too Many Requests",
                org.springframework.http.HttpHeaders.EMPTY, new byte[0], null);
        when(chatService.callChat(any(), any(), any(), any())).thenThrow(error);
        ChatAICommand command = new ChatAICommand(
                Set.of(ModelCapabilities.CHAT), 0.7, 1000, "Sys", "User", false, Map.of(), Map.of());
        RuntimeException ex = assertThrows(RuntimeException.class, () -> gateway.generateResponse(command));
        assertTrue(ex.getMessage().contains("Failed to generate response from Spring AI"));
        assertSame(error, ex.getCause());
    }
}
