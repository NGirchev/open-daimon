package io.github.ngirchev.opendaimon.telegram.service;

import io.github.ngirchev.opendaimon.telegram.TelegramBot;
import io.github.ngirchev.opendaimon.telegram.config.TelegramProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboard;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.LongSupplier;

/**
 * Synchronous, rate-limited facade over {@link TelegramBot}.
 *
 * <p>Every {@code sendMessage}/{@code editMessage}/{@code deleteMessage} call blocks
 * the caller until <strong>both</strong> per-chat and global quota slots are
 * available, then performs the network call. By construction we never exceed the
 * published Telegram limits — there is no path that allows a burst — so we don't
 * receive HTTP 429 from Telegram.
 *
 * <p>Quota model:
 * <ul>
 *   <li>private chat ({@code chatId > 0}) — {@code privateChatPerSecond} ops, default 1/sec.</li>
 *   <li>group/supergroup ({@code chatId < 0}) — {@code groupChatPerMinute} ops, default 20/min.</li>
 *   <li>per-bot global cap — {@code globalPerSecond} ops, default 30/sec.</li>
 * </ul>
 *
 * <p>If both queues stay closed for longer than {@code maxAcquireWaitMs} (default 60s)
 * the call returns null/false instead of blocking the caller forever — that ceiling
 * exists so a stuck channel never corrupts a Reactor pipeline thread.
 *
 * <p>Thread safety: per-chat mutex keeps order-of-arrival inside one chat; the global
 * cap is a single sliding-window deque under its own monitor. Different chats run in
 * parallel as long as the global cap allows.
 */
@Slf4j
public class TelegramRateLimitedBot {

    private final ObjectProvider<TelegramBot> botProvider;
    private final TelegramProperties.RateLimit config;
    private final LongSupplier clock;
    private final Sleeper sleeper;

    private final ConcurrentMap<Long, ChatLane> chatLanes = new ConcurrentHashMap<>();
    private final Object globalLock = new Object();
    private final Deque<Long> globalTimestamps = new ArrayDeque<>();

    public TelegramRateLimitedBot(ObjectProvider<TelegramBot> botProvider,
                                  TelegramProperties.RateLimit config) {
        this(botProvider, config, System::currentTimeMillis, Thread::sleep);
    }

    // visible for tests — lets tests inject a virtual clock and sleeper
    public TelegramRateLimitedBot(ObjectProvider<TelegramBot> botProvider,
                                  TelegramProperties.RateLimit config,
                                  LongSupplier clock,
                                  Sleeper sleeper) {
        this.botProvider = botProvider;
        this.config = config;
        this.clock = clock;
        this.sleeper = sleeper;
    }

    /** Send a plain HTML message. Returns the new messageId, or {@code null} on failure. */
    public Integer sendMessage(long chatId, String html, Integer replyToMessageId,
                               boolean disableLinkPreview) {
        return sendMessage(chatId, html, replyToMessageId, disableLinkPreview, null);
    }

    /**
     * Send an HTML message, optionally with a reply keyboard. Returns the new messageId,
     * or {@code null} if the bot is unavailable / the call timed out / Telegram errored.
     */
    public Integer sendMessage(long chatId, String html, Integer replyToMessageId,
                               boolean disableLinkPreview, ReplyKeyboard keyboard) {
        TelegramBot bot = botProvider.getIfAvailable();
        if (bot == null) {
            log.warn("TelegramBot not available; sendMessage skipped for chatId={}", chatId);
            return null;
        }
        if (!await(chatId)) {
            return null;
        }
        try {
            if (keyboard == null) {
                return bot.sendMessageAndGetId(chatId, html, replyToMessageId, disableLinkPreview);
            }
            SendMessage sm = new SendMessage();
            sm.setChatId(String.valueOf(chatId));
            sm.setText(html);
            sm.setParseMode("HTML");
            sm.setDisableWebPagePreview(disableLinkPreview);
            if (replyToMessageId != null) {
                sm.setReplyToMessageId(replyToMessageId);
            }
            sm.setReplyMarkup(keyboard);
            Message msg = bot.execute(sm);
            return msg != null ? msg.getMessageId() : null;
        } catch (TelegramApiException e) {
            log.warn("sendMessage failed for chatId={}: {}", chatId, e.getMessage());
            return null;
        }
    }

    /** Edit a previously-sent message. Returns true on success. */
    public boolean editMessage(long chatId, int messageId, String html, boolean disableLinkPreview) {
        TelegramBot bot = botProvider.getIfAvailable();
        if (bot == null) {
            return false;
        }
        if (!await(chatId)) {
            return false;
        }
        try {
            bot.editMessageHtml(chatId, messageId, html, disableLinkPreview);
            return true;
        } catch (TelegramApiException e) {
            log.warn("editMessage failed for chatId={} messageId={}: {}", chatId, messageId, e.getMessage());
            return false;
        }
    }

    /** Delete a message. Returns true when Telegram acknowledged the delete. */
    public boolean deleteMessage(long chatId, int messageId) {
        TelegramBot bot = botProvider.getIfAvailable();
        if (bot == null) {
            return false;
        }
        if (!await(chatId)) {
            return false;
        }
        try {
            bot.deleteMessage(chatId, messageId);
            return true;
        } catch (TelegramApiException e) {
            log.debug("deleteMessage failed for chatId={} messageId={}: {}", chatId, messageId, e.getMessage());
            return false;
        }
    }

    // ─── Slot acquisition ──────────────────────────────────────────────────

    /**
     * Block until a per-chat quota slot opens, then claim a global slot. Returns true
     * when the slot is reserved; false only when waiting exceeded the configured
     * {@code maxAcquireWaitMs}.
     */
    private boolean await(long chatId) {
        long deadlineAt = clock.getAsLong() + Math.max(0, config.getMaxAcquireWaitMs());
        try {
            if (!awaitChatSlot(chatId, deadlineAt)) {
                log.warn("rate limiter: per-chat slot wait timed out for chatId={}", chatId);
                return false;
            }
            if (!awaitGlobalSlot(deadlineAt)) {
                log.warn("rate limiter: global slot wait timed out for chatId={}", chatId);
                return false;
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private boolean awaitChatSlot(long chatId, long deadlineAt) throws InterruptedException {
        ChatLane lane = chatLanes.computeIfAbsent(chatId, ChatLane::new);
        synchronized (lane.lock) {
            while (true) {
                long now = clock.getAsLong();
                long delay = lane.nextAllowedAtMs - now;
                if (delay <= 0) {
                    lane.nextAllowedAtMs = now + chatIntervalMs(chatId);
                    return true;
                }
                long remaining = deadlineAt - now;
                if (remaining <= 0) {
                    return false;
                }
                sleeper.sleep(Math.min(delay, remaining));
            }
        }
    }

    private boolean awaitGlobalSlot(long deadlineAt) throws InterruptedException {
        synchronized (globalLock) {
            while (true) {
                long now = clock.getAsLong();
                cleanup(now - 1_000L);
                if (globalTimestamps.size() < config.getGlobalPerSecond()) {
                    globalTimestamps.addLast(now);
                    return true;
                }
                Long oldest = globalTimestamps.peekFirst();
                long delay = oldest == null ? 1_000L : Math.max(1L, oldest + 1_000L - now);
                long remaining = deadlineAt - now;
                if (remaining <= 0) {
                    return false;
                }
                sleeper.sleep(Math.min(delay, remaining));
            }
        }
    }

    private void cleanup(long cutoff) {
        while (!globalTimestamps.isEmpty() && globalTimestamps.peekFirst() <= cutoff) {
            globalTimestamps.pollFirst();
        }
    }

    private long chatIntervalMs(long chatId) {
        if (chatId < 0) {
            return Math.max(1L, 60_000L / config.getGroupChatPerMinute());
        }
        return Math.max(1L, 1_000L / config.getPrivateChatPerSecond());
    }

    @FunctionalInterface
    public interface Sleeper {
        void sleep(long ms) throws InterruptedException;
    }

    private static final class ChatLane {
        final long chatId;
        final Object lock = new Object();
        long nextAllowedAtMs;

        ChatLane(long chatId) {
            this.chatId = chatId;
        }
    }
}
