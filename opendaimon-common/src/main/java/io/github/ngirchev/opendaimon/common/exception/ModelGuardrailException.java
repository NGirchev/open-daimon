package io.github.ngirchev.opendaimon.common.exception;

import lombok.Getter;

/**
 * Thrown when a user-selected model is unavailable due to OpenRouter guardrail/data policy restrictions.
 * Presentation layer should use {@link #getModelId()} with MessageSource key
 * {@code common.error.model.guardrail} to notify the user, then retry with auto-routing.
 */
@Getter
public class ModelGuardrailException extends RuntimeException {

    private final String modelId;

    public ModelGuardrailException(String modelId) {
        super("Model \"" + modelId + "\" is unavailable due to OpenRouter guardrail/data policy restrictions");
        this.modelId = modelId;
    }
}
