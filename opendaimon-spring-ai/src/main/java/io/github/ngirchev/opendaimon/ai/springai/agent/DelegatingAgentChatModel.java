package io.github.ngirchev.opendaimon.ai.springai.agent;

import io.github.ngirchev.opendaimon.ai.springai.config.SpringAIModelConfig;
import io.github.ngirchev.opendaimon.ai.springai.retry.SpringAIModelRegistry;
import io.github.ngirchev.opendaimon.common.ai.ModelCapabilities;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Set;

/**
 * Proxy ChatModel that delegates to existing auto-configured ChatModel beans
 * (OllamaChatModel or OpenAiChatModel) while dynamically resolving the model
 * name from {@link SpringAIModelRegistry}.
 *
 * <p>On each {@link #call(Prompt)}, this proxy:
 * <ol>
 *   <li>Resolves the best available model from the registry by capabilities</li>
 *   <li>Selects the correct existing ChatModel bean based on provider type</li>
 *   <li>Enriches the Prompt with the resolved model name via ChatOptions</li>
 *   <li>Delegates the call to the existing bean</li>
 * </ol>
 *
 * <p>Unlike creating new ChatModel instances, this approach preserves Spring bean
 * lifecycle — aspects, metrics, interceptors, and other customizations applied
 * to the auto-configured beans remain active.
 *
 * <p>The model name override works because Spring AI's ChatModel implementations
 * honor the {@code model} field in ChatOptions when it differs from the bean's
 * default — the same mechanism used by {@code SpringAIPromptFactory}.
 */
@Slf4j
public class DelegatingAgentChatModel implements ChatModel {

    private final SpringAIModelRegistry registry;
    private final ChatModel ollamaChatModel;
    private final ChatModel openAiChatModel;

    public DelegatingAgentChatModel(
            SpringAIModelRegistry registry,
            ObjectProvider<OllamaChatModel> ollamaProvider,
            ObjectProvider<OpenAiChatModel> openAiProvider) {
        this.registry = registry;
        this.ollamaChatModel = ollamaProvider.getIfAvailable();
        this.openAiChatModel = openAiProvider.getIfAvailable();
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        SpringAIModelConfig modelConfig = resolveModel();
        ChatModel target = selectBean(modelConfig);
        Prompt enriched = enrichWithModelName(prompt, modelConfig.getName());
        return target.call(enriched);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        SpringAIModelConfig modelConfig = resolveModel();
        ChatModel target = selectBean(modelConfig);
        Prompt enriched = enrichWithModelName(prompt, modelConfig.getName());
        return target.stream(enriched);
    }

    @Override
    public ChatOptions getDefaultOptions() {
        // Prefer OpenAI bean's defaults (used for OpenRouter), fallback to Ollama
        ChatModel primary = openAiChatModel != null ? openAiChatModel : ollamaChatModel;
        return primary != null ? primary.getDefaultOptions() : null;
    }

    private SpringAIModelConfig resolveModel() {
        List<SpringAIModelConfig> candidates = registry.getCandidatesByCapabilities(
                Set.of(ModelCapabilities.CHAT, ModelCapabilities.TOOL_CALLING), null);
        if (candidates.isEmpty()) {
            candidates = registry.getCandidatesByCapabilities(
                    Set.of(ModelCapabilities.CHAT), null);
        }
        if (candidates.isEmpty()) {
            throw new IllegalStateException(
                    "No model with CHAT capability found in registry for agent");
        }
        SpringAIModelConfig modelConfig = candidates.getFirst();
        log.debug("DelegatingAgentChatModel: resolved model='{}' (provider={})",
                modelConfig.getName(), modelConfig.getProviderType());
        return modelConfig;
    }

    private ChatModel selectBean(SpringAIModelConfig modelConfig) {
        return switch (modelConfig.getProviderType()) {
            case OLLAMA -> {
                if (ollamaChatModel == null) {
                    throw new IllegalStateException(
                            "OllamaChatModel bean not available for model: " + modelConfig.getName());
                }
                yield ollamaChatModel;
            }
            case OPENAI -> {
                if (openAiChatModel == null) {
                    throw new IllegalStateException(
                            "OpenAiChatModel bean not available for model: " + modelConfig.getName());
                }
                yield openAiChatModel;
            }
        };
    }

    /**
     * Enriches the prompt with the resolved model name. If the prompt already has
     * ChatOptions (e.g. ToolCallingChatOptions with tool callbacks), the model name
     * is merged into a copy. Otherwise, a minimal ChatOptions with just the model is created.
     */
    private Prompt enrichWithModelName(Prompt prompt, String modelName) {
        ChatOptions existing = prompt.getOptions();
        if (existing instanceof ToolCallingChatOptions tco) {
            ToolCallingChatOptions enriched = ToolCallingChatOptions.builder()
                    .model(modelName)
                    .toolCallbacks(tco.getToolCallbacks())
                    .toolNames(tco.getToolNames())
                    .internalToolExecutionEnabled(tco.getInternalToolExecutionEnabled())
                    .temperature(tco.getTemperature())
                    .maxTokens(tco.getMaxTokens())
                    .topP(tco.getTopP())
                    .topK(tco.getTopK())
                    .build();
            return new Prompt(prompt.getInstructions(), enriched);
        }
        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .model(modelName)
                .build();
        return new Prompt(prompt.getInstructions(), options);
    }
}
