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
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.ollama.api.ThinkOption;
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
        String preferredModelId = extractPreferredModelId(prompt);
        SpringAIModelConfig modelConfig = resolveModel(preferredModelId);
        ChatModel target = selectBean(modelConfig);
        Prompt enriched = enrichWithModelOptions(prompt, modelConfig);
        return target.call(enriched);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        String preferredModelId = extractPreferredModelId(prompt);
        SpringAIModelConfig modelConfig = resolveModel(preferredModelId);
        ChatModel target = selectBean(modelConfig);
        Prompt enriched = enrichWithModelOptions(prompt, modelConfig);
        return target.stream(enriched);
    }

    @Override
    public ChatOptions getDefaultOptions() {
        // Prefer OpenAI bean's defaults (used for OpenRouter), fallback to Ollama
        ChatModel primary = openAiChatModel != null ? openAiChatModel : ollamaChatModel;
        return primary != null ? primary.getDefaultOptions() : null;
    }

    /**
     * Extracts the preferred model ID from the prompt's ChatOptions, if set.
     * Callers (SpringAgentLoopActions, SimpleChainExecutor) read the user's
     * preferred model from AgentContext/AgentRequest metadata and set it
     * as {@code ChatOptions.model}.
     */
    private String extractPreferredModelId(Prompt prompt) {
        ChatOptions options = prompt.getOptions();
        if (options == null) {
            return null;
        }
        return options.getModel();
    }

    private SpringAIModelConfig resolveModel(String preferredModelId) {
        List<SpringAIModelConfig> candidates = registry.getCandidatesByCapabilities(
                Set.of(ModelCapabilities.CHAT, ModelCapabilities.TOOL_CALLING), preferredModelId);
        if (candidates.isEmpty()) {
            candidates = registry.getCandidatesByCapabilities(
                    Set.of(ModelCapabilities.CHAT), preferredModelId);
        }
        if (candidates.isEmpty()) {
            throw new IllegalStateException(
                    "No model with CHAT capability found in registry for agent");
        }
        SpringAIModelConfig modelConfig = candidates.getFirst();
        log.info("DelegatingAgentChatModel: resolved model='{}' (provider={}, preferred='{}')",
                modelConfig.getName(), modelConfig.getProviderType(), preferredModelId);
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
     * Enriches the prompt with the resolved model name and provider-specific options.
     *
     * <p>For Ollama models: builds {@link OllamaChatOptions} which supports both
     * {@code thinkOption} and {@code toolCallbacks} (implements {@code ToolCallingChatOptions}).
     * This ensures thinking mode is enabled when the model config has {@code think=true}.
     *
     * <p>For OpenAI/OpenRouter models: builds generic {@link ToolCallingChatOptions}.
     */
    private Prompt enrichWithModelOptions(Prompt prompt, SpringAIModelConfig modelConfig) {
        ChatOptions existing = prompt.getOptions();
        String modelName = modelConfig.getName();

        if (modelConfig.getProviderType() == SpringAIModelConfig.ProviderType.OLLAMA) {
            return enrichForOllama(prompt, existing, modelConfig);
        }

        // OpenAI / OpenRouter path — generic ToolCallingChatOptions
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

    /**
     * Builds {@link OllamaChatOptions} that combines model name, think option,
     * and tool callbacks from the original prompt options.
     */
    private Prompt enrichForOllama(Prompt prompt, ChatOptions existing, SpringAIModelConfig modelConfig) {
        OllamaChatOptions.Builder builder = OllamaChatOptions.builder()
                .model(modelConfig.getName());

        boolean thinkEnabled = Boolean.TRUE.equals(modelConfig.getThink());
        if (thinkEnabled) {
            builder.thinkOption(ThinkOption.ThinkBoolean.ENABLED);
        }
        log.info("DelegatingAgentChatModel: enrichForOllama model='{}', think={}, hasExistingOptions={}",
                modelConfig.getName(), thinkEnabled, existing != null);

        // Transfer tool callbacks and other options from the original prompt
        if (existing instanceof ToolCallingChatOptions tco) {
            builder.toolCallbacks(tco.getToolCallbacks());
            builder.toolNames(tco.getToolNames());
            builder.internalToolExecutionEnabled(tco.getInternalToolExecutionEnabled());
            if (tco.getTemperature() != null) {
                builder.temperature(tco.getTemperature());
            }
            if (tco.getMaxTokens() != null) {
                builder.numPredict(tco.getMaxTokens());
            }
            if (tco.getTopP() != null) {
                builder.topP(tco.getTopP());
            }
            if (tco.getTopK() != null) {
                builder.topK(tco.getTopK());
            }
        }

        return new Prompt(prompt.getInstructions(), builder.build());
    }
}
