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
    /**
     * true -> Spring AI ChatMemory (MessageChatMemoryAdvisor) is the source of history.
     * false -> history is provided explicitly via messages (manual context builder).
     */
    private final boolean useChatMemoryAdvisor;

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

        if (useChatMemoryAdvisor && conversationId != null) {
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
        if (messages == null || messages.isEmpty() || !useChatMemoryAdvisor) return;
        for (Message message : messages) {
            if (message instanceof SystemMessage systemMessage) {
                promptBuilder.system(systemMessage.getText());
            }
        }
    }

    private void addWebToolsIfEnabled(ChatClient.ChatClientRequestSpec promptBuilder, boolean webEnabled) {
        if (webEnabled) {
            promptBuilder.tools(webTools);
            log.info("Web tools added to prompt (web_search, fetch_url). Model may invoke them.");
        } else {
            log.info("Web tools NOT added to prompt (webEnabled=false). Serper/fetch_url will not be available. Only VIP users get WEB capability in DefaultAICommandFactory.");
        }
    }

    private void addUserOrAllMessages(ChatClient.ChatClientRequestSpec promptBuilder, List<Message> messages) {
        if (messages == null || messages.isEmpty()) return;
        if (useChatMemoryAdvisor) {
            addLastUserMessageToPrompt(promptBuilder, messages);
        } else {
            promptBuilder.messages(messages);
        }
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
            Object reasoning = safeOverrides.get("reasoning");
            if (reasoning != null) {
                extraBody.put("reasoning", reasoning);
            }
            if (extraBody.isEmpty() && isOpenRouterAutoModel(modelName)) {
                extraBody = defaultOpenRouterFreeMaxPriceExtraBody();
                log.debug("Applying default max_price=0 for model={}", modelName);
            }
            if (!extraBody.isEmpty()) {
                optionsBuilder.extraBody(extraBody);
            }

            return optionsBuilder.build();
        }

        // Ollama: do not pass think — some versions/models return 400 for this param. Enable via options in model config if needed.
        return OllamaChatOptions.builder()
                .model(modelName)
                .frequencyPenalty(getDouble(safeOverrides, FREQUENCY_PENALTY))
                .temperature(temperature)
                .numPredict(maxTokens)
                .topK(getInteger(safeOverrides, TOP_K))
                .topP(getDouble(safeOverrides, TOP_P))
                .build();
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
