package io.github.ngirchev.aibot.ai.springai.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import io.github.ngirchev.aibot.common.ai.ModelCapabilities;

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

