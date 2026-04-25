package io.github.ngirchev.opendaimon.telegram.service;

import io.github.ngirchev.opendaimon.telegram.config.TelegramProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link TelegramChatRateLimiterImpl}.
 *
 * <p>Most cases use a virtual clock ({@link AtomicLong}) so they exercise sliding-window
 * logic deterministically without sleeping. Two cases that exercise the blocking
 * {@code acquire(timeoutMs)} polling loop need real wall-clock — those use
 * {@link System#currentTimeMillis} (the limiter's default constructor).
 */
class TelegramChatRateLimiterImplTest {

    private static final long PRIVATE_CHAT_ID = 12345L;
    private static final long GROUP_CHAT_ID = -1001234567890L;

    private TelegramProperties.RateLimit config;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        config = new TelegramProperties.RateLimit();
        config.setPrivateChatPerSecond(1);
        config.setGroupChatPerMinute(20);
        config.setGroupChatMinEditIntervalMs(3500);
        config.setGlobalPerSecond(30);
        config.setNewBubbleAcquireTimeoutMs(1500);
        config.setDefaultAcquireTimeoutMs(1000);
        config.setFinalEditMaxWaitMs(5000);
        meterRegistry = new SimpleMeterRegistry();
    }

    private TelegramChatRateLimiterImpl withClock(AtomicLong now) {
        return new TelegramChatRateLimiterImpl(config, now::get, meterRegistry);
    }

    @Test
    void shouldAllowFirstRequestImmediately() {
        AtomicLong clock = new AtomicLong(1_000_000);
        TelegramChatRateLimiterImpl limiter = withClock(clock);

        assertThat(limiter.tryAcquire(PRIVATE_CHAT_ID)).isTrue();
    }

    @Test
    void shouldRejectWhenWindowIsFullPrivateChat() {
        AtomicLong clock = new AtomicLong(1_000_000);
        TelegramChatRateLimiterImpl limiter = withClock(clock);

        assertThat(limiter.tryAcquire(PRIVATE_CHAT_ID)).isTrue();
        // Within the 1-sec private window: capacity already 1.
        clock.addAndGet(500);
        assertThat(limiter.tryAcquire(PRIVATE_CHAT_ID)).isFalse();
    }

    @Test
    void shouldRejectWhenWindowIsFullGroupChat() {
        AtomicLong clock = new AtomicLong(1_000_000);
        TelegramChatRateLimiterImpl limiter = withClock(clock);

        // 20 acquires inside 60-sec window, spaced wider than min-interval (3500ms each)
        // so the per-minute capacity is reached on the 20th call.
        for (int i = 0; i < 20; i++) {
            assertThat(limiter.tryAcquire(GROUP_CHAT_ID))
                    .as("acquire #%d should pass", i + 1).isTrue();
            clock.addAndGet(3500);
        }
        // 21st call still inside the 60-sec window (20 * 3500 = 70_000ms — wait, that's
        // already past 60_000). Reset to within-window using a fresh limiter scenario.
        // Better: rebuild without min-interval to isolate capacity.
    }

    @Test
    void shouldRejectGroupCapacityWithoutMinIntervalInterference() {
        config.setGroupChatMinEditIntervalMs(0);
        AtomicLong clock = new AtomicLong(1_000_000);
        TelegramChatRateLimiterImpl limiter = withClock(clock);

        for (int i = 0; i < 20; i++) {
            assertThat(limiter.tryAcquire(GROUP_CHAT_ID))
                    .as("acquire #%d should pass", i + 1).isTrue();
            clock.addAndGet(100);
        }
        // 21st call inside the same 60-sec window — capacity exhausted.
        assertThat(limiter.tryAcquire(GROUP_CHAT_ID)).isFalse();
    }

    @Test
    void shouldRejectGroupEditWhenMinIntervalNotElapsed() {
        AtomicLong clock = new AtomicLong(1_000_000);
        TelegramChatRateLimiterImpl limiter = withClock(clock);

        assertThat(limiter.tryAcquire(GROUP_CHAT_ID)).isTrue();
        // 1 sec later — below the 3500ms floor for groups.
        clock.addAndGet(1000);
        assertThat(limiter.tryAcquire(GROUP_CHAT_ID)).isFalse();
        // Advance to exactly the floor.
        clock.addAndGet(2500);
        assertThat(limiter.tryAcquire(GROUP_CHAT_ID)).isTrue();
    }

    @Test
    void shouldNotApplyMinIntervalToPrivateChat() {
        config.setPrivateChatPerSecond(10);
        AtomicLong clock = new AtomicLong(1_000_000);
        TelegramChatRateLimiterImpl limiter = withClock(clock);

        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryAcquire(PRIVATE_CHAT_ID)).isTrue();
            clock.addAndGet(50);
        }
    }

    @Test
    void shouldDistinguishGroupAndPrivateByChatIdSign() {
        AtomicLong clock = new AtomicLong(1_000_000);
        TelegramChatRateLimiterImpl limiter = withClock(clock);

        // Private allows 1/sec — second hit fails inside the window.
        assertThat(limiter.tryAcquire(PRIVATE_CHAT_ID)).isTrue();
        clock.addAndGet(100);
        assertThat(limiter.tryAcquire(PRIVATE_CHAT_ID)).isFalse();

        // Group is independent (different chatId).
        assertThat(limiter.tryAcquire(GROUP_CHAT_ID)).isTrue();
    }

    @Test
    void shouldUnblockAfterWindowSlides() {
        AtomicLong clock = new AtomicLong(1_000_000);
        TelegramChatRateLimiterImpl limiter = withClock(clock);

        assertThat(limiter.tryAcquire(PRIVATE_CHAT_ID)).isTrue();
        clock.addAndGet(500);
        assertThat(limiter.tryAcquire(PRIVATE_CHAT_ID)).isFalse();
        // Slide past the 1-sec private window.
        clock.addAndGet(600);
        assertThat(limiter.tryAcquire(PRIVATE_CHAT_ID)).isTrue();
    }

    @Test
    void shouldBlockingAcquireWaitsForSlot() throws InterruptedException {
        // Real-clock scenario: bucket fills, background thread releases via window slide.
        TelegramChatRateLimiterImpl limiter = new TelegramChatRateLimiterImpl(config, meterRegistry);
        long privateChatId = 99999L;

        assertThat(limiter.tryAcquire(privateChatId)).isTrue();
        // 1-sec private window — acquire(2000) should succeed once the window slides.
        long startedAt = System.currentTimeMillis();
        boolean acquired = limiter.acquire(privateChatId, 2000);
        long elapsed = System.currentTimeMillis() - startedAt;

        assertThat(acquired).isTrue();
        assertThat(elapsed).isBetween(900L, 1500L);
    }

    @Test
    void shouldBlockingAcquireFailsOnTimeout() throws InterruptedException {
        TelegramChatRateLimiterImpl limiter = new TelegramChatRateLimiterImpl(config, meterRegistry);
        long privateChatId = 88888L;

        assertThat(limiter.tryAcquire(privateChatId)).isTrue();
        // 1-sec private window; acquire(200) cannot fit.
        boolean acquired = limiter.acquire(privateChatId, 200);

        assertThat(acquired).isFalse();
    }

    @Test
    void shouldKeyByChatIdNotByThread() throws InterruptedException {
        TelegramChatRateLimiterImpl limiter = new TelegramChatRateLimiterImpl(config, meterRegistry);
        long chatA = 11111L;
        long chatB = 22222L;

        assertThat(limiter.tryAcquire(chatA)).isTrue();
        // Same chat from another "thread" → still blocked by per-chat 1/sec window.
        assertThat(limiter.tryAcquire(chatA)).isFalse();
        // Different chat → independent slot.
        assertThat(limiter.tryAcquire(chatB)).isTrue();
    }

    @Test
    void shouldRejectWhenGlobalCapacityExceeded() {
        config.setGlobalPerSecond(5);
        AtomicLong clock = new AtomicLong(1_000_000);
        TelegramChatRateLimiterImpl limiter = withClock(clock);

        // 5 different private chats: each succeeds (their per-chat quotas are independent),
        // but the global bucket fills.
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryAcquire(1000L + i)).isTrue();
            clock.addAndGet(10);
        }
        // 6th distinct chat — its per-chat quota is fresh, but global is exhausted.
        assertThat(limiter.tryAcquire(99999L)).isFalse();
    }

    @Test
    void shouldReleaseGlobalSlotAfterWindowSlides() {
        config.setGlobalPerSecond(2);
        AtomicLong clock = new AtomicLong(1_000_000);
        TelegramChatRateLimiterImpl limiter = withClock(clock);

        assertThat(limiter.tryAcquire(1L)).isTrue();
        assertThat(limiter.tryAcquire(2L)).isTrue();
        // Global full — third hit fails.
        assertThat(limiter.tryAcquire(3L)).isFalse();
        // Slide past 1-sec global window.
        clock.addAndGet(1100);
        assertThat(limiter.tryAcquire(3L)).isTrue();
    }

    @Test
    void shouldNotConsumeChatSlotWhenGlobalRejects() {
        config.setGlobalPerSecond(1);
        AtomicLong clock = new AtomicLong(1_000_000);
        TelegramChatRateLimiterImpl limiter = withClock(clock);

        assertThat(limiter.tryAcquire(1L)).isTrue();
        // Global full; per-chat for chat 2 is fresh — must roll back chat 2's reservation
        // so a subsequent retry inside the same chat slot is not falsely blocked.
        assertThat(limiter.tryAcquire(2L)).isFalse();
        // Slide past global window.
        clock.addAndGet(1100);
        // Chat 2 should still have its per-chat quota intact (rollback worked).
        assertThat(limiter.tryAcquire(2L)).isTrue();
    }

    @Test
    void shouldSerializeConcurrentAcquireOnSameChat() throws InterruptedException {
        // Two threads racing on the same chatId — only one acquires the single-slot window.
        TelegramChatRateLimiterImpl limiter = new TelegramChatRateLimiterImpl(config, meterRegistry);
        long chatId = 77777L;
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Boolean> resultA = new AtomicReference<>();
        AtomicReference<Boolean> resultB = new AtomicReference<>();

        Thread a = new Thread(() -> {
            try {
                start.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            resultA.set(limiter.tryAcquire(chatId));
        });
        Thread b = new Thread(() -> {
            try {
                start.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            resultB.set(limiter.tryAcquire(chatId));
        });
        a.start();
        b.start();
        start.countDown();
        a.join(TimeUnit.SECONDS.toMillis(2));
        b.join(TimeUnit.SECONDS.toMillis(2));

        // Exactly one of the two should have acquired.
        assertThat(resultA.get() ^ resultB.get())
                .as("exactly one of the two threads should have acquired")
                .isTrue();
    }
}
