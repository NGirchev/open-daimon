package io.github.ngirchev.opendaimon.telegram.command.handler.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import io.github.ngirchev.opendaimon.common.command.ICommand;
import io.github.ngirchev.opendaimon.common.service.MessageLocalizationService;
import io.github.ngirchev.opendaimon.telegram.TelegramBot;
import io.github.ngirchev.opendaimon.telegram.command.TelegramCommand;
import io.github.ngirchev.opendaimon.telegram.command.TelegramCommandType;
import io.github.ngirchev.opendaimon.telegram.command.handler.AbstractTelegramCommandHandlerWithResponseSend;
import io.github.ngirchev.opendaimon.telegram.command.handler.TelegramCommandHandlerException;
import io.github.ngirchev.opendaimon.telegram.model.TelegramUser;
import io.github.ngirchev.opendaimon.telegram.service.TelegramBotMenuService;
import io.github.ngirchev.opendaimon.telegram.service.TelegramUserService;
import io.github.ngirchev.opendaimon.telegram.service.TypingIndicatorService;

import java.util.List;

import static io.github.ngirchev.opendaimon.common.SupportedLanguages.DEFAULT_LANGUAGE;
import static io.github.ngirchev.opendaimon.common.SupportedLanguages.EN;
import static io.github.ngirchev.opendaimon.common.SupportedLanguages.RU;
import static io.github.ngirchev.opendaimon.common.SupportedLanguages.SUPPORTED_LANGUAGES;

@Slf4j
public class LanguageTelegramCommandHandler extends AbstractTelegramCommandHandlerWithResponseSend {

    private static final String CALLBACK_PREFIX = "LANG_";
    private static final String CALLBACK_CANCEL = CALLBACK_PREFIX + "CANCEL";

    private final TelegramUserService telegramUserService;
    private final TelegramBotMenuService menuService;

    public LanguageTelegramCommandHandler(ObjectProvider<TelegramBot> telegramBotProvider,
                                          TypingIndicatorService typingIndicatorService,
                                          MessageLocalizationService messageLocalizationService,
                                          TelegramUserService telegramUserService,
                                          TelegramBotMenuService menuService) {
        super(telegramBotProvider, typingIndicatorService, messageLocalizationService);
        this.telegramUserService = telegramUserService;
        this.menuService = menuService;
    }

    @Override
    public String getSupportedCommandText(String languageCode) {
        return messageLocalizationService.getMessage("telegram.command.language.desc", languageCode);
    }

    @Override
    protected boolean shouldShowTypingIndicator(TelegramCommand command) {
        return false;
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
        String currentLang = user.getLanguageCode() != null ? user.getLanguageCode() : DEFAULT_LANGUAGE;
        String currentLabel = languageLabel(currentLang, command.languageCode());
        String currentMsg = messageLocalizationService.getMessage("telegram.language.current", command.languageCode(), currentLabel);
        sendLanguageMenu(command.telegramId(), command.languageCode(), currentMsg);
        return null;
    }

    private void handleCallbackQuery(TelegramCommand command) {
        CallbackQuery cq = command.update().getCallbackQuery();
        String callbackData = cq.getData();
        if (callbackData == null || !callbackData.startsWith(CALLBACK_PREFIX)) {
            throw new TelegramCommandHandlerException(command.telegramId(), "Invalid callback data");
        }
        if (CALLBACK_CANCEL.equals(callbackData)) {
            ackCallback(cq.getId(), "");
            deleteMenuMessage(command.telegramId(), cq);
            return;
        }
        String langCode = callbackData.substring(CALLBACK_PREFIX.length());
        if (langCode.isBlank()) {
            ackCallback(cq.getId(), "❌");
            return;
        }
        String normalized = langCode.toLowerCase().split("-")[0];
        if (!SUPPORTED_LANGUAGES.contains(normalized)) {
            ackCallback(cq.getId(), "❌");
            sendErrorMessage(command.telegramId(), messageLocalizationService.getMessage("telegram.language.unknown", command.languageCode()));
            return;
        }
        telegramUserService.updateLanguageCode(cq.getFrom().getId(), normalized);
        menuService.setupBotMenuForUser(command.telegramId(), normalized);
        String label = languageLabel(normalized, normalized);
        String updatedMsg = messageLocalizationService.getMessage("telegram.language.updated", normalized, label);
        ackCallback(cq.getId(), updatedMsg);
        deleteMenuMessage(command.telegramId(), cq);
    }

    private void sendLanguageMenu(Long chatId, String languageCode, String currentMsg) {
        try {
            String labelRu = messageLocalizationService.getMessage("telegram.language.label.ru", RU);
            String labelEn = messageLocalizationService.getMessage("telegram.language.label.en", EN);
            List<InlineKeyboardButton> languageRow = List.of(
                    buttonForLang(RU, labelRu),
                    buttonForLang(EN, labelEn)
            );
            String closeLabel = messageLocalizationService.getMessage("telegram.language.close", languageCode);
            InlineKeyboardMarkup markup = new InlineKeyboardMarkup(List.of(
                    languageRow,
                    List.of(button(closeLabel, CALLBACK_CANCEL))
            ));
            String selectText = messageLocalizationService.getMessage("telegram.language.select", languageCode);
            SendMessage msg = new SendMessage(chatId.toString(), currentMsg + "\n\n" + selectText);
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

    private InlineKeyboardButton button(String label, String callbackData) {
        InlineKeyboardButton button = new InlineKeyboardButton(label);
        button.setCallbackData(callbackData);
        return button;
    }

    private String languageLabel(String code, String forLocale) {
        if (code == null) {
            return DEFAULT_LANGUAGE;
        }
        String normalized = code.toLowerCase().split("-")[0];
        if (!SUPPORTED_LANGUAGES.contains(normalized)) {
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

    private void deleteMenuMessage(Long chatId, CallbackQuery callbackQuery) {
        if (callbackQuery.getMessage() instanceof Message menuMessage) {
            try {
                telegramBotProvider.getObject().execute(
                        new DeleteMessage(chatId.toString(), menuMessage.getMessageId()));
            } catch (Exception e) {
                log.warn("Failed to delete language menu message: {}", e.getMessage());
            }
        }
    }
}
