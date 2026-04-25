package io.github.ngirchev.opendaimon.telegram.command.handler.impl.fsm;

import io.github.ngirchev.opendaimon.common.service.MessageLocalizationService;
import io.github.ngirchev.opendaimon.telegram.TelegramBot;
import io.github.ngirchev.opendaimon.telegram.config.TelegramProperties;
import io.github.ngirchev.opendaimon.telegram.service.PersistentKeyboardService;
import io.github.ngirchev.opendaimon.telegram.service.TelegramChatRateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.telegram.telegrambots.meta.api.objects.ResponseParameters;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TelegramMessageSender}.
 *
 * <p>Covers limiter integration (best-effort skip on tryAcquire-false, blocking acquire
 * on critical paths), the retry-on-429 logic in {@code editHtmlReliable} /
 * {@code sendHtmlReliableAndGetId}, and the static {@code parseRetryAfterSeconds}
 * helper which falls back to message-string parsing when {@link ResponseParameters}
 * are not populated by the library.
 */
@ExtendWith(MockitoExtension.class)
class TelegramMessageSenderTest {

    private static final long CHAT_ID = -1001234567890L;
    private static final int MESSAGE_ID = 42;
    private static final String HTML = "<b>hello</b>";

    @Mock
    private TelegramBot telegramBot;
    @Mock
    private MessageLocalizationService messageLocalizationService;
    @Mock
    private PersistentKeyboardService persistentKeyboardService;
    @Mock
    private TelegramChatRateLimiter rateLimiter;

    private TelegramMessageSender sender;

    @BeforeEach
    void setUp() {
        @SuppressWarnings("unchecked")
        ObjectProvider<TelegramBot> botProvider = mock(ObjectProvider.class);
        // Lenient: parser-only tests below do not exercise the bot provider.
        lenient().when(botProvider.getIfAvailable()).thenReturn(telegramBot);

        TelegramProperties props = new TelegramProperties();
        props.setToken("t");
        props.setUsername("u");
        props.setMaxMessageLength(4096);
        props.setAgentStreamEditMinIntervalMs(0);
        TelegramProperties.RateLimit rl = new TelegramProperties.RateLimit();
        rl.setPrivateChatPerSecond(1);
        rl.setGroupChatPerMinute(20);
        rl.setGroupChatMinEditIntervalMs(3500);
        rl.setGlobalPerSecond(30);
        rl.setNewBubbleAcquireTimeoutMs(0);
        rl.setDefaultAcquireTimeoutMs(0);
        rl.setFinalEditMaxWaitMs(5000);
        props.setRateLimit(rl);

        sender = new TelegramMessageSender(botProvider, messageLocalizationService,
                persistentKeyboardService, props, rateLimiter);
    }

    @Test
    void shouldSkipEditWhenLimiterRejects() throws Exception {
        when(rateLimiter.tryAcquire(CHAT_ID)).thenReturn(false);

        sender.editHtml(CHAT_ID, MESSAGE_ID, HTML);

        verify(telegramBot, never()).editMessageHtml(anyLong(), anyInt(), anyString(), anyBoolean());
    }

    @Test
    void shouldEditWhenLimiterAllows() throws Exception {
        when(rateLimiter.tryAcquire(CHAT_ID)).thenReturn(true);

        sender.editHtml(CHAT_ID, MESSAGE_ID, HTML);

        verify(telegramBot, times(1)).editMessageHtml(eq(CHAT_ID), eq(MESSAGE_ID), eq(HTML), eq(false));
    }

    @Test
    void shouldRetryEditOnceAfter429AndReturnTrueWhenSecondAttemptSucceeds() throws Exception {
        // First acquire OK, second acquire OK after sleep — returns true after retry.
        when(rateLimiter.acquire(eq(CHAT_ID), anyLong())).thenReturn(true);
        // First edit raises 429 with retry_after=1; second edit succeeds (void return — no throw).
        TelegramApiRequestException e429 = build429(1);
        doThrow(e429).doNothing()
                .when(telegramBot).editMessageHtml(eq(CHAT_ID), eq(MESSAGE_ID), eq(HTML), eq(false));

        long startedAt = System.currentTimeMillis();
        boolean result = sender.editHtmlReliable(CHAT_ID, MESSAGE_ID, HTML, false, 5000);
        long elapsed = System.currentTimeMillis() - startedAt;

        assertThat(result).isTrue();
        assertThat(elapsed).as("Thread.sleep(1000) must have been observed").isGreaterThanOrEqualTo(900L);
        verify(telegramBot, times(2)).editMessageHtml(eq(CHAT_ID), eq(MESSAGE_ID), eq(HTML), eq(false));
    }

    @Test
    void shouldGiveUpAndReturnFalseWhen429RetryAfterExceedsMaxWait() throws Exception {
        when(rateLimiter.acquire(eq(CHAT_ID), anyLong())).thenReturn(true);
        TelegramApiRequestException e429 = build429(40);
        doThrow(e429).when(telegramBot).editMessageHtml(eq(CHAT_ID), eq(MESSAGE_ID), eq(HTML), eq(false));

        long startedAt = System.currentTimeMillis();
        boolean result = sender.editHtmlReliable(CHAT_ID, MESSAGE_ID, HTML, false, 5000);
        long elapsed = System.currentTimeMillis() - startedAt;

        assertThat(result).isFalse();
        // Single attempt — must not have slept on the 40-sec retry_after.
        assertThat(elapsed).as("must give up without sleeping").isLessThan(500L);
        verify(telegramBot, times(1)).editMessageHtml(eq(CHAT_ID), eq(MESSAGE_ID), eq(HTML), eq(false));
    }

    @Test
    void shouldParseRetryAfterFromMessageWhenParametersAreNull() {
        // Production reproducer: TelegramApiRequestException("[429] Too Many Requests: retry after 40")
        // with errorCode=429 and parameters==null → must still extract 40 from the message string.
        TelegramApiRequestException e = new TelegramApiRequestException(
                "[429] Too Many Requests: retry after 40");
        // Force errorCode to 429 — the simple-message ctor does not set it.
        try {
            java.lang.reflect.Field f = e.getClass().getDeclaredField("errorCode");
            f.setAccessible(true);
            f.set(e, 429);
        } catch (ReflectiveOperationException reflective) {
            throw new AssertionError("test setup failed", reflective);
        }

        int retryAfter = TelegramMessageSender.parseRetryAfterSeconds(e);

        assertThat(retryAfter).isEqualTo(40);
    }

    @Test
    void shouldReturnZeroFromParseWhenNotARateLimitError() {
        TelegramApiException e = new TelegramApiException("network unreachable");

        int retryAfter = TelegramMessageSender.parseRetryAfterSeconds(e);

        assertThat(retryAfter).isZero();
    }

    @Test
    void shouldReturnNullFromSendWhenLimiterTimesOut() throws Exception {
        when(rateLimiter.acquire(eq(CHAT_ID), anyLong())).thenReturn(false);

        Integer result = sender.sendHtmlAndGetId(CHAT_ID, HTML, null);

        assertThat(result).isNull();
        verify(telegramBot, never()).sendMessageAndGetId(anyLong(), anyString(), any(), anyBoolean());
    }

    private static TelegramApiRequestException build429(int retryAfterSec) {
        ResponseParameters params = new ResponseParameters();
        params.setRetryAfter(retryAfterSec);
        TelegramApiRequestException e = new TelegramApiRequestException(
                "[429] Too Many Requests: retry after " + retryAfterSec);
        // Populate fields the library normally fills from the parsed JSON envelope.
        try {
            java.lang.reflect.Field codeField = e.getClass().getDeclaredField("errorCode");
            codeField.setAccessible(true);
            codeField.set(e, 429);
            java.lang.reflect.Field paramsField = e.getClass().getDeclaredField("parameters");
            paramsField.setAccessible(true);
            paramsField.set(e, params);
        } catch (ReflectiveOperationException reflective) {
            throw new AssertionError("test setup failed", reflective);
        }
        return e;
    }
}
