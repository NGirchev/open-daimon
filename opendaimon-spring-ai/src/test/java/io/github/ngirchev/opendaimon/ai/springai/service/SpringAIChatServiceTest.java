package io.github.ngirchev.opendaimon.ai.springai.service;

import io.github.ngirchev.opendaimon.ai.springai.config.SpringAIModelConfig;
import io.github.ngirchev.opendaimon.common.ai.ModelCapabilities;
import io.github.ngirchev.opendaimon.common.ai.command.OpenDaimonChatOptions;
import io.github.ngirchev.opendaimon.common.ai.command.ChatAICommand;
import io.github.ngirchev.opendaimon.common.ai.response.AIResponse;
import io.github.ngirchev.opendaimon.common.ai.response.SpringAIResponse;
import io.github.ngirchev.opendaimon.common.ai.response.SpringAIStreamResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;

@ExtendWith(MockitoExtension.class)
class SpringAIChatServiceTest {

    @Mock
    private SpringAIPromptFactory promptFactory;

    @Mock
    private ObjectProvider<io.github.ngirchev.opendaimon.ai.springai.retry.metrics.OpenRouterStreamMetricsTracker> openRouterStreamMetricsTrackerProvider;

    private SpringAIChatService chatService;
    private SpringAIModelConfig modelConfig;

    @BeforeEach
    void setUp() {
        chatService = new SpringAIChatService(promptFactory, openRouterStreamMetricsTrackerProvider);
        modelConfig = new SpringAIModelConfig();
        modelConfig.setName("test-model");
        modelConfig.setCapabilities(List.of(ModelCapabilities.CHAT));
        modelConfig.setProviderType(SpringAIModelConfig.ProviderType.OLLAMA);
    }

    @Test
    void callChat_returnsSpringAIResponse() {
        ChatResponse mockResponse = ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage("Hello"))))
                .build();
        org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec requestSpec =
                mock(org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec.class, RETURNS_DEEP_STUBS);
        when(requestSpec.call().chatResponse()).thenReturn(mockResponse);
        when(promptFactory.preparePrompt(
                eq(modelConfig),
                any(),
                any(),
                any(),
                anyBoolean(),
                any(),
                any())).thenReturn(requestSpec);

        OpenDaimonChatOptions options = new OpenDaimonChatOptions(0.7, 1000, "System", "User", false, Map.of());
        ChatAICommand command = new ChatAICommand(
                java.util.Set.of(ModelCapabilities.CHAT),
                0.7, 1000, "System", "User", false, Map.of(), Map.of());
        List<Message> messages = List.of();

        AIResponse response = chatService.callChat(modelConfig, command, options, messages);

        assertNotNull(response);
        assertInstanceOf(SpringAIResponse.class, response);
        assertEquals("Hello", ((SpringAIResponse) response).chatResponse().getResult().getOutput().getText());
    }

    @Test
    void callChatFromBody_returnsSpringAIResponse() {
        ChatResponse mockResponse = ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage("From body"))))
                .build();
        org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec requestSpec =
                mock(org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec.class, RETURNS_DEEP_STUBS);
        when(requestSpec.call().chatResponse()).thenReturn(mockResponse);
        when(promptFactory.preparePrompt(
                eq(modelConfig),
                any(),
                any(),
                any(),
                anyBoolean(),
                any(),
                isNull())).thenReturn(requestSpec);

        List<Message> messages = List.of();
        Map<String, Object> body = Map.of("model", "test-model");

        AIResponse response = chatService.callChatFromBody(modelConfig, body, null, false, messages);

        assertNotNull(response);
        assertInstanceOf(SpringAIResponse.class, response);
        assertEquals("From body", ((SpringAIResponse) response).chatResponse().getResult().getOutput().getText());
    }

    @Test
    void streamChat_returnsSpringAIStreamResponse() {
        when(openRouterStreamMetricsTrackerProvider.getIfAvailable()).thenReturn(null);
        ChatResponse chunk = ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage("chunk"))))
                .build();
        Flux<ChatResponse> flux = Flux.just(chunk);
        org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec requestSpec =
                mock(org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec.class, RETURNS_DEEP_STUBS);
        when(requestSpec.stream().chatResponse()).thenReturn(flux);
        when(promptFactory.preparePrompt(
                eq(modelConfig),
                any(),
                any(),
                any(),
                anyBoolean(),
                any(),
                any())).thenReturn(requestSpec);

        OpenDaimonChatOptions options = new OpenDaimonChatOptions(0.7, 1000, null, "Hi", true, Map.of());
        ChatAICommand command = new ChatAICommand(
                java.util.Set.of(ModelCapabilities.CHAT),
                0.7, 1000, null, "Hi", true, Map.of(), Map.of());

        AIResponse response = chatService.streamChat(modelConfig, command, options, List.of());

        assertNotNull(response);
        assertInstanceOf(SpringAIStreamResponse.class, response);
        Flux<ChatResponse> responseFlux = ((SpringAIStreamResponse) response).chatResponse();
        assertNotNull(responseFlux);
        ChatResponse first = responseFlux.blockFirst();
        assertNotNull(first);
        assertEquals("chunk", first.getResult().getOutput().getText());
    }

    @Test
    void callChatFromBody_modelFromOptionsMap_usesOptionsModel() {
        ChatResponse mockResponse = ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage("From options model"))))
                .build();
        org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec requestSpec =
                mock(org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec.class, RETURNS_DEEP_STUBS);
        when(requestSpec.call().chatResponse()).thenReturn(mockResponse);
        SpringAIModelConfig configWithNullName = new SpringAIModelConfig();
        configWithNullName.setName(null);
        configWithNullName.setCapabilities(List.of(ModelCapabilities.CHAT));
        configWithNullName.setProviderType(SpringAIModelConfig.ProviderType.OLLAMA);
        when(promptFactory.preparePrompt(
                eq(configWithNullName),
                eq("options-model-name"),
                any(),
                any(),
                anyBoolean(),
                any(),
                isNull())).thenReturn(requestSpec);
        Map<String, Object> options = Map.of("model", "options-model-name");
        Map<String, Object> body = Map.of("options", options, "messages", List.<Map<String, Object>>of());
        AIResponse response = chatService.callChatFromBody(configWithNullName, body, null, false, List.of());
        assertNotNull(response);
        assertEquals("From options model", ((SpringAIResponse) response).chatResponse().getResult().getOutput().getText());
        verify(promptFactory).preparePrompt(eq(configWithNullName), eq("options-model-name"), eq(body), any(), anyBoolean(), any(), isNull());
    }

    @Test
    void callChat_webClientResponseException_thrown() {
        org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec requestSpec =
                mock(org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec.class, RETURNS_DEEP_STUBS);
        when(promptFactory.preparePrompt(eq(modelConfig), any(), any(), any(), anyBoolean(), any(), any())).thenReturn(requestSpec);
        WebClientResponseException error = WebClientResponseException.create(429, "Too Many Requests",
                org.springframework.http.HttpHeaders.EMPTY, "rate limit".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                java.nio.charset.StandardCharsets.UTF_8);
        when(requestSpec.call().chatResponse()).thenThrow(error);
        OpenDaimonChatOptions options = new OpenDaimonChatOptions(0.7, 1000, null, "Hi", false, Map.of());
        ChatAICommand command = new ChatAICommand(
                java.util.Set.of(ModelCapabilities.CHAT), 0.7, 1000, null, "Hi", false, Map.of(), Map.of());
        assertThrows(WebClientResponseException.class, () ->
                chatService.callChat(modelConfig, command, options, List.of()));
    }
}
