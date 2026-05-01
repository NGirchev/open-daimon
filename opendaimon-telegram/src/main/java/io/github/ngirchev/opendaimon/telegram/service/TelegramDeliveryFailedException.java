package io.github.ngirchev.opendaimon.telegram.service;

public class TelegramDeliveryFailedException extends RuntimeException {

    public TelegramDeliveryFailedException(String message) {
        super(message);
    }

    public TelegramDeliveryFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
