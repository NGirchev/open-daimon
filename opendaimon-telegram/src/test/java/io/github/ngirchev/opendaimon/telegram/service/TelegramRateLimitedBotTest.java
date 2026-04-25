package io.github.ngirchev.opendaimon.telegram.service;

import io.github.ngirchev.opendaimon.telegram.TelegramBot;
import io.github.ngirchev.opendaimon.telegram.config.TelegramProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the contract of {@link TelegramRateLimitedBot}: per-chat and global quotas
 * are honoured by blocking the caller, never by silently dropping or exceeding a slot.
 *
 * <p>Uses a virtual clock + virtual sleeper so the tests are deterministic and run in
 * milliseconds even when the asserted gap is "1 second" or "3 seconds". The virtual
 * sleeper just advances the clock by the requested amount; the rate limiter never
 * learns it isn't on a real wall clock.
 */
class TelegramRateLimitedBotTest {

    private static final long PRIVATE_CHAT = 42L;
    private static final long GROUP_CHAT = -10042L;

    private TelegramBot bot;
    private ObjectProvider<TelegramBot> botProvider;
    private TelegramProperties.RateLimit config;
    private AtomicLong virtualClock;
    private List<Long> sleepHistory;
    private TelegramRateLimitedBot rate;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        bot = mock(TelegramBot.class);
        botProvider = mock(ObjectProvider.class);
        when(botProvider.getIfAvailable()).thenReturn(bot);
        when(bot.sendMessageAndGetId(anyLong(), any(), any(), anyBoolean()))
                .thenReturn(1001, 1002, 1003, 1004, 1005);

        config = new TelegramProperties.RateLimit();
        config.setPrivateChatPerSecond(1);   // 1 op / sec
        config.setGroupChatPerMinute(20);    // 1 op / 3 sec
        config.setGlobalPerSecond(30);
        config.setMaxAcquireWaitMs(60_000);

        virtualClock = new AtomicLong(0);
        sleepHistory = new ArrayList<>();

        rate = new TelegramRateLimitedBot(
                botProvider, config, virtualClock::get,
                ms -> {
                    sleepHistory.add(ms);
                    virtualClock.addAndGet(ms);
                });
    }

    @Test
    @DisplayName("private chat: two consecutive sends are spaced by ≥ 1 second (no 429 surface)")
    void privateChatTwoSendsAreSpacedByOneSecond() {
        rate.sendMessage(PRIVATE_CHAT, "first", null, true);
        long afterFirst = virtualClock.get();

        rate.sendMessage(PRIVATE_CHAT, "second", null, true);
        long afterSecond = virtualClock.get();

        assertThat(afterSecond - afterFirst).isGreaterThanOrEqualTo(1000);
    }

    @Test
    @DisplayName("group chat: two consecutive sends are spaced by ≥ 3 seconds (60s/20 = 3s)")
    void groupChatTwoSendsAreSpacedByThreeSeconds() {
        rate.sendMessage(GROUP_CHAT, "first", null, true);
        long afterFirst = virtualClock.get();

        rate.sendMessage(GROUP_CHAT, "second", null, true);
        long afterSecond = virtualClock.get();

        assertThat(afterSecond - afterFirst).isGreaterThanOrEqualTo(3000);
    }

    @Test
    @DisplayName("global cap: 30 ops in one second across many chats — 31st has to wait")
    void globalCapForcesWaitWhenSaturated() {
        for (int i = 0; i < 30; i++) {
            rate.sendMessage(1_000_000L + i, "spam " + i, null, true);
        }
        long afterThirty = virtualClock.get();

        rate.sendMessage(2_000_000L, "thirty-first", null, true);
        long afterThirtyFirst = virtualClock.get();

        // The 31st op must wait until the global window slides — at least until
        // (oldest + 1000 ms - now). With everything happening at virtual t=0,
        // this is exactly ~1000 ms.
        assertThat(afterThirtyFirst - afterThirty).isGreaterThanOrEqualTo(1000);
    }

    @Test
    @DisplayName("different chats run independently per-chat (only the global cap can serialize them)")
    void differentChatsAreIndependentUnderGlobalCap() {
        rate.sendMessage(100L, "a", null, true);
        long afterChat100 = virtualClock.get();

        // A different private chat should not have to wait the per-chat 1-sec window —
        // it has its own lane. Only the global cap can throttle it, and at this volume
        // (2 ops total) the global cap is far from saturated.
        rate.sendMessage(200L, "b", null, true);
        long afterChat200 = virtualClock.get();

        assertThat(afterChat200 - afterChat100).isLessThan(50);
    }

    @Test
    @DisplayName("each call really hits bot exactly once — no silent drops")
    void everySendCallReachesTelegramExactlyOnce() throws Exception {
        rate.sendMessage(PRIVATE_CHAT, "one", null, true);
        rate.sendMessage(PRIVATE_CHAT, "two", null, true);
        rate.sendMessage(PRIVATE_CHAT, "three", null, true);

        verify(bot, times(1)).sendMessageAndGetId(eq(PRIVATE_CHAT), eq("one"), any(), anyBoolean());
        verify(bot, times(1)).sendMessageAndGetId(eq(PRIVATE_CHAT), eq("two"), any(), anyBoolean());
        verify(bot, times(1)).sendMessageAndGetId(eq(PRIVATE_CHAT), eq("three"), any(), anyBoolean());
    }

    @Test
    @DisplayName("editMessage and deleteMessage also block on per-chat quota")
    void editAndDeleteRespectQuota() throws Exception {
        rate.sendMessage(PRIVATE_CHAT, "init", null, true);
        long afterSend = virtualClock.get();

        rate.editMessage(PRIVATE_CHAT, 1001, "edited", true);
        long afterEdit = virtualClock.get();
        assertThat(afterEdit - afterSend).isGreaterThanOrEqualTo(1000);

        rate.deleteMessage(PRIVATE_CHAT, 1001);
        long afterDelete = virtualClock.get();
        assertThat(afterDelete - afterEdit).isGreaterThanOrEqualTo(1000);

        verify(bot).editMessageHtml(eq(PRIVATE_CHAT), eq(1001), eq("edited"), eq(true));
        verify(bot).deleteMessage(eq(PRIVATE_CHAT), eq(1001));
    }

    @Test
    @DisplayName("twenty group-chat sends fit in exactly one minute (1 op every 3s, no 429)")
    void groupChatTwentyMessagesFitInOneMinute() {
        long start = virtualClock.get();
        for (int i = 0; i < 20; i++) {
            rate.sendMessage(GROUP_CHAT, "chunk " + i, null, true);
        }
        long elapsed = virtualClock.get() - start;

        // Group quota: 20/min = 1 op every 3 sec. First op fires at t=0, last (20th)
        // fires at t = 19 * 3000 = 57 000 ms. We claim the bound 56 000..60 000 to
        // tolerate the off-by-one between intervals and the floor on the final wait.
        assertThat(elapsed).isLessThanOrEqualTo(60_000);
        assertThat(elapsed).isGreaterThanOrEqualTo(57_000);
    }

    @Test
    @DisplayName("when bot is null, send/edit/delete return null/false without throwing")
    void unavailableBotIsHandledGracefully() {
        when(botProvider.getIfAvailable()).thenReturn(null);

        Integer sendId = rate.sendMessage(PRIVATE_CHAT, "x", null, true);
        boolean edited = rate.editMessage(PRIVATE_CHAT, 1, "x", true);
        boolean deleted = rate.deleteMessage(PRIVATE_CHAT, 1);

        assertThat(sendId).isNull();
        assertThat(edited).isFalse();
        assertThat(deleted).isFalse();
    }
}
