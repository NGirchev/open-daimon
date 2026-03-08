package io.github.ngirchev.aibot.ai.springai.service;

import io.github.ngirchev.aibot.ai.springai.config.RAGProperties;
import io.github.ngirchev.aibot.ai.springai.config.SpringAIModelConfig;
import io.github.ngirchev.aibot.ai.springai.config.SpringAIProperties;
import io.github.ngirchev.aibot.ai.springai.retry.SpringAIModelRegistry;
import io.github.ngirchev.aibot.common.ai.ModelCapabilities;
import io.github.ngirchev.aibot.common.ai.command.AICommand;
import io.github.ngirchev.aibot.common.ai.command.ChatAICommand;
import io.github.ngirchev.aibot.common.ai.response.AIResponse;
import io.github.ngirchev.aibot.common.ai.response.SpringAIResponse;
import io.github.ngirchev.aibot.common.service.AIGatewayRegistry;
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

import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.github.ngirchev.aibot.common.ai.LlmParamNames.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

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
        modelConfig.setCapabilities(List.of(ModelCapabilities.CHAT));
        modelConfig.setProviderType(SpringAIModelConfig.ProviderType.OLLAMA);
        modelConfig.setPriority(1);
        when(springAIModelRegistry.getCandidatesByCapabilities(any(), any())).thenReturn(List.of(modelConfig));
        when(springAIModelRegistry.getByModelName(any())).thenReturn(java.util.Optional.of(modelConfig));

        @SuppressWarnings("unchecked")
        ObjectProvider<org.springframework.ai.chat.memory.ChatMemory> chatMemoryProvider = mock(ObjectProvider.class);
        when(chatMemoryProvider.getIfAvailable()).thenReturn(null);

        @SuppressWarnings("unchecked")
        ObjectProvider<DocumentProcessingService> docProvider = mock(ObjectProvider.class);
        when(docProvider.getIfAvailable()).thenReturn(null);

        @SuppressWarnings("unchecked")
        ObjectProvider<io.github.ngirchev.aibot.ai.springai.rag.FileRAGService> ragProvider = mock(ObjectProvider.class);
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
}
