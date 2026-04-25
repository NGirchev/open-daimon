package io.github.ngirchev.opendaimon.telegram.service;

/**
 * Chat-scoped + global outbound rate limiter for Telegram operations.
 *
 * <p>Every outgoing call ({@code sendMessage}, {@code editMessageText},
 * {@code deleteMessage}, keyboard sends) must pass through this limiter
 * before touching the network. Implementations enforce three rules:
 *
 * <ol>
 *   <li>Per-chat sliding window (capacity + window depend on chat type:
 *       1 op / 1 sec for private chats, 20 ops / 60 sec for groups).</li>
 *   <li>Per-chat minimum interval between consecutive ops (only for groups,
 *       defaults to 3.5 sec — Telegram returns 429 below this floor even
 *       when the formal 20/min quota is not reached).</li>
 *   <li>Global sliding window across all chats (default 30 ops / 1 sec —
 *       Telegram per-bot ceiling).</li>
 * </ol>
 *
 * <p>Chat type is inferred from the sign of {@code chatId}: positive ids are
 * private chats (id == user id), negative ids are groups / supergroups.
 */
public interface TelegramChatRateLimiter {

    /**
     * Non-blocking attempt. Returns {@code true} when a slot was reserved
     * immediately (all three rules passed), {@code false} otherwise.
     *
     * <p>Used for best-effort calls (status edits, partial-answer edits) where
     * dropping the call is preferable to blocking the streaming pipeline.
     */
    boolean tryAcquire(long chatId);

    /**
     * Blocking attempt with a timeout. Returns {@code true} when a slot was
     * acquired within {@code timeoutMs}, {@code false} on timeout.
     *
     * <p>Used for critical calls (final answer delivery, opening a new bubble,
     * notifications) where waiting is acceptable.
     *
     * <p>Internally polls the bucket state — the calling thread is blocked
     * via {@link Thread#sleep}. Callers MUST run on a thread pool that
     * tolerates blocking (typically {@code priority-request-executor-N});
     * never call from a Reactor scheduler thread.
     *
     * @throws InterruptedException when the calling thread is interrupted
     *         while sleeping
     */
    boolean acquire(long chatId, long timeoutMs) throws InterruptedException;
}
