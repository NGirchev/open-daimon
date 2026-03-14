package io.github.ngirchev.opendaimon.common.exception;

import lombok.Getter;

/**
 * Thrown when user message exceeds token limit
 * (open-daimon.common.max-user-message-tokens).
 * Presentation layer should use {@link #getEstimatedTokens()} and {@link #getMaxAllowed()}
 * with MessageSource key {@code common.error.message.too.long} for localized user message.
 */
@Getter
public class UserMessageTooLongException extends RuntimeException {

    private final int estimatedTokens;
    private final int maxAllowed;

    public UserMessageTooLongException(String message) {
        super(message);
        this.estimatedTokens = 0;
        this.maxAllowed = 0;
    }

    public UserMessageTooLongException(String message, Throwable cause) {
        super(message, cause);
        this.estimatedTokens = 0;
        this.maxAllowed = 0;
    }

    /**
     * Preferred constructor for presentation-layer localization.
     */
    public UserMessageTooLongException(int estimatedTokens, int maxAllowed) {
        super("Message too long: " + estimatedTokens + " tokens, limit " + maxAllowed);
        this.estimatedTokens = estimatedTokens;
        this.maxAllowed = maxAllowed;
    }
}
