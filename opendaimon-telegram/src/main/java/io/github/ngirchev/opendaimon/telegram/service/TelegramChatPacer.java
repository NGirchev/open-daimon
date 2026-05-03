package io.github.ngirchev.opendaimon.telegram.service;

/**
 * Chat-scoped pacing gate for outbound Telegram operations.
 *
 * <p>This is not a dispatcher queue. Callers keep their own semantic buffers and ask the
 * pacer only when they are ready to send a current snapshot to Telegram.
 */
public interface TelegramChatPacer {

    boolean tryReserve(long chatId);

    boolean reserve(long chatId, long timeoutMs) throws InterruptedException;

    long intervalMs(long chatId);
}
