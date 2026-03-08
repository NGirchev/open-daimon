package io.github.ngirchev.aibot.telegram.command.handler;

import lombok.Getter;

@Getter
public class TelegramCommandHandlerException extends RuntimeException {
    private final Long userId;

    public TelegramCommandHandlerException(String message, Throwable cause) {
        super(message, cause);
        this.userId = null;
    }

    public TelegramCommandHandlerException(Long userId, String message) {
        super(message);
        this.userId = userId;
    }
}
