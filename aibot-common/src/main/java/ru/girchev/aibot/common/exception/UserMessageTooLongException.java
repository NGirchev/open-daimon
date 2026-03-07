package ru.girchev.aibot.common.exception;

/**
 * Исключение при превышении лимита токенов одного сообщения пользователя
 * (ai-bot.common.max-user-message-tokens).
 * Сообщение исключения предназначено для показа пользователю.
 */
public class UserMessageTooLongException extends RuntimeException {

    public UserMessageTooLongException(String message) {
        super(message);
    }

    public UserMessageTooLongException(String message, Throwable cause) {
        super(message, cause);
    }
}
