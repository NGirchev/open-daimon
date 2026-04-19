package io.github.ngirchev.opendaimon.ai.springai.agent;

import io.github.ngirchev.opendaimon.ai.springai.config.SpringAIModelConfig;
import io.github.ngirchev.opendaimon.ai.springai.retry.SpringAIModelRegistry;
import io.github.ngirchev.opendaimon.common.ai.ModelCapabilities;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DelegatingAgentChatModel} — preferred model routing
 * and provider-specific options enrichment.
 */
class DelegatingAgentChatModelTest {

    private SpringAIModelRegistry registry;
    private OllamaChatModel ollamaChatModel;
    private OpenAiChatModel openAiChatModel;
    private DelegatingAgentChatModel delegating;

    @BeforeEach
    void setUp() {
        registry = mock(SpringAIModelRegistry.class);
        ollamaChatModel = mock(OllamaChatModel.class);
        openAiChatModel = mock(OpenAiChatModel.class);

        @SuppressWarnings("unchecked")
        ObjectProvider<OllamaChatModel> ollamaProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<OpenAiChatModel> openAiProvider = mock(ObjectProvider.class);
        when(ollamaProvider.getIfAvailable()).thenReturn(ollamaChatModel);
        when(openAiProvider.getIfAvailable()).thenReturn(openAiChatModel);

        delegating = new DelegatingAgentChatModel(registry, ollamaProvider, openAiProvider);
    }

    @Test
    @DisplayName("Preferred model from ChatOptions is passed to registry")
    void shouldPassPreferredModelToRegistry() {
        // Arrange
        SpringAIModelConfig ollamaConfig = createModelConfig("qwen3.5:4b",
                SpringAIModelConfig.ProviderType.OLLAMA, true);
        when(registry.getCandidatesByCapabilities(any(), eq("qwen3.5:4b")))
                .thenReturn(List.of(ollamaConfig));
        when(ollamaChatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("answer")))));

        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .model("qwen3.5:4b")
                .build();
        Prompt prompt = new Prompt(List.of(new UserMessage("hello")), options);

        // Act
        delegating.call(prompt);

        // Assert
        verify(registry).getCandidatesByCapabilities(
                eq(Set.of(ModelCapabilities.CHAT, ModelCapabilities.TOOL_CALLING)),
                eq("qwen3.5:4b"));
    }

    @Test
    @DisplayName("Null preferred model when ChatOptions has no model set")
    void shouldPassNullWhenNoPreferredModel() {
        // Arrange
        SpringAIModelConfig openAiConfig = createModelConfig("openrouter/auto",
                SpringAIModelConfig.ProviderType.OPENAI, false);
        when(registry.getCandidatesByCapabilities(any(), eq(null)))
                .thenReturn(List.of(openAiConfig));
        when(openAiChatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("answer")))));

        Prompt prompt = new Prompt(List.of(new UserMessage("hello")));

        // Act
        delegating.call(prompt);

        // Assert
        verify(registry).getCandidatesByCapabilities(
                eq(Set.of(ModelCapabilities.CHAT, ModelCapabilities.TOOL_CALLING)),
                eq(null));
    }

    @Test
    @DisplayName("Ollama model with think=true gets OllamaChatOptions with thinkOption ENABLED")
    void shouldEnrichOllamaWithThinkOption() {
        // Arrange
        SpringAIModelConfig ollamaConfig = createModelConfig("qwen3.5:4b",
                SpringAIModelConfig.ProviderType.OLLAMA, true);
        when(registry.getCandidatesByCapabilities(any(), any()))
                .thenReturn(List.of(ollamaConfig));
        when(ollamaChatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("answer")))));

        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .model("qwen3.5:4b")
                .internalToolExecutionEnabled(false)
                .build();
        Prompt prompt = new Prompt(List.of(new UserMessage("hello")), options);

        // Act
        delegating.call(prompt);

        // Assert
        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(ollamaChatModel).call(captor.capture());
        Prompt enriched = captor.getValue();

        assertThat(enriched.getOptions())
                .isInstanceOf(OllamaChatOptions.class);
        OllamaChatOptions ollamaOptions = (OllamaChatOptions) enriched.getOptions();
        assertThat(ollamaOptions.getModel()).isEqualTo("qwen3.5:4b");
        assertThat(ollamaOptions.getThinkOption()).isNotNull();
    }

    @Test
    @DisplayName("OpenAI model gets ToolCallingChatOptions without thinkOption")
    void shouldEnrichOpenAiWithToolCallingChatOptions() {
        // Arrange
        SpringAIModelConfig openAiConfig = createModelConfig("openrouter/auto",
                SpringAIModelConfig.ProviderType.OPENAI, false);
        when(registry.getCandidatesByCapabilities(any(), any()))
                .thenReturn(List.of(openAiConfig));
        when(openAiChatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("answer")))));

        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .model("openrouter/auto")
                .build();
        Prompt prompt = new Prompt(List.of(new UserMessage("hello")), options);

        // Act
        delegating.call(prompt);

        // Assert
        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(openAiChatModel).call(captor.capture());
        Prompt enriched = captor.getValue();

        assertThat(enriched.getOptions())
                .isInstanceOf(ToolCallingChatOptions.class)
                .isNotInstanceOf(OllamaChatOptions.class);
        assertThat(enriched.getOptions().getModel()).isEqualTo("openrouter/auto");
    }

    private SpringAIModelConfig createModelConfig(String name,
                                                   SpringAIModelConfig.ProviderType provider,
                                                   boolean think) {
        SpringAIModelConfig config = new SpringAIModelConfig();
        config.setName(name);
        config.setProviderType(provider);
        config.setCapabilities(Set.of(ModelCapabilities.CHAT, ModelCapabilities.TOOL_CALLING));
        config.setPriority(1);
        config.setThink(think ? true : null);
        return config;
    }
}
