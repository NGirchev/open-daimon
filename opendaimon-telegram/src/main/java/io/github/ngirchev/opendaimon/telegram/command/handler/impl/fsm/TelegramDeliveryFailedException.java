package io.github.ngirchev.opendaimon.telegram.command.handler.impl.fsm;

public class TelegramDeliveryFailedException extends RuntimeException {

    public TelegramDeliveryFailedException(String message) {
        super(message);
    }

    public TelegramDeliveryFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
