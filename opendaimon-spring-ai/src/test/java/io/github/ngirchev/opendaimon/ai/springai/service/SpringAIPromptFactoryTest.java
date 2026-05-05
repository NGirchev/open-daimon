package io.github.ngirchev.opendaimon.ai.springai.service;

import io.github.ngirchev.opendaimon.ai.springai.config.SpringAIModelConfig;
import io.github.ngirchev.opendaimon.bulkhead.model.UserPriority;
import io.github.ngirchev.opendaimon.ai.springai.tool.WebTools;
import io.github.ngirchev.opendaimon.common.ai.ModelCapabilities;
import io.github.ngirchev.opendaimon.common.ai.command.AICommand;
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
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static io.github.ngirchev.opendaimon.common.ai.LlmParamNames.*;
import static org.junit.jupiter.api.Assertions.*;
import org.mockito.ArgumentCaptor;

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
                springAIModelType
        );
        ollamaModelConfig = new SpringAIModelConfig();
        ollamaModelConfig.setName("ollama-model");
        ollamaModelConfig.setProviderType(SpringAIModelConfig.ProviderType.OLLAMA);
        ollamaModelConfig.setCapabilities(Set.of(ModelCapabilities.CHAT));
        openAIModelConfig = new SpringAIModelConfig();
        openAIModelConfig.setName("openrouter/auto");
        openAIModelConfig.setProviderType(SpringAIModelConfig.ProviderType.OPENAI);
        openAIModelConfig.setCapabilities(Set.of(ModelCapabilities.AUTO));

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
    void preparePrompt_withBodySeed_setsOllamaSeed() {
        Map<String, Object> body = Map.of(SEED, 42, MAX_TOKENS, 500);
        var spec = promptFactory.preparePrompt(
                ollamaModelConfig,
                "ollama-model",
                body,
                null,
                false,
                List.of(new UserMessage("Seeded OCR")),
                new OpenDaimonChatOptions(0.7, 1000, null, "Seeded OCR", false, Map.of())
        );

        spec.call().chatResponse();
        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(ollamaChatModel, atLeastOnce()).call(captor.capture());

        ChatOptions options = captor.getValue().getOptions();
        assertInstanceOf(OllamaChatOptions.class, options);
        assertEquals(42, ((OllamaChatOptions) options).getSeed());
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
        configWithNullProvider.setCapabilities(Set.of(ModelCapabilities.CHAT));
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

    @Test
    void preparePrompt_ollama_reasoningBudget_addsToNumPredict() {
        Map<String, Object> body = Map.of("reasoning", Map.of("max_tokens", 400));
        var spec = promptFactory.preparePrompt(
                ollamaModelConfig,
                "ollama-model",
                body,
                null,
                false,
                List.of(new UserMessage("Hi")),
                new OpenDaimonChatOptions(0.7, 1000, null, "Hi", false, Map.of())
        );
        spec.call().chatResponse();
        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(ollamaChatModel).call(captor.capture());
        ChatOptions options = captor.getValue().getOptions();
        assertInstanceOf(OllamaChatOptions.class, options);
        assertEquals(1400, ((OllamaChatOptions) options).getNumPredict());
    }

    @Test
    void preparePrompt_ollama_thinkFalse_doesNotAddReasoningToNumPredict() {
        ollamaModelConfig.setThink(false);
        Map<String, Object> body = Map.of("reasoning", Map.of("max_tokens", 400));
        var spec = promptFactory.preparePrompt(
                ollamaModelConfig,
                "ollama-model",
                body,
                null,
                false,
                List.of(new UserMessage("Hi")),
                new OpenDaimonChatOptions(0.7, 1000, null, "Hi", false, Map.of())
        );
        spec.call().chatResponse();
        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(ollamaChatModel).call(captor.capture());
        ChatOptions options = captor.getValue().getOptions();
        assertInstanceOf(OllamaChatOptions.class, options);
        assertEquals(1000, ((OllamaChatOptions) options).getNumPredict());
    }

    @Test
    void preparePrompt_ollama_withoutChatOptions_usesFallbackMaxTokens() {
        var spec = promptFactory.preparePrompt(
                ollamaModelConfig,
                "ollama-model",
                null,
                null,
                false,
                List.of(new UserMessage("Vision extraction")),
                null
        );

        assertNotNull(spec);
        ChatResponse response = spec.call().chatResponse();
        assertNotNull(response);

        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(ollamaChatModel, atLeastOnce()).call(captor.capture());
        ChatOptions options = captor.getValue().getOptions();
        assertInstanceOf(OllamaChatOptions.class, options);
        assertEquals(4000, ((OllamaChatOptions) options).getNumPredict());
    }

    @Test
    void preparePrompt_withExternalToolsEnabled_addsExternalToolCallbacks() {
        ToolCallback externalTool = toolCallback("mcp_search");
        ToolCallbackProvider provider = mock(ToolCallbackProvider.class);
        when(provider.getToolCallbacks()).thenReturn(new ToolCallback[]{externalTool});
        @SuppressWarnings("unchecked")
        ObjectProvider<ToolCallbackProvider> providers = mock(ObjectProvider.class);
        when(providers.orderedStream()).thenReturn(Stream.of(provider));
        SpringAIPromptFactory factory = new SpringAIPromptFactory(
                chatClient,
                chatClient,
                webTools,
                null,
                springAIModelType,
                providers,
                true);

        var spec = factory.preparePrompt(
                ollamaModelConfig,
                "ollama-model",
                null,
                null,
                false,
                true,
                Map.of(AICommand.USER_PRIORITY_FIELD, UserPriority.ADMIN.name()),
                List.of(new UserMessage("Use the external tool if needed")),
                new OpenDaimonChatOptions(0.7, 1000, null, "Use the external tool if needed", false, Map.of())
        );

        spec.call().chatResponse();
        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(ollamaChatModel).call(captor.capture());

        ChatOptions options = captor.getValue().getOptions();
        assertInstanceOf(ToolCallingChatOptions.class, options);
        ToolCallingChatOptions toolOptions = (ToolCallingChatOptions) options;
        assertTrue(toolOptions.getToolCallbacks().stream()
                .anyMatch(callback -> "mcp_search".equals(callback.getToolDefinition().name())));
    }

    @Test
    void preparePrompt_withExternalToolsAllowedButNoPriority_doesNotAddExternalToolCallbacks() {
        ToolCallback externalTool = toolCallback("mcp_search");
        ToolCallbackProvider provider = mock(ToolCallbackProvider.class);
        when(provider.getToolCallbacks()).thenReturn(new ToolCallback[]{externalTool});
        @SuppressWarnings("unchecked")
        ObjectProvider<ToolCallbackProvider> providers = mock(ObjectProvider.class);
        when(providers.orderedStream()).thenReturn(Stream.of(provider));
        SpringAIPromptFactory factory = new SpringAIPromptFactory(
                chatClient,
                chatClient,
                webTools,
                null,
                springAIModelType,
                providers,
                true);

        var spec = factory.preparePrompt(
                ollamaModelConfig,
                "ollama-model",
                null,
                null,
                false,
                true,
                List.of(new UserMessage("Do not infer admin access")),
                new OpenDaimonChatOptions(0.7, 1000, null, "Do not infer admin access", false, Map.of())
        );

        spec.call().chatResponse();
        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(ollamaChatModel).call(captor.capture());

        if (captor.getValue().getOptions() instanceof ToolCallingChatOptions toolOptions) {
            assertTrue(toolOptions.getToolCallbacks() == null || toolOptions.getToolCallbacks().isEmpty());
        }
    }

    @Test
    void preparePrompt_withExternalToolsNotAllowed_doesNotAddExternalToolCallbacks() {
        ToolCallback externalTool = toolCallback("mcp_search");
        ToolCallbackProvider provider = mock(ToolCallbackProvider.class);
        when(provider.getToolCallbacks()).thenReturn(new ToolCallback[]{externalTool});
        @SuppressWarnings("unchecked")
        ObjectProvider<ToolCallbackProvider> providers = mock(ObjectProvider.class);
        when(providers.orderedStream()).thenReturn(Stream.of(provider));
        SpringAIPromptFactory factory = new SpringAIPromptFactory(
                chatClient,
                chatClient,
                webTools,
                null,
                springAIModelType,
                providers,
                true);

        var spec = factory.preparePrompt(
                ollamaModelConfig,
                "ollama-model",
                null,
                null,
                false,
                false,
                List.of(new UserMessage("Do not expose the external tool")),
                new OpenDaimonChatOptions(0.7, 1000, null, "Do not expose the external tool", false, Map.of())
        );

        spec.call().chatResponse();
        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(ollamaChatModel).call(captor.capture());

        if (captor.getValue().getOptions() instanceof ToolCallingChatOptions toolOptions) {
            assertTrue(toolOptions.getToolCallbacks() == null || toolOptions.getToolCallbacks().isEmpty());
        }
    }

    @Test
    void preparePrompt_regularUserGetsSharedMcpToolsButNotFilesystemTools() {
        ToolCallback filesystemTool = toolCallback("read_file");
        ToolCallback sharedTool = toolCallback("weather_lookup");
        ToolCallbackProvider provider = mock(ToolCallbackProvider.class);
        when(provider.getToolCallbacks()).thenReturn(new ToolCallback[]{filesystemTool, sharedTool});
        @SuppressWarnings("unchecked")
        ObjectProvider<ToolCallbackProvider> providers = mock(ObjectProvider.class);
        when(providers.orderedStream()).thenReturn(Stream.of(provider));
        SpringAIPromptFactory factory = new SpringAIPromptFactory(
                chatClient,
                chatClient,
                webTools,
                null,
                springAIModelType,
                providers,
                true);

        var spec = factory.preparePrompt(
                ollamaModelConfig,
                "ollama-model",
                null,
                null,
                false,
                true,
                Map.of(AICommand.USER_PRIORITY_FIELD, UserPriority.REGULAR.name()),
                List.of(new UserMessage("Use shared tools only")),
                new OpenDaimonChatOptions(0.7, 1000, null, "Use shared tools only", false, Map.of())
        );

        spec.call().chatResponse();
        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(ollamaChatModel).call(captor.capture());

        assertInstanceOf(ToolCallingChatOptions.class, captor.getValue().getOptions());
        ToolCallingChatOptions toolOptions = (ToolCallingChatOptions) captor.getValue().getOptions();
        assertTrue(toolOptions.getToolCallbacks().stream()
                .anyMatch(callback -> "weather_lookup".equals(callback.getToolDefinition().name())));
        assertFalse(toolOptions.getToolCallbacks().stream()
                .anyMatch(callback -> "read_file".equals(callback.getToolDefinition().name())));
    }

    // --- null openAiChatClient (Ollama-only setup) ---

    @Test
    void preparePrompt_withNullOpenAiClient_throwsIllegalStateException_whenOpenAIModelRequested() {
        SpringAIPromptFactory factoryWithoutOpenAi = new SpringAIPromptFactory(
                chatClient, null, webTools, null, springAIModelType);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                factoryWithoutOpenAi.preparePrompt(
                        openAIModelConfig,
                        "openrouter/auto",
                        null, null, false,
                        List.of(new UserMessage("Hello")),
                        null
                )
        );
        assertNotNull(ex.getMessage());
        assertTrue(ex.getMessage().contains("openrouter/auto"));
        assertTrue(ex.getMessage().contains("spring.ai.openai.api-key"));
    }

    @Test
    void preparePrompt_withNullOpenAiClient_worksNormally_whenOllamaModelRequested() {
        SpringAIPromptFactory factoryWithoutOpenAi = new SpringAIPromptFactory(
                chatClient, null, webTools, null, springAIModelType);

        var spec = factoryWithoutOpenAi.preparePrompt(
                ollamaModelConfig,
                "ollama-model",
                null, null, false,
                List.of(new UserMessage("Hello")),
                new OpenDaimonChatOptions(0.7, 1000, null, "Hello", false, Map.of())
        );
        assertNotNull(spec);
        ChatResponse response = spec.call().chatResponse();
        assertNotNull(response);
        verify(ollamaChatModel, atLeastOnce()).call(any(Prompt.class));
    }

    @Test
    void preparePrompt_withNullOpenAiClient_throwsIllegalStateException_whenProviderTypeIsOpenAI() {
        SpringAIPromptFactory factoryWithoutOpenAi = new SpringAIPromptFactory(
                chatClient, null, webTools, null, springAIModelType);

        SpringAIModelConfig openAiConfig = new SpringAIModelConfig();
        openAiConfig.setName("meta-llama/llama-3.3-70b-instruct:free");
        openAiConfig.setProviderType(SpringAIModelConfig.ProviderType.OPENAI);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                factoryWithoutOpenAi.preparePrompt(
                        openAiConfig,
                        openAiConfig.getName(),
                        null, null, false,
                        List.of(new UserMessage("Hi")),
                        null
                )
        );
        assertTrue(ex.getMessage().contains("spring.ai.openai.api-key"));
    }

    private static ToolCallback toolCallback(String name) {
        ToolDefinition definition = ToolDefinition.builder()
                .name(name)
                .description(name + " description")
                .inputSchema("{\"type\":\"object\"}")
                .build();
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String toolInput) {
                return "ok";
            }
        };
    }
}
