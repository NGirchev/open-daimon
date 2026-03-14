package io.github.ngirchev.opendaimon.ai.springai.service;

import io.github.ngirchev.opendaimon.ai.springai.config.SpringAIModelConfig;
import io.github.ngirchev.opendaimon.ai.springai.tool.WebTools;
import io.github.ngirchev.opendaimon.common.ai.ModelCapabilities;
import io.github.ngirchev.opendaimon.common.ai.command.OpenDaimonChatOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.OllamaChatModel;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.github.ngirchev.opendaimon.common.ai.LlmParamNames.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SpringAIPromptFactoryTest {

    @Mock
    private OllamaChatModel ollamaChatModel;

    @Mock
    private SpringAIModelType springAIModelType;

    @Mock
    private WebTools webTools;

    private ChatClient chatClient;
    private SpringAIPromptFactory promptFactory;
    private SpringAIModelConfig ollamaModelConfig;
    private SpringAIModelConfig openAIModelConfig;

    @BeforeEach
    void setUp() {
        chatClient = ChatClient.builder(ollamaChatModel).build();
        promptFactory = new SpringAIPromptFactory(
                chatClient,
                chatClient,
                webTools,
                null,
                springAIModelType,
                false
        );
        ollamaModelConfig = new SpringAIModelConfig();
        ollamaModelConfig.setName("ollama-model");
        ollamaModelConfig.setProviderType(SpringAIModelConfig.ProviderType.OLLAMA);
        ollamaModelConfig.setCapabilities(List.of(ModelCapabilities.CHAT));
        openAIModelConfig = new SpringAIModelConfig();
        openAIModelConfig.setName("openrouter/auto");
        openAIModelConfig.setProviderType(SpringAIModelConfig.ProviderType.OPENAI);
        openAIModelConfig.setCapabilities(List.of(ModelCapabilities.AUTO));

        ChatResponse mockResponse = ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage("ok"))))
                .build();
        when(ollamaChatModel.call(any(Prompt.class))).thenReturn(mockResponse);
        when(springAIModelType.getByModelName(any())).thenReturn(Optional.empty());
        when(springAIModelType.isOllamaModel(any())).thenReturn(true);
        when(springAIModelType.isOpenAIModel(any())).thenReturn(false);
    }

    @Test
    void preparePrompt_ollamaModel_callsChatModel() {
        var spec = promptFactory.preparePrompt(
                ollamaModelConfig,
                "ollama-model",
                null,
                null,
                false,
                List.of(new UserMessage("Hello")),
                new OpenDaimonChatOptions(0.7, 1000, null, "Hello", false, Map.of())
        );
        assertNotNull(spec);
        ChatResponse response = spec.call().chatResponse();
        assertNotNull(response);
        assertEquals("ok", response.getResult().getOutput().getText());
        verify(ollamaChatModel, times(1)).call(any(Prompt.class));
    }

    @Test
    void preparePrompt_withBodyTemperatureAndMaxTokens_usesOverrides() {
        Map<String, Object> body = Map.of(TEMPERATURE, 0.5, MAX_TOKENS, 500);
        var spec = promptFactory.preparePrompt(
                ollamaModelConfig,
                "ollama-model",
                body,
                null,
                false,
                List.of(new UserMessage("Hi")),
                new OpenDaimonChatOptions(0.7, 1000, null, "Hi", false, Map.of())
        );
        assertNotNull(spec);
        ChatResponse response = spec.call().chatResponse();
        assertNotNull(response);
        verify(ollamaChatModel, times(1)).call(any(Prompt.class));
    }

    @Test
    void preparePrompt_withSystemAndUserMessages_includesBoth() {
        var spec = promptFactory.preparePrompt(
                ollamaModelConfig,
                "ollama-model",
                null,
                null,
                false,
                List.of(new SystemMessage("You are helpful."), new UserMessage("Hello")),
                new OpenDaimonChatOptions(0.7, 1000, "You are helpful.", "Hello", false, Map.of())
        );
        assertNotNull(spec);
        ChatResponse response = spec.call().chatResponse();
        assertNotNull(response);
        verify(ollamaChatModel, times(1)).call(any(Prompt.class));
    }

    @Test
    void preparePrompt_modelConfigWithNullProviderType_usesModelName() {
        SpringAIModelConfig configWithNullProvider = new SpringAIModelConfig();
        configWithNullProvider.setName("some-model");
        configWithNullProvider.setProviderType(null);
        configWithNullProvider.setCapabilities(List.of(ModelCapabilities.CHAT));
        var spec = promptFactory.preparePrompt(
                configWithNullProvider,
                "ollama-model",
                null,
                null,
                false,
                List.of(new UserMessage("Hi")),
                new OpenDaimonChatOptions(0.7, 1000, null, "Hi", false, Map.of())
        );
        assertNotNull(spec);
        ChatResponse response = spec.call().chatResponse();
        assertNotNull(response);
        verify(ollamaChatModel, times(1)).call(any(Prompt.class));
    }
}
