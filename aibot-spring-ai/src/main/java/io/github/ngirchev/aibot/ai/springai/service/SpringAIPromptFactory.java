package io.github.ngirchev.aibot.ai.springai.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import io.github.ngirchev.aibot.ai.springai.advisor.MessageOrderingAdvisor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.content.Media;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import io.github.ngirchev.aibot.ai.springai.config.SpringAIModelConfig;
import io.github.ngirchev.aibot.ai.springai.tool.WebTools;
import io.github.ngirchev.aibot.common.ai.ModelCapabilities;
import io.github.ngirchev.aibot.common.ai.command.AIBotChatOptions;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID;
import static io.github.ngirchev.aibot.common.ai.LlmParamNames.*;

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
            AIBotChatOptions chatOptions
    ) {
        String resolvedModelName = modelConfig != null ? modelConfig.getName() : modelName;
        ChatClient chatClient = getChatClient(modelConfig, resolvedModelName);
        var promptBuilder = chatClient.prompt();
        promptBuilder.options(buildChatOptions(modelConfig, resolvedModelName, body, chatOptions));

        // Add MessageChatMemoryAdvisor to inject history from ChatMemory
        // IMPORTANT: MessageChatMemoryAdvisor adds history BEFORE System messages (known Spring AI #4170 bug)
        // So we add MessageOrderingAdvisor after it to fix order: System -> History -> User
        if (useChatMemoryAdvisor && conversationId != null) {
            promptBuilder
                    .advisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                    .advisors(a -> a.param(CONVERSATION_ID, conversationId))
                    .advisors(new MessageOrderingAdvisor()); // Reorder: System first
        }

        // Add System message (if any) — Spring AI ensures System messages are always at the start of prompt
        if (messages != null && !messages.isEmpty()) {
            if (useChatMemoryAdvisor) {
                for (Message message : messages) {
                    if (message instanceof SystemMessage systemMessage) {
                        promptBuilder.system(systemMessage.getText());
                    }
                }
            }
        }

        if (webEnabled) {
            promptBuilder.tools(webTools);
            log.info("Web tools added to prompt (web_search, fetch_url). Model may invoke them.");
        } else {
            log.info("Web tools NOT added to prompt (webEnabled=false). Serper/fetch_url will not be available. Only VIP users get WEB capability in DefaultAICommandFactory.");
        }

        // Finally add User message — it will be last
        if (messages != null && !messages.isEmpty()) {
            if (useChatMemoryAdvisor) {
                // With ChatMemory: MessageChatMemoryAdvisor adds history from ChatMemory between System and User.
                // IMPORTANT: Add only the LAST User message to avoid duplication; advisor already added history.
                UserMessage lastUserMessage = null;
                for (Message message : messages) {
                    if (message instanceof UserMessage userMessage) {
                        lastUserMessage = userMessage; // Keep last User message
                    }
                }
                if (lastUserMessage != null) {
                    final UserMessage userMsg = lastUserMessage;
                    // Add last User message with media for photo support in ChatMemory (local).
                    if (userMsg.getMedia() != null && !userMsg.getMedia().isEmpty()) {
                        promptBuilder.user(u -> u.text(userMsg.getText())
                                .media(userMsg.getMedia().toArray(new Media[0])));
                    } else {
                        promptBuilder.user(userMsg.getText());
                    }
                }
                // Ignore AssistantMessage — already in ChatMemory
            } else {
                // In manual mode pass full history as-is
                promptBuilder.messages(messages);
            }
        }

        return promptBuilder;
    }

    private ChatOptions buildChatOptions(
            SpringAIModelConfig modelConfig,
            String modelName,
            Map<String, Object> body,
            AIBotChatOptions chatOptions
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

        Map<String, Object> normalizedMaxPrice;
        if (maxPrice instanceof Map<?, ?> maxPriceMap) {
            normalizedMaxPrice = new HashMap<>();
            maxPriceMap.forEach((key, value) -> {
                if (key != null) {
                    normalizedMaxPrice.put(key.toString(), value);
                }
            });
        } else if (maxPrice instanceof Number || maxPrice instanceof String) {
            Double maxPriceValue = maxPrice instanceof Number number
                    ? Double.valueOf(number.doubleValue())
                    : getDouble(Map.of(MAX_PRICE, maxPrice), MAX_PRICE);
            if (maxPriceValue == null) {
                log.warn("Ignoring invalid max_price value: {}", maxPrice);
                return null;
            }
            normalizedMaxPrice = Map.of(
                    "prompt", maxPriceValue,
                    "completion", maxPriceValue
            );
        } else {
            log.warn("Ignoring invalid max_price type: {}. OpenRouter expects an object.",
                    maxPrice.getClass().getSimpleName());
            return null;
        }

        Map<String, Object> extraBody = new HashMap<>();
        extraBody.put(MAX_PRICE, normalizedMaxPrice);
        return extraBody;
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
        if (modelConfig != null && modelConfig.getProviderType() != null) {
            return modelConfig.getProviderType() == SpringAIModelConfig.ProviderType.OPENAI
                    ? openAiChatClient
                    : ollamaChatClient;
        }
        if (modelName != null) {
            if (isOpenAIProvider(null, modelName)) {
                return openAiChatClient;
            } else if (springAIModelType.isOllamaModel(modelName)) {
                return ollamaChatClient;
            }
        }

        return ollamaChatClient;
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
