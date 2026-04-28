package io.github.ngirchev.opendaimon.telegram.command.handler.impl.fsm;

import io.github.ngirchev.opendaimon.common.model.ConversationThread;
import io.github.ngirchev.opendaimon.common.service.MessageLocalizationService;
import io.github.ngirchev.opendaimon.telegram.TelegramBot;
import io.github.ngirchev.opendaimon.telegram.service.PersistentKeyboardService;
import io.github.ngirchev.opendaimon.telegram.service.TelegramChatPacer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;

import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sends messages to Telegram users on behalf of FSM actions.
 *
 * <p>Uses {@link ObjectProvider} for lazy bot resolution and delegates to
 * {@link TelegramBot#sendMessage} (same API as the handler's parent class).
 */
@Slf4j
@RequiredArgsConstructor
public class TelegramMessageSender {

    private static final Pattern RETRY_AFTER_PATTERN = Pattern.compile("retry after (\\d+)");

    private final ObjectProvider<TelegramBot> telegramBotProvider;
    private final MessageLocalizationService messageLocalizationService;
    private final PersistentKeyboardService persistentKeyboardService;
    private final TelegramChatPacer telegramChatPacer;

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
     * @return message ID, or {@code null} if bot is unavailable or send fails
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
        try {
            return bot.sendMessageAndGetId(chatId, htmlText, replyToMessageId, disableWebPagePreview);
        } catch (TelegramApiException e) {
            log.error("Failed to send message to chatId={}: {}", chatId, e.getMessage());
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
     */
    public void editHtml(Long chatId, Integer messageId, String htmlText,
                          boolean disableWebPagePreview) {
        TelegramBot bot = telegramBotProvider.getIfAvailable();
        if (bot == null) {
            log.warn("TelegramBot not available, cannot edit message in chatId={}", chatId);
            return;
        }
        try {
            bot.editMessageHtml(chatId, messageId, htmlText, disableWebPagePreview);
        } catch (TelegramApiException e) {
            log.error("Failed to edit message {} in chatId={}: {}", messageId, chatId, e.getMessage());
        }
    }

    public boolean editHtmlReliable(Long chatId, Integer messageId, String htmlText,
                                    boolean disableWebPagePreview, long maxWaitMs) {
        if (messageId == null) {
            return false;
        }
        TelegramBot bot = telegramBotProvider.getIfAvailable();
        if (bot == null) {
            log.warn("TelegramBot not available, cannot reliably edit message in chatId={}", chatId);
            return false;
        }
        long startedAt = System.currentTimeMillis();
        for (int attempt = 1; attempt <= 2; attempt++) {
            if (!reserveForReliable(chatId, startedAt, maxWaitMs)) {
                return false;
            }
            try {
                bot.editMessageHtml(chatId, messageId, htmlText, disableWebPagePreview);
                return true;
            } catch (TelegramApiException e) {
                if (!sleepForRetryAfterIfPossible("edit", chatId, e, startedAt, maxWaitMs, attempt)) {
                    logTelegramFailure("edit", chatId, messageId, e);
                    return false;
                }
            }
        }
        return false;
    }

    public Integer sendHtmlReliableAndGetId(Long chatId, String htmlText, Integer replyToMessageId,
                                            boolean disableWebPagePreview, long maxWaitMs) {
        TelegramBot bot = telegramBotProvider.getIfAvailable();
        if (bot == null) {
            log.warn("TelegramBot not available, cannot reliably send message to chatId={}", chatId);
            return null;
        }
        long startedAt = System.currentTimeMillis();
        for (int attempt = 1; attempt <= 2; attempt++) {
            if (!reserveForReliable(chatId, startedAt, maxWaitMs)) {
                return null;
            }
            try {
                return bot.sendMessageAndGetId(chatId, htmlText, replyToMessageId, disableWebPagePreview);
            } catch (TelegramApiException e) {
                if (!sleepForRetryAfterIfPossible("send", chatId, e, startedAt, maxWaitMs, attempt)) {
                    logTelegramFailure("send", chatId, null, e);
                    return null;
                }
            }
        }
        return null;
    }

    /**
     * Delete a message in a chat. Returns {@code true} on success, {@code false} when the
     * bot is unavailable or Telegram refused the request (message too old, no rights, etc).
     * Failure is logged at debug level — deletion is a best-effort UX nicety.
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

        try {
            bot.sendMessage(chatId, htmlText, replyToMessageId, keyboard);
        } catch (TelegramApiException e) {
            log.error("Failed to send message to chatId={}: {}", chatId, e.getMessage());
        }
    }

    private boolean reserveForReliable(Long chatId, long startedAt, long maxWaitMs) {
        long remainingMs = maxWaitMs - (System.currentTimeMillis() - startedAt);
        if (remainingMs < 0) {
            return false;
        }
        try {
            return telegramChatPacer.reserve(chatId, remainingMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while waiting for Telegram chat pacing slot, chatId={}", chatId);
            return false;
        }
    }

    private boolean sleepForRetryAfterIfPossible(String operation, Long chatId, TelegramApiException e,
                                                 long startedAt, long maxWaitMs, int attempt) {
        OptionalInt retryAfter = parseRetryAfterSeconds(e);
        if (retryAfter.isEmpty() || attempt >= 2) {
            return false;
        }
        long sleepMs = retryAfter.getAsInt() * 1000L;
        long elapsedMs = System.currentTimeMillis() - startedAt;
        if (elapsedMs + sleepMs > maxWaitMs) {
            log.warn("Telegram {} got 429 for chatId={} retryAfterSeconds={} exceeds remaining budget",
                    operation, chatId, retryAfter.getAsInt());
            return false;
        }
        log.warn("Telegram {} got 429 for chatId={}, retrying after {}s",
                operation, chatId, retryAfter.getAsInt());
        try {
            Thread.sleep(sleepMs);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while waiting for Telegram retry_after, chatId={}", chatId);
            return false;
        }
    }

    public OptionalInt parseRetryAfterSeconds(TelegramApiException e) {
        if (e instanceof TelegramApiRequestException requestException
                && requestException.getParameters() != null
                && requestException.getParameters().getRetryAfter() != null) {
            return OptionalInt.of(requestException.getParameters().getRetryAfter());
        }
        String message = e.getMessage();
        if (message == null) {
            return OptionalInt.empty();
        }
        Matcher matcher = RETRY_AFTER_PATTERN.matcher(message);
        if (matcher.find()) {
            return OptionalInt.of(Integer.parseInt(matcher.group(1)));
        }
        return OptionalInt.empty();
    }

    private void logTelegramFailure(String operation, Long chatId, Integer messageId, TelegramApiException e) {
        if (parseRetryAfterSeconds(e).isPresent()) {
            log.warn("Telegram {} failed with 429 for chatId={} messageId={}: {}",
                    operation, chatId, messageId, e.getMessage());
        } else {
            log.error("Telegram {} failed for chatId={} messageId={}: {}",
                    operation, chatId, messageId, e.getMessage());
        }
    }
}
