package io.github.ngirchev.aibot.telegram.command.handler.impl;

import org.springframework.beans.factory.ObjectProvider;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import io.github.ngirchev.aibot.common.command.ICommand;
import io.github.ngirchev.aibot.common.service.MessageLocalizationService;
import io.github.ngirchev.aibot.telegram.TelegramBot;
import io.github.ngirchev.aibot.telegram.command.TelegramCommand;
import io.github.ngirchev.aibot.telegram.command.TelegramCommandType;
import io.github.ngirchev.aibot.telegram.command.handler.AbstractTelegramCommandHandlerWithResponseSend;
import io.github.ngirchev.aibot.telegram.command.handler.TelegramCommandHandlerException;
import io.github.ngirchev.aibot.telegram.model.TelegramUser;
import io.github.ngirchev.aibot.telegram.service.TelegramUserService;
import io.github.ngirchev.aibot.telegram.service.TypingIndicatorService;

import java.util.List;

public class LanguageTelegramCommandHandler extends AbstractTelegramCommandHandlerWithResponseSend {

    private static final String CALLBACK_PREFIX = "LANG_";

    private final TelegramUserService telegramUserService;

    public LanguageTelegramCommandHandler(ObjectProvider<TelegramBot> telegramBotProvider,
                                          TypingIndicatorService typingIndicatorService,
                                          MessageLocalizationService messageLocalizationService,
                                          TelegramUserService telegramUserService) {
        super(telegramBotProvider, typingIndicatorService, messageLocalizationService);
        this.telegramUserService = telegramUserService;
    }

    @Override
    public String getSupportedCommandText(String languageCode) {
        return messageLocalizationService.getMessage("telegram.command.language.desc", languageCode);
    }

    @Override
    public boolean canHandle(ICommand<TelegramCommandType> command) {
        if (!(command instanceof TelegramCommand telegramCommand)) {
            return false;
        }
        if (telegramCommand.update().hasCallbackQuery()) {
            CallbackQuery cq = telegramCommand.update().getCallbackQuery();
            return cq.getData() != null && cq.getData().startsWith(CALLBACK_PREFIX);
        }
        var commandType = command.commandType();
        return commandType != null
                && commandType.command() != null
                && commandType.command().equals(TelegramCommand.LANGUAGE);
    }

    @Override
    public String handleInner(TelegramCommand command) {
        if (command.update().hasCallbackQuery()) {
            handleCallbackQuery(command);
            return null;
        }
        Message message = command.update().getMessage();
        if (message == null) {
            throw new TelegramCommandHandlerException(command.telegramId(), "Message is required for language command");
        }
        TelegramUser user = telegramUserService.getOrCreateUser(message.getFrom());
        String currentLang = user.getLanguageCode() != null ? user.getLanguageCode() : "ru";
        String currentLabel = languageLabel(currentLang, command.languageCode());
        String currentMsg = messageLocalizationService.getMessage("telegram.language.current", command.languageCode(), currentLabel);
        sendMessage(command.telegramId(), currentMsg);
        sendLanguageMenu(command.telegramId(), command.languageCode());
        return null;
    }

    private void handleCallbackQuery(TelegramCommand command) {
        CallbackQuery cq = command.update().getCallbackQuery();
        String callbackData = cq.getData();
        if (callbackData == null || !callbackData.startsWith(CALLBACK_PREFIX)) {
            throw new TelegramCommandHandlerException(command.telegramId(), "Invalid callback data");
        }
        String langCode = callbackData.substring(CALLBACK_PREFIX.length());
        if (langCode.isBlank()) {
            ackCallback(cq.getId(), "❌");
            return;
        }
        String normalized = langCode.toLowerCase().split("-")[0];
        if (!List.of("ru", "en").contains(normalized)) {
            ackCallback(cq.getId(), "❌");
            sendErrorMessage(command.telegramId(), messageLocalizationService.getMessage("telegram.language.unknown", command.languageCode()));
            return;
        }
        telegramUserService.updateLanguageCode(cq.getFrom().getId(), normalized);
        String label = languageLabel(normalized, normalized);
        String updatedMsg = messageLocalizationService.getMessage("telegram.language.updated", normalized, label);
        ackCallback(cq.getId(), updatedMsg);
        sendMessage(command.telegramId(), updatedMsg);
    }

    private void sendLanguageMenu(Long chatId, String languageCode) {
        try {
            String labelRu = messageLocalizationService.getMessage("telegram.language.label.ru", "ru");
            String labelEn = messageLocalizationService.getMessage("telegram.language.label.en", "en");
            List<InlineKeyboardButton> row = List.of(
                    buttonForLang("ru", labelRu),
                    buttonForLang("en", labelEn)
            );
            InlineKeyboardMarkup markup = new InlineKeyboardMarkup(List.of(row));
            String selectText = messageLocalizationService.getMessage("telegram.language.select", languageCode);
            SendMessage msg = new SendMessage(chatId.toString(), selectText);
            msg.setReplyMarkup(markup);
            telegramBotProvider.getObject().execute(msg);
        } catch (Exception e) {
            throw new TelegramCommandHandlerException("Failed to send language menu", e);
        }
    }

    private InlineKeyboardButton buttonForLang(String code, String label) {
        InlineKeyboardButton button = new InlineKeyboardButton(label);
        button.setCallbackData(CALLBACK_PREFIX + code);
        return button;
    }

    private String languageLabel(String code, String forLocale) {
        if (code == null) {
            return "ru";
        }
        String normalized = code.toLowerCase().split("-")[0];
        if (!List.of("ru", "en").contains(normalized)) {
            return code;
        }
        return messageLocalizationService.getMessage("telegram.language.label." + normalized, forLocale);
    }

    private void ackCallback(String callbackQueryId, String text) {
        try {
            AnswerCallbackQuery ack = new AnswerCallbackQuery();
            ack.setCallbackQueryId(callbackQueryId);
            ack.setText(text);
            ack.setShowAlert(false);
            telegramBotProvider.getObject().execute(ack);
        } catch (Exception e) {
            throw new TelegramCommandHandlerException("Failed to ack callback", e);
        }
    }
}
