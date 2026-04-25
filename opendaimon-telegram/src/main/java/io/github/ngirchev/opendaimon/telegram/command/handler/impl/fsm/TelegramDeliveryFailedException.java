package io.github.ngirchev.opendaimon.telegram.command.handler.impl.fsm;

/**
 * Thrown when Telegram refuses every retry attempt to deliver a critical message
 * (final agent answer, terminal status update). Always paired with
 * {@link MessageHandlerErrorType#TELEGRAM_DELIVERY_FAILED} on the FSM context so
 * the dispatch code in {@code MessageTelegramCommandHandler} skips user-visible
 * notification (the same chat we are flooding will not accept one anyway).
 */
public class TelegramDeliveryFailedException extends RuntimeException {

    public TelegramDeliveryFailedException(String message) {
        super(message);
    }

    public TelegramDeliveryFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
