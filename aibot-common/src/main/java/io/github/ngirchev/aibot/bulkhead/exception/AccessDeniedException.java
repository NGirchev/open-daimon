package io.github.ngirchev.aibot.bulkhead.exception;

/**
 * Thrown when access to resources is denied.
 * Used when user is blocked or thread pool is exhausted.
 */
public class AccessDeniedException extends RuntimeException {

    /**
     * Creates exception with given message.
     *
     * @param message error message
     */
    public AccessDeniedException(String message) {
        super(message);
    }

    /**
     * Creates exception with message and cause.
     *
     * @param message error message
     * @param cause cause
     */
    public AccessDeniedException(String message, Throwable cause) {
        super(message, cause);
    }
} 