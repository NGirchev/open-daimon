package io.github.ngirchev.opendaimon.common.ai;

import lombok.extern.slf4j.Slf4j;

import java.util.Map;

@Slf4j
public final class LlmParamNames {

    private LlmParamNames() {}

    public static final String TEMPERATURE = "temperature";
    public static final String MAX_TOKENS = "max_tokens";
    public static final String TOP_P = "top_p";
    public static final String TOP_K = "top_k";
    public static final String FREQUENCY_PENALTY = "frequency_penalty";
    public static final String PRESENCE_PENALTY = "presence_penalty";

    // Less commonly used
    public static final String STOP = "stop";
    public static final String SEED = "seed";
    public static final String LOGPROBS = "logprobs";
    public static final String TOP_LOGPROBS = "top_logprobs";

    // OpenRouter / multi-model stuff
    public static final String MODEL = "model";
    public static final String MESSAGES = "messages";
    public static final String CHOICES = "choices";
    public static final String STREAM = "stream";
    
    // Message structure
    public static final String ROLE = "role";
    public static final String CONTENT = "content";
    public static final String MESSAGE = "message";
    
    // Message roles
    public static final String ROLE_SYSTEM = "system";
    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";
    
    // Response structure
    public static final String USAGE = "usage";
    public static final String FINISH_REASON = "finish_reason";
    public static final String PROMPT_TOKENS = "prompt_tokens";
    public static final String COMPLETION_TOKENS = "completion_tokens";
    public static final String TOTAL_TOKENS = "total_tokens";
    public static final String ACTUAL_MODEL = "actual_model";
    public static final String ID = "id";
    public static final String RESPONSE_CALLS = "responseCalls";
    public static final String GENERATION_RESULTS = "generationResults";
    
    // Other
    public static final String OPTIONS = "options";
    public static final String CONVERSATION_ID = "conversationId";
    public static final String MAX_PRICE = "max_price";

    /**
     * Safely extracts Double from Map.
     * Supports conversion from Integer, Long, Float, String.
     *
     * @param requestBody request params map
     * @param key key to extract
     * @return Double or null if key missing or value not convertible
     */
    public static Double getDouble(Map<String, Object> requestBody, String key) {
        Object value = requestBody.get(key);
        switch (value) {
            case null -> {
                return null;
            }
            case Double doubleValue -> {
                return doubleValue;
            }
            case Float floatValue -> {
                return floatValue.doubleValue();
            }
            case Integer integerValue -> {
                return integerValue.doubleValue();
            }
            case Long longValue -> {
                return longValue.doubleValue();
            }
            case String stringValue -> {
                try {
                    return Double.parseDouble(stringValue);
                } catch (NumberFormatException e) {
                    log.warn("Cannot parse Double from string value '{}' for key '{}'", value, key);
                    return null;
                }
            }
            default -> {
                log.warn("Cannot convert value '{}' of type '{}' to Double for key '{}'",
                        value, value.getClass().getSimpleName(), key);
                return null;
            }
        }
    }

    /**
     * Safely extracts Integer from Map.
     * Supports conversion from Long, Double, Float, String.
     *
     * @param requestBody request params map
     * @param key key to extract
     * @return Integer or null if key missing or value not convertible
     */
    public static Integer getInteger(Map<String, Object> requestBody, String key) {
        Object value = requestBody.get(key);
        switch (value) {
            case null -> {
                return null;
            }
            case Integer intValue -> {
                return intValue;
            }
            case Long longValue -> {
                return longValue.intValue();
            }
            case Double doubleValue -> {
                return doubleValue.intValue();
            }
            case Float floatValue -> {
                return floatValue.intValue();
            }
            case String stringValue -> {
                try {
                    return Integer.parseInt(stringValue);
                } catch (NumberFormatException e) {
                    log.warn("Cannot parse Integer from string value '{}' for key '{}'", value, key);
                    return null;
                }
            }
            default -> {
                log.warn("Cannot convert value '{}' of type '{}' to Integer for key '{}'",
                        value, value.getClass().getSimpleName(), key);
                return null;
            }
        }
    }
}
