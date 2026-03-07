package ru.girchev.aibot.common.exception;

/**
 * Thrown when user message exceeds token limit
 * (ai-bot.common.max-user-message-tokens).
 * Exception message is intended for display to the user.
 */
public class UserMessageTooLongException extends RuntimeException {

    public UserMessageTooLongException(String message) {
        super(message);
    }

    public UserMessageTooLongException(String message, Throwable cause) {
        super(message, cause);
    }
}
