package io.github.ngirchev.opendaimon.telegram.service;

import io.github.ngirchev.opendaimon.telegram.config.TelegramProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Sliding-window implementation of {@link TelegramChatRateLimiter}.
 *
 * <p>Per-chat buckets live in a {@link ConcurrentHashMap}; the global bucket
 * is a single {@link ArrayDeque} guarded by its own monitor. Each per-chat
 * bucket synchronizes on itself, so contention is per-chat rather than
 * global. The global lock is held only for the cleanup-and-decide step.
 *
 * <p>Memory: ~200 bytes/chat (entry + ArrayDeque header + ~20 longs).
 * 10k active chats ≈ 3 MB — eviction not needed for typical loads. If
 * memory pressure shows up in production, add a periodic prune of buckets
 * whose deque is empty and last access was &gt; N minutes ago.
 *
 * <p>Acquire ordering: per-chat first, then global. Per-chat rejection is
 * the common case (group min-interval kicks in often), so checking it first
 * avoids touching the global lock on the cheap-reject path. On global
 * rejection the per-chat reservation is rolled back so the slot is not
 * spuriously consumed.
 */
@Slf4j
public class TelegramChatRateLimiterImpl implements TelegramChatRateLimiter {

    private static final long PRIVATE_WINDOW_MS = 1_000L;
    private static final long GROUP_WINDOW_MS = 60_000L;
    private static final long GLOBAL_WINDOW_MS = 1_000L;
    private static final long POLL_SLEEP_MS = 50L;

    public enum ChatType { PRIVATE, GROUP }

    public enum SkipReason { CHAT_CAPACITY, CHAT_MIN_INTERVAL, GLOBAL_CAPACITY, TIMEOUT }

    private final TelegramProperties.RateLimit config;
    private final LongSupplier clock;
    private final MeterRegistry meterRegistry;

    private final Object globalMonitor = new Object();
    private final ArrayDeque<Long> globalTimestamps = new ArrayDeque<>();
    private final ConcurrentHashMap<Long, ChatBucket> chatBuckets = new ConcurrentHashMap<>();

    public TelegramChatRateLimiterImpl(TelegramProperties.RateLimit config, MeterRegistry meterRegistry) {
        this(config, System::currentTimeMillis, meterRegistry);
    }

    // Visible for tests.
    TelegramChatRateLimiterImpl(TelegramProperties.RateLimit config,
                                LongSupplier clock,
                                MeterRegistry meterRegistry) {
        this.config = config;
        this.clock = clock;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public boolean tryAcquire(long chatId) {
        ChatType type = chatType(chatId);
        long now = clock.getAsLong();
        ChatBucket bucket = chatBuckets.computeIfAbsent(chatId, k -> new ChatBucket());

        SkipReason chatReason = bucket.tryReserve(now, type, config);
        if (chatReason != null) {
            recordSkip(type, chatReason);
            return false;
        }

        if (!tryReserveGlobal(now)) {
            bucket.rollback(now);
            recordSkip(type, SkipReason.GLOBAL_CAPACITY);
            return false;
        }

        recordSuccess(type);
        return true;
    }

    @Override
    public boolean acquire(long chatId, long timeoutMs) throws InterruptedException {
        long deadline = clock.getAsLong() + timeoutMs;
        while (true) {
            if (tryAcquire(chatId)) {
                return true;
            }
            long now = clock.getAsLong();
            if (now >= deadline) {
                recordSkip(chatType(chatId), SkipReason.TIMEOUT);
                return false;
            }
            long remaining = deadline - now;
            Thread.sleep(Math.min(POLL_SLEEP_MS, remaining));
        }
    }

    private boolean tryReserveGlobal(long now) {
        synchronized (globalMonitor) {
            cleanup(globalTimestamps, now - GLOBAL_WINDOW_MS);
            if (globalTimestamps.size() >= config.getGlobalPerSecond()) {
                return false;
            }
            globalTimestamps.addLast(now);
            return true;
        }
    }

    private static void cleanup(ArrayDeque<Long> timestamps, long cutoff) {
        while (!timestamps.isEmpty() && timestamps.peekFirst() <= cutoff) {
            timestamps.pollFirst();
        }
    }

    private static ChatType chatType(long chatId) {
        return chatId < 0 ? ChatType.GROUP : ChatType.PRIVATE;
    }

    private void recordSkip(ChatType type, SkipReason reason) {
        Counter.builder("telegram.rate_limiter.acquire.skipped.count")
                .tag("chat_type", type.name().toLowerCase())
                .tag("reason", reason.name().toLowerCase())
                .register(meterRegistry)
                .increment();
    }

    private void recordSuccess(ChatType type) {
        Counter.builder("telegram.rate_limiter.acquire.success.count")
                .tag("chat_type", type.name().toLowerCase())
                .register(meterRegistry)
                .increment();
    }

    private static final class ChatBucket {
        private final ArrayDeque<Long> timestamps = new ArrayDeque<>();

        synchronized SkipReason tryReserve(long now, ChatType type, TelegramProperties.RateLimit config) {
            int capacity;
            long windowMs;
            long minIntervalMs;
            if (type == ChatType.PRIVATE) {
                capacity = config.getPrivateChatPerSecond();
                windowMs = PRIVATE_WINDOW_MS;
                minIntervalMs = 0L;
            } else {
                capacity = config.getGroupChatPerMinute();
                windowMs = GROUP_WINDOW_MS;
                minIntervalMs = config.getGroupChatMinEditIntervalMs();
            }
            cleanup(timestamps, now - windowMs);
            if (timestamps.size() >= capacity) {
                return SkipReason.CHAT_CAPACITY;
            }
            if (minIntervalMs > 0L && !timestamps.isEmpty()) {
                long last = timestamps.peekLast();
                if (now - last < minIntervalMs) {
                    return SkipReason.CHAT_MIN_INTERVAL;
                }
            }
            timestamps.addLast(now);
            return null;
        }

        synchronized void rollback(long ts) {
            // Best-effort: pop our reservation if it is still the latest.
            // A racing thread may have inserted afterwards — in that case
            // the slot stays consumed until the window slides; the next
            // legitimate acquire may falsely reject once. This is benign.
            if (!timestamps.isEmpty() && timestamps.peekLast() == ts) {
                timestamps.pollLast();
            }
        }
    }
}
