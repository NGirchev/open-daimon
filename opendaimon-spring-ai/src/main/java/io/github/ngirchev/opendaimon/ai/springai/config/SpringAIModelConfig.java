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

@Getter
@Setter
public class SpringAIModelConfig {
    
    @NotBlank(message = "Name of model cannot be blank")
    private String name;
    
    @NotEmpty(message = "List of capabilities cannot be empty")
    private List<ModelCapabilities> capabilities;
    
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

