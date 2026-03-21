package io.github.ngirchev.opendaimon.ai.springai.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import io.github.ngirchev.opendaimon.common.ai.ModelCapabilities;
import io.github.ngirchev.opendaimon.bulkhead.model.UserPriority;

import java.util.List;
import java.util.Set;

@Getter
@Setter
public class SpringAIModelConfig {
    
    @NotBlank(message = "Name of model cannot be blank")
    private String name;
    
    @NotEmpty(message = "List of capabilities cannot be empty")
    private Set<ModelCapabilities> capabilities;
    
    @NotNull(message = "Provider type is required")
    private ProviderType providerType;
    
    @NotNull(message = "Priority is required")
    @Min(value = 1, message = "Priority must be >= 1")
    private Integer priority;

    /**
     * Roles allowed to use this model. If null or empty — all roles can access it.
     * Values: ADMIN, VIP, REGULAR (from UserPriority enum).
     */
    private List<UserPriority> allowedRoles;

    /**
     * Per-model output token limit. Overrides global CoreCommonProperties.maxOutputTokens.
     * null  — use global default.
     * &gt;0 — use this value instead of global.
     */
    @Min(value = 1, message = "maxOutputTokens must be >= 1")
    private Integer maxOutputTokens;

    /**
     * Per-model reasoning token budget. Overrides global CoreCommonProperties.maxReasoningTokens.
     * null  — use global default.
     * 0     — disable reasoning for this model.
     * &gt;0 — use this value instead of global.
     */
    @Min(value = 0, message = "maxReasoningTokens must be >= 0")
    private Integer maxReasoningTokens;

    /**
     * Ollama-only: controls the "think" (extended reasoning) mode for models that support it (e.g. Qwen3).
     * null  — do not send the parameter (Ollama default; some models return 400 if the param is sent).
     * true  — enable thinking.
     * false — disable thinking (recommended for Qwen3 to avoid empty responses when token budget is exhausted by thinking).
     */
    private Boolean think;

    /**
     * Returns true if the given user priority is allowed to use this model.
     * If allowedRoles is null or empty — all roles are allowed.
     */
    public boolean isAllowedForRole(UserPriority userPriority) {
        if (allowedRoles == null || allowedRoles.isEmpty()) {
            return true;
        }
        if (userPriority == null) {
            return true;
        }
        return allowedRoles.contains(userPriority);
    }
    
    public enum ProviderType {
        OLLAMA,
        OPENAI
    }

    /**
     * Model is free (OpenRouter free tier etc.) if capabilities contain FREE.
     * Add FREE in yml only for actually free models; do not add for openrouter/auto.
     */
    public boolean isFree() {
        return capabilities != null && capabilities.contains(ModelCapabilities.FREE);
    }
}

