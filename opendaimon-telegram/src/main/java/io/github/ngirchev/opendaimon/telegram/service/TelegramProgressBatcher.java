package io.github.ngirchev.opendaimon.telegram.service;

import java.util.Optional;

/**
 * Debounces {@code editMessageText} calls to respect Telegram's ~1 edit/sec per chat
 * limit (bursts trigger 429 "Too Many Requests" with long retry windows).
 *
 * <p>Stateless utility — all per-conversation state (last-edit timestamps, text buffers)
 * is owned by the caller's FSM context ({@code MessageHandlerContext}). The batcher only
 * evaluates two questions:
 *
 * <ol>
 *   <li>{@link #shouldFlush(long, long, long, boolean)} — given the previous flush timestamp,
 *       the current clock, and the debounce window, should the caller invoke the network
 *       edit now? When {@code forceFlush} is {@code true} the answer is always yes
 *       (structural events such as {@code TOOL_CALL}, {@code OBSERVATION},
 *       {@code FINAL_ANSWER}, {@code ERROR}, rollback, or max-iterations must not be
 *       deferred).</li>
 *   <li>{@link #selectContentToFlush(StringBuilder, int)} — if the accumulated buffer
 *       exceeds {@code maxLength}, returns the finalized head produced by
 *       {@link TelegramBufferRotator} (paragraph / sentence / whitespace boundary) and
 *       mutates the buffer in place to hold only the tail. The caller owns the rotation
 *       book-keeping (sending the head as a finalized previous message, updating the
 *       current message id, etc.).</li>
 * </ol>
 *
 * <p>Design note: this class intentionally does not manage a queue or a timer. Debouncing
 * is pull-based — evaluated on every incoming stream event — so the orchestrator remains
 * single-threaded and can be reasoned about without concurrency or scheduling concerns.
 */
public final class TelegramProgressBatcher {

    private TelegramProgressBatcher() {
    }

    /**
     * Returns {@code true} when the caller should push the pending buffer to Telegram and
     * {@code false} when the edit should be deferred until the next event that arrives
     * after the debounce window has elapsed (or until a {@code forceFlush}).
     *
     * <p>Semantics:
     * <ul>
     *   <li>{@code forceFlush == true} → always flush (structural / terminal events).</li>
     *   <li>{@code debounceMs <= 0} → throttling disabled (test fixtures); always flush.</li>
     *   <li>otherwise flush iff {@code nowMs - lastFlushAtMs >= debounceMs}.</li>
     * </ul>
     *
     * @param lastFlushAtMs epoch-ms of the previous successful flush ({@code 0} means never)
     * @param nowMs         current epoch-ms
     * @param debounceMs    minimum interval between consecutive flushes; {@code 0} disables
     * @param forceFlush    bypass the debounce window for structural / terminal events
     * @return {@code true} when the caller should issue the edit now
     */
    public static boolean shouldFlush(long lastFlushAtMs, long nowMs, long debounceMs, boolean forceFlush) {
        if (forceFlush) {
            return true;
        }
        if (debounceMs <= 0) {
            return true;
        }
        return (nowMs - lastFlushAtMs) >= debounceMs;
    }

    /**
     * Rotates the buffer at a graceful boundary when it would exceed
     * {@code maxLength}. Delegates to {@link TelegramBufferRotator#rotateIfExceeds} so
     * cut-selection uses the project's shared priority ladder (paragraph → sentence →
     * whitespace → hard cut). When rotation fires the buffer is mutated in place to hold
     * only the tail; the returned head is the finalized fragment the caller should send
     * as the now-closed previous message.
     *
     * @param buffer    mutable buffer holding the pending edit payload
     * @param maxLength Telegram message-body limit to respect
     * @return the extracted head when rotation was needed, otherwise empty
     */
    public static Optional<String> selectContentToFlush(StringBuilder buffer, int maxLength) {
        return TelegramBufferRotator.rotateIfExceeds(buffer, maxLength);
    }
}
