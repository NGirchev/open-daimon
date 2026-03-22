package io.github.ngirchev.opendaimon.ai.springai.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import io.github.ngirchev.opendaimon.ai.springai.advisor.MessageOrderingAdvisor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.content.Media;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.ollama.api.ThinkOption;
import org.springframework.ai.openai.OpenAiChatOptions;
import io.github.ngirchev.opendaimon.ai.springai.config.SpringAIModelConfig;
import io.github.ngirchev.opendaimon.ai.springai.tool.WebTools;
import io.github.ngirchev.opendaimon.common.ai.ModelCapabilities;
import io.github.ngirchev.opendaimon.common.ai.command.OpenDaimonChatOptions;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID;
import static io.github.ngirchev.opendaimon.common.ai.LlmParamNames.*;

@Slf4j
@RequiredArgsConstructor
public class SpringAIPromptFactory {

    private final ChatClient ollamaChatClient;
    private final ChatClient openAiChatClient;
    private final WebTools webTools;
    private final ChatMemory chatMemory;
    private final SpringAIModelType springAIModelType;

    public ChatClient.ChatClientRequestSpec preparePrompt(
            SpringAIModelConfig modelConfig,
            String modelName,
            Map<String, Object> body,
            Object conversationId,
            boolean webEnabled,
            List<Message> messages,
            OpenDaimonChatOptions chatOptions
    ) {
        String resolvedModelName = modelConfig != null ? modelConfig.getName() : modelName;
        ChatClient chatClient = getChatClient(modelConfig, resolvedModelName);
        var promptBuilder = chatClient.prompt();
        promptBuilder.options(buildChatOptions(modelConfig, resolvedModelName, body, chatOptions));

        if (conversationId != null) {
            promptBuilder
                    .advisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                    .advisors(a -> a.param(CONVERSATION_ID, conversationId))
                    .advisors(new MessageOrderingAdvisor());
        }
        addSystemMessagesIfPresent(promptBuilder, messages);
        addWebToolsIfEnabled(promptBuilder, webEnabled);
        addUserOrAllMessages(promptBuilder, messages);

        return promptBuilder;
    }

    private void addSystemMessagesIfPresent(ChatClient.ChatClientRequestSpec promptBuilder, List<Message> messages) {
        if (messages == null || messages.isEmpty()) return;
        for (Message message : messages) {
            if (message instanceof SystemMessage systemMessage) {
                promptBuilder.system(systemMessage.getText());
            }
        }
    }

    private void addWebToolsIfEnabled(ChatClient.ChatClientRequestSpec promptBuilder, boolean webEnabled) {
        if (webEnabled) {
            promptBuilder.tools(webTools);
            log.debug("Web tools added to prompt (web_search, fetch_url). Model may invoke them.");
        } else {
            log.debug("Web tools NOT added to prompt (webEnabled=false). Serper/fetch_url are only registered when the AI command requests WEB in required or optional capabilities.");
        }
    }

    private void addUserOrAllMessages(ChatClient.ChatClientRequestSpec promptBuilder, List<Message> messages) {
        if (messages == null || messages.isEmpty()) return;
        addLastUserMessageToPrompt(promptBuilder, messages);
    }

    private void addLastUserMessageToPrompt(ChatClient.ChatClientRequestSpec promptBuilder, List<Message> messages) {
        UserMessage lastUserMessage = null;
        for (Message message : messages) {
            if (message instanceof UserMessage userMessage) {
                lastUserMessage = userMessage;
            }
        }
        if (lastUserMessage == null) return;
        final UserMessage lastUserMsg = lastUserMessage;
        if (lastUserMsg.getMedia() != null && !lastUserMsg.getMedia().isEmpty()) {
            promptBuilder.user(u -> u.text(lastUserMsg.getText())
                    .media(lastUserMsg.getMedia().toArray(new Media[0])));
        } else {
            promptBuilder.user(lastUserMsg.getText());
        }
    }

    private ChatOptions buildChatOptions(
            SpringAIModelConfig modelConfig,
            String modelName,
            Map<String, Object> body,
            OpenDaimonChatOptions chatOptions
    ) {
        Map<String, Object> safeOverrides = body != null ? body : Collections.emptyMap();
        
        // Use chatOptions values when missing in body
        Double temperature = getDouble(safeOverrides, TEMPERATURE);
        if (temperature == null && chatOptions != null) {
            temperature = chatOptions.temp();
        }
        
        Integer maxTokens = getInteger(safeOverrides, MAX_TOKENS);
        if (maxTokens == null && chatOptions != null) {
            maxTokens = chatOptions.maxTokens();
        }
        // Per-model override takes priority over global default
        if (modelConfig != null && modelConfig.getMaxOutputTokens() != null) {
            maxTokens = modelConfig.getMaxOutputTokens();
        }
        
        if (isOpenAIProvider(modelConfig, modelName)) {
            OpenAiChatOptions.Builder optionsBuilder = OpenAiChatOptions.builder()
                    .model(modelName)
                    .frequencyPenalty(getDouble(safeOverrides, FREQUENCY_PENALTY))
                    .temperature(temperature)
                    .maxTokens(maxTokens)
                    .topP(getDouble(safeOverrides, TOP_P));

            Map<String, Object> extraBody = extractExtraBody(safeOverrides);
            if (extraBody == null) {
                extraBody = new HashMap<>();
            }
            Object reasoning = resolveReasoning(modelConfig, safeOverrides);
            if (reasoning != null) {
                extraBody.put("reasoning", reasoning);
            }
            if (extraBody.isEmpty() && isOpenRouterAutoModel(modelName)) {
                extraBody = defaultOpenRouterFreeMaxPriceExtraBody();
                log.debug("Applying default max_price=0 for model={}", modelName);
            }
            if (!extraBody.isEmpty()) {
                optionsBuilder.extraBody(extraBody);
                log.debug("OpenRouter request: model={}, extraBody={}", modelName, extraBody);
            } else {
                log.debug("OpenRouter request: model={}, extraBody=(none)", modelName);
            }

            return optionsBuilder.build();
        }

        // Ollama: no separate reasoning token API (unlike OpenRouter extra_body.reasoning). Thinking and
        // visible content share num_predict. When a reasoning budget is configured and thinking is not
        // explicitly disabled, reserve it by setting num_predict = maxTokens + reasoningBudget.
        int ollamaPredict = computeOllamaNumPredict(maxTokens, modelConfig, safeOverrides);
        if (ollamaPredict != maxTokens) {
            log.debug("Ollama num_predict: {} (maxTokens={} + reasoningBudget)", ollamaPredict, maxTokens);
        }

        // Ollama: do not pass think by default — some versions/models return 400 for this param.
        // Per-model opt-in/opt-out via SpringAIModelConfig.think (e.g. think: false for Qwen3 to avoid empty responses).
        OllamaChatOptions.Builder ollamaBuilder = OllamaChatOptions.builder()
                .model(modelName)
                .frequencyPenalty(getDouble(safeOverrides, FREQUENCY_PENALTY))
                .temperature(temperature)
                .numPredict(ollamaPredict)
                .topK(getInteger(safeOverrides, TOP_K))
                .topP(getDouble(safeOverrides, TOP_P));
        if (modelConfig != null && modelConfig.getThink() != null) {
            ollamaBuilder.thinkOption(modelConfig.getThink()
                    ? ThinkOption.ThinkBoolean.ENABLED
                    : ThinkOption.ThinkBoolean.DISABLED);
        }
        return ollamaBuilder.build();
    }

    private Map<String, Object> extractExtraBody(Map<String, Object> body) {
        if (body == null || body.isEmpty()) {
            return null;
        }

        Object maxPrice = body.get(MAX_PRICE);
        if (maxPrice == null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> options = (Map<String, Object>) body.get(OPTIONS);
            if (options != null) {
                maxPrice = options.get(MAX_PRICE);
            }
        }

        if (maxPrice == null) {
            return null;
        }
        Map<String, Object> normalizedMaxPrice = normalizeMaxPrice(maxPrice);
        if (normalizedMaxPrice == null) {
            return null;
        }
        Map<String, Object> extraBody = new HashMap<>();
        extraBody.put(MAX_PRICE, normalizedMaxPrice);
        return extraBody;
    }

    private Map<String, Object> normalizeMaxPrice(Object maxPrice) {
        if (maxPrice instanceof Map<?, ?> maxPriceMap) {
            Map<String, Object> normalized = new HashMap<>();
            maxPriceMap.forEach((key, value) -> {
                if (key != null) {
                    normalized.put(key.toString(), value);
                }
            });
            return normalized;
        }
        if (maxPrice instanceof Number || maxPrice instanceof String) {
            Double maxPriceValue = maxPrice instanceof Number number
                    ? Double.valueOf(number.doubleValue())
                    : getDouble(Map.of(MAX_PRICE, maxPrice), MAX_PRICE);
            if (maxPriceValue == null) {
                log.warn("Ignoring invalid max_price value: {}", maxPrice);
                return null;
            }
            return Map.of("prompt", maxPriceValue, "completion", maxPriceValue);
        }
        log.warn("Ignoring invalid max_price type: {}. OpenRouter expects an object.",
                maxPrice.getClass().getSimpleName());
        return null;
    }

    private boolean isOpenRouterAutoModel(String modelName) {
        return springAIModelType.getByModelName(modelName)
                .filter(model -> model.getProviderType() == SpringAIModelConfig.ProviderType.OPENAI)
                .map(model -> model.getCapabilities() != null && model.getCapabilities().contains(ModelCapabilities.AUTO))
                .orElse(false);
    }

    private Map<String, Object> defaultOpenRouterFreeMaxPriceExtraBody() {
        Map<String, Object> maxPrice = Map.of(
                "prompt", 0.0d,
                "completion", 0.0d
        );
        Map<String, Object> extraBody = new HashMap<>();
        extraBody.put(MAX_PRICE, maxPrice);
        return extraBody;
    }

    private ChatClient getChatClient(SpringAIModelConfig modelConfig, String modelName) {
        Objects.requireNonNull(modelConfig, "modelConfig must not be null");
        if (modelConfig.getProviderType() != null) {
            return modelConfig.getProviderType() == SpringAIModelConfig.ProviderType.OPENAI
                    ? openAiChatClient
                    : ollamaChatClient;
        }
        if (modelName != null) {
            if (isOpenAIProvider(modelConfig, modelName)) {
                return openAiChatClient;
            }
            if (springAIModelType.isOllamaModel(modelName)) {
                return ollamaChatClient;
            }
        }
        return springAIModelType.getFirstModel()
                .filter(m -> m.getProviderType() != null)
                .map(m -> m.getProviderType() == SpringAIModelConfig.ProviderType.OPENAI ? openAiChatClient : ollamaChatClient)
                .orElse(openAiChatClient);
    }

    /**
     * Resolves reasoning token budget: per-model {@code maxReasoningTokens} overrides body
     * {@code reasoning.max_tokens}. Returns null if disabled (0 / absent).
     */
    private Integer resolveReasoningTokenBudget(SpringAIModelConfig modelConfig, Map<String, Object> safeOverrides) {
        if (modelConfig != null && modelConfig.getMaxReasoningTokens() != null) {
            int perModel = modelConfig.getMaxReasoningTokens();
            return perModel > 0 ? perModel : null;
        }
        Object reasoning = safeOverrides.get("reasoning");
        if (reasoning instanceof Map<?, ?> m) {
            Object mt = m.get("max_tokens");
            if (mt instanceof Number n) {
                int v = n.intValue();
                return v > 0 ? v : null;
            }
        }
        return null;
    }

    /**
     * Ollama: single generation budget {@code num_predict} is shared by thinking trace and answer.
     * Adds configured reasoning budget to the output cap when thinking is not explicitly off.
     */
    private int computeOllamaNumPredict(int maxTokens, SpringAIModelConfig modelConfig, Map<String, Object> safeOverrides) {
        Integer reasoningBudget = resolveReasoningTokenBudget(modelConfig, safeOverrides);
        if (reasoningBudget == null) {
            return maxTokens;
        }
        boolean thinkingExplicitlyOff = modelConfig != null && modelConfig.getThink() != null && !modelConfig.getThink();
        if (thinkingExplicitlyOff) {
            return maxTokens;
        }
        long sum = (long) maxTokens + reasoningBudget;
        return sum > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) sum;
    }

    /**
     * Resolves reasoning token budget for OpenRouter: {@code extra_body.reasoning}.
     * Returns null if reasoning should not be sent (model override = 0 or no value anywhere).
     */
    private Object resolveReasoning(SpringAIModelConfig modelConfig, Map<String, Object> safeOverrides) {
        Integer budget = resolveReasoningTokenBudget(modelConfig, safeOverrides);
        return budget != null ? Map.of("max_tokens", budget) : null;
    }

    private boolean isOpenAIProvider(SpringAIModelConfig modelConfig, String modelName) {
        if (modelConfig != null && modelConfig.getProviderType() != null) {
            return modelConfig.getProviderType() == SpringAIModelConfig.ProviderType.OPENAI;
        }
        if (modelName == null) {
            return false;
        }
        if (springAIModelType.isOpenAIModel(modelName)) {
            return true;
        }
        return looksLikeOpenRouterModel(modelName);
    }

    private boolean looksLikeOpenRouterModel(String modelName) {
        return modelName.contains("/") || modelName.contains(":free");
    }
}
