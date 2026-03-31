package io.github.ngirchev.opendaimon.telegram.command.handler.impl.fsm;

import io.github.ngirchev.opendaimon.common.model.ConversationThread;
import io.github.ngirchev.opendaimon.common.service.MessageLocalizationService;
import io.github.ngirchev.opendaimon.telegram.TelegramBot;
import io.github.ngirchev.opendaimon.telegram.service.PersistentKeyboardService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

/**
 * Sends messages to Telegram users on behalf of FSM actions.
 *
 * <p>Uses {@link ObjectProvider} for lazy bot resolution and delegates to
 * {@link TelegramBot#sendMessage} (same API as the handler's parent class).
 */
@Slf4j
@RequiredArgsConstructor
public class TelegramMessageSender {

    private final ObjectProvider<TelegramBot> telegramBotProvider;
    private final MessageLocalizationService messageLocalizationService;
    private final PersistentKeyboardService persistentKeyboardService;

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
}
