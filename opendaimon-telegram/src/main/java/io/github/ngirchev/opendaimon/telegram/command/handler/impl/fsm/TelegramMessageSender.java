package io.github.ngirchev.opendaimon.telegram.command.handler.impl.fsm;

import io.github.ngirchev.opendaimon.common.model.ConversationThread;
import io.github.ngirchev.opendaimon.common.service.MessageLocalizationService;
import io.github.ngirchev.opendaimon.telegram.TelegramBot;
import io.github.ngirchev.opendaimon.telegram.config.TelegramProperties;
import io.github.ngirchev.opendaimon.telegram.service.PersistentKeyboardService;
import io.github.ngirchev.opendaimon.telegram.service.TelegramChatRateLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.telegram.telegrambots.meta.api.objects.ResponseParameters;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sends messages to Telegram users on behalf of FSM actions.
 *
 * <p>Every outgoing call goes through {@link TelegramChatRateLimiter} which
 * enforces chat-scoped + global Telegram quotas. Best-effort calls
 * ({@link #editHtml}, {@link #deleteMessage}) use {@code tryAcquire} and
 * silently skip on rejection. Critical calls ({@link #sendNotification},
 * {@link #sendTextWithKeyboard}, {@link #sendHtmlAndGetId}) block on
 * {@code acquire} up to a configured timeout.
 *
 * <p>{@link #editHtmlReliable} and {@link #sendHtmlReliableAndGetId} add
 * a 429-aware single retry on top of the limiter — used only for terminal
 * delivery of the agent's final answer (called from sync FSM callbacks,
 * NOT from a Reactor pipeline).
 */
@Slf4j
@RequiredArgsConstructor
public class TelegramMessageSender {

    private static final Pattern RETRY_AFTER_PATTERN = Pattern.compile("retry after (\\d+)");
    private static final int HTTP_TOO_MANY_REQUESTS = 429;

    private final ObjectProvider<TelegramBot> telegramBotProvider;
    private final MessageLocalizationService messageLocalizationService;
    private final PersistentKeyboardService persistentKeyboardService;
    private final TelegramProperties telegramProperties;
    private final TelegramChatRateLimiter rateLimiter;

    /**
     * Send a localized notification to the user (e.g., guardrail warning).
     */
    public void sendNotification(Long chatId, String messageKey, String languageCode, Object... args) {
        String text = messageLocalizationService.getMessage(messageKey, languageCode, args);
        sendHtml(chatId, text, null);
    }

    /**
     * Send HTML text with a persistent keyboard attached.
     */
    public void sendTextWithKeyboard(Long chatId, String htmlText, Integer replyToMessageId,
                                      Long userId, ConversationThread thread) {
        ReplyKeyboardMarkup keyboard = persistentKeyboardService.buildKeyboardMarkup(userId, thread);
        sendHtml(chatId, htmlText, replyToMessageId, keyboard);
    }

    /**
     * Send an HTML-formatted message and return the Telegram message ID.
     *
     * @return message ID, or {@code null} if bot is unavailable, the rate
     *         limiter denied a slot within the new-bubble timeout, or send fails
     */
    public Integer sendHtmlAndGetId(Long chatId, String htmlText, Integer replyToMessageId) {
        return sendHtmlAndGetId(chatId, htmlText, replyToMessageId, false);
    }

    /**
     * Send an HTML-formatted message and return the Telegram message ID.
     * Allows controlling Telegram link previews.
     */
    public Integer sendHtmlAndGetId(Long chatId, String htmlText, Integer replyToMessageId,
                                     boolean disableWebPagePreview) {
        TelegramBot bot = telegramBotProvider.getIfAvailable();
        if (bot == null) {
            log.warn("TelegramBot not available, cannot send message to chatId={}", chatId);
            return null;
        }
        if (!acquireQuietly(chatId, telegramProperties.getRateLimit().getNewBubbleAcquireTimeoutMs())) {
            log.warn("Rate limiter denied sendMessage slot for chatId={} within new-bubble timeout", chatId);
            return null;
        }
        try {
            return bot.sendMessageAndGetId(chatId, htmlText, replyToMessageId, disableWebPagePreview);
        } catch (TelegramApiException e) {
            logSendFailure(chatId, e);
            return null;
        }
    }

    /**
     * Edit an existing message's text (HTML mode).
     */
    public void editHtml(Long chatId, Integer messageId, String htmlText) {
        editHtml(chatId, messageId, htmlText, false);
    }

    /**
     * Edit an existing message's text (HTML mode).
     * Allows controlling Telegram link previews.
     *
     * <p>Best-effort: skipped silently when the rate limiter denies a slot.
     */
    public void editHtml(Long chatId, Integer messageId, String htmlText,
                          boolean disableWebPagePreview) {
        TelegramBot bot = telegramBotProvider.getIfAvailable();
        if (bot == null) {
            log.warn("TelegramBot not available, cannot edit message in chatId={}", chatId);
            return;
        }
        if (!rateLimiter.tryAcquire(chatId)) {
            log.debug("Rate limiter denied edit slot for chatId={} (best-effort skip)", chatId);
            return;
        }
        try {
            bot.editMessageHtml(chatId, messageId, htmlText, disableWebPagePreview);
        } catch (TelegramApiException e) {
            logEditFailure(chatId, messageId, e);
        }
    }

    /**
     * Delete a message in a chat. Returns {@code true} on success, {@code false}
     * when the bot is unavailable, the rate limiter denied a slot, or Telegram
     * refused the request.
     */
    public boolean deleteMessage(Long chatId, Integer messageId) {
        if (messageId == null) {
            return false;
        }
        TelegramBot bot = telegramBotProvider.getIfAvailable();
        if (bot == null) {
            log.warn("TelegramBot not available, cannot delete message in chatId={}", chatId);
            return false;
        }
        if (!rateLimiter.tryAcquire(chatId)) {
            log.debug("Rate limiter denied delete slot for chatId={} (best-effort skip)", chatId);
            return false;
        }
        try {
            bot.deleteMessage(chatId, messageId);
            return true;
        } catch (TelegramApiException e) {
            log.debug("Failed to delete message {} in chatId={}: {}", messageId, chatId, e.getMessage());
            return false;
        }
    }

    /**
     * Send an HTML-formatted message.
     */
    public void sendHtml(Long chatId, String htmlText, Integer replyToMessageId) {
        sendHtml(chatId, htmlText, replyToMessageId, null);
    }

    private void sendHtml(Long chatId, String htmlText, Integer replyToMessageId,
                           ReplyKeyboardMarkup keyboard) {
        TelegramBot bot = telegramBotProvider.getIfAvailable();
        if (bot == null) {
            log.warn("TelegramBot not available, cannot send message to chatId={}", chatId);
            return;
        }
        if (!acquireQuietly(chatId, telegramProperties.getRateLimit().getDefaultAcquireTimeoutMs())) {
            log.warn("Rate limiter denied sendMessage slot for chatId={} within default timeout", chatId);
            return;
        }
        try {
            bot.sendMessage(chatId, htmlText, replyToMessageId, keyboard);
        } catch (TelegramApiException e) {
            logSendFailure(chatId, e);
        }
    }

    /**
     * Acquire a slot (blocking up to {@code maxWaitMs}), edit the message;
     * on a 429 reply parse {@code retry_after} and retry once if the wait
     * fits within the remaining budget. Returns {@code true} on success.
     *
     * <p>Blocks the calling thread via {@code Thread.sleep}. Call only from
     * sync FSM callbacks (NOT from a Reactor pipeline).
     */
    public boolean editHtmlReliable(Long chatId, Integer messageId, String htmlText,
                                     boolean disableWebPagePreview, long maxWaitMs) {
        TelegramBot bot = telegramBotProvider.getIfAvailable();
        if (bot == null) {
            log.warn("TelegramBot not available, cannot edit message in chatId={}", chatId);
            return false;
        }
        long startedAt = System.currentTimeMillis();
        return acquireAndExecuteWithRetry(chatId, maxWaitMs, startedAt, () -> {
            bot.editMessageHtml(chatId, messageId, htmlText, disableWebPagePreview);
            return Boolean.TRUE;
        }) != null;
    }

    /**
     * Acquire a slot (blocking up to {@code maxWaitMs}), send a fresh message;
     * on a 429 reply parse {@code retry_after} and retry once if the wait
     * fits within the remaining budget. Returns the new message id on
     * success, {@code null} otherwise.
     */
    public Integer sendHtmlReliableAndGetId(Long chatId, String htmlText, Integer replyToMessageId,
                                             boolean disableWebPagePreview, long maxWaitMs) {
        TelegramBot bot = telegramBotProvider.getIfAvailable();
        if (bot == null) {
            log.warn("TelegramBot not available, cannot send message to chatId={}", chatId);
            return null;
        }
        long startedAt = System.currentTimeMillis();
        return acquireAndExecuteWithRetry(chatId, maxWaitMs, startedAt,
                () -> bot.sendMessageAndGetId(chatId, htmlText, replyToMessageId, disableWebPagePreview));
    }

    private <T> T acquireAndExecuteWithRetry(long chatId, long maxWaitMs, long startedAt,
                                              TelegramCall<T> call) {
        long remaining = maxWaitMs - (System.currentTimeMillis() - startedAt);
        if (!acquireQuietly(chatId, Math.max(0, remaining))) {
            log.warn("Rate limiter denied reliable slot for chatId={} within {} ms", chatId, maxWaitMs);
            return null;
        }
        try {
            return call.execute();
        } catch (TelegramApiException e) {
            int retryAfterSec = parseRetryAfterSeconds(e);
            if (retryAfterSec <= 0) {
                logEditFailure(chatId, null, e);
                return null;
            }
            long elapsed = System.currentTimeMillis() - startedAt;
            long retryAfterMs = retryAfterSec * 1000L;
            if (elapsed + retryAfterMs > maxWaitMs) {
                log.warn("Telegram 429 retry_after={}s for chatId={} exceeds remaining budget ({} ms left of {} ms) — giving up",
                        retryAfterSec, chatId, maxWaitMs - elapsed, maxWaitMs);
                return null;
            }
            log.warn("Telegram 429 for chatId={} — sleeping {} ms then retrying once", chatId, retryAfterMs);
            try {
                Thread.sleep(retryAfterMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return null;
            }
            // Re-acquire (window has likely cleared) within the remaining budget.
            long remainingAfterSleep = maxWaitMs - (System.currentTimeMillis() - startedAt);
            if (!acquireQuietly(chatId, Math.max(0, remainingAfterSleep))) {
                log.warn("Rate limiter denied retry slot for chatId={} after 429 sleep", chatId);
                return null;
            }
            try {
                return call.execute();
            } catch (TelegramApiException e2) {
                logEditFailure(chatId, null, e2);
                return null;
            }
        }
    }

    private boolean acquireQuietly(long chatId, long timeoutMs) {
        try {
            return rateLimiter.acquire(chatId, timeoutMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Extracts {@code retry_after} (seconds) from a Telegram 429 reply.
     * Falls back to regex parsing of the message string when
     * {@link ResponseParameters} are unset (older error envelopes from the
     * library — observed in production, see {@code "[429] Too Many Requests:
     * retry after 40"}).
     *
     * @return retry-after in seconds, or {@code 0} when not parsed (or not a 429)
     */
    static int parseRetryAfterSeconds(TelegramApiException e) {
        if (e instanceof TelegramApiRequestException req) {
            Integer code = req.getErrorCode();
            if (code == null || code != HTTP_TOO_MANY_REQUESTS) {
                return 0;
            }
            ResponseParameters params = req.getParameters();
            if (params != null && params.getRetryAfter() != null) {
                return params.getRetryAfter();
            }
        }
        String msg = e.getMessage();
        if (msg != null) {
            Matcher m = RETRY_AFTER_PATTERN.matcher(msg);
            if (m.find()) {
                return Integer.parseInt(m.group(1));
            }
        }
        return 0;
    }

    private static void logSendFailure(Long chatId, TelegramApiException e) {
        int retryAfter = parseRetryAfterSeconds(e);
        if (retryAfter > 0) {
            log.warn("Telegram 429 on sendMessage to chatId={}, retry_after={}s", chatId, retryAfter);
        } else {
            log.error("Failed to send message to chatId={}: {}", chatId, e.getMessage());
        }
    }

    private static void logEditFailure(Long chatId, Integer messageId, TelegramApiException e) {
        int retryAfter = parseRetryAfterSeconds(e);
        if (retryAfter > 0) {
            log.warn("Telegram 429 on edit/send for chatId={} messageId={}, retry_after={}s",
                    chatId, messageId, retryAfter);
        } else {
            log.error("Failed to edit/send message {} in chatId={}: {}", messageId, chatId, e.getMessage());
        }
    }

    @FunctionalInterface
    private interface TelegramCall<T> {
        T execute() throws TelegramApiException;
    }
}
