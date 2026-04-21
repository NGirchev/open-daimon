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
import io.github.ngirchev.opendaimon.common.model.ThinkingMode;
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

@Slf4j
public class ThinkingTelegramCommandHandler extends AbstractTelegramCommandHandlerWithResponseSend {

    private static final String CALLBACK_PREFIX = "THINKING_";
    private static final String CALLBACK_SHOW_ALL = CALLBACK_PREFIX + "SHOW_ALL";
    private static final String CALLBACK_HIDE_REASONING = CALLBACK_PREFIX + "HIDE_REASONING";
    private static final String CALLBACK_SILENT = CALLBACK_PREFIX + "SILENT";
    private static final String CALLBACK_CANCEL = CALLBACK_PREFIX + "CANCEL";

    private final TelegramUserService telegramUserService;
    private final TelegramBotMenuService menuService;

    public ThinkingTelegramCommandHandler(ObjectProvider<TelegramBot> telegramBotProvider,
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
        return messageLocalizationService.getMessage("telegram.command.thinking.desc", languageCode);
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
                && commandType.command().equals(TelegramCommand.THINKING);
    }

    @Override
    public String handleInner(TelegramCommand command) {
        if (command.update().hasCallbackQuery()) {
            handleCallbackQuery(command);
            return null;
        }
        Message message = command.update().getMessage();
        if (message == null) {
            throw new TelegramCommandHandlerException(command.telegramId(), "Message is required for thinking command");
        }
        TelegramUser user = telegramUserService.getOrCreateUser(message.getFrom());
        ThinkingMode currentMode = user.getThinkingMode() != null ? user.getThinkingMode() : ThinkingMode.HIDE_REASONING;
        String currentLabel = thinkingModeLabel(currentMode, command.languageCode());
        String currentMsg = messageLocalizationService.getMessage("telegram.thinking.current", command.languageCode(), currentLabel);
        sendThinkingMenu(command.telegramId(), command.languageCode(), currentMsg);
        return null;
    }

    private void handleCallbackQuery(TelegramCommand command) {
        CallbackQuery cq = command.update().getCallbackQuery();
        String callbackData = cq.getData();
        log.info("ThinkingTelegramCommandHandler callback: telegramId={}, data={}",
                cq.getFrom() != null ? cq.getFrom().getId() : null, callbackData);
        if (callbackData == null || !callbackData.startsWith(CALLBACK_PREFIX)) {
            throw new TelegramCommandHandlerException(command.telegramId(), "Invalid callback data");
        }
        if (CALLBACK_CANCEL.equals(callbackData)) {
            ackCallback(cq.getId(), "");
            deleteMenuMessage(command.telegramId(), cq);
            return;
        }
        if (CALLBACK_SHOW_ALL.equals(callbackData)) {
            telegramUserService.updateThinkingMode(cq.getFrom().getId(), ThinkingMode.SHOW_ALL);
            String label = messageLocalizationService.getMessage("telegram.thinking.label.show_all", command.languageCode());
            String updatedMsg = messageLocalizationService.getMessage("telegram.thinking.updated", command.languageCode(), label);
            ackCallback(cq.getId(), updatedMsg);
            deleteMenuMessage(command.telegramId(), cq);
            sendConfirmationMessage(command.telegramId(), updatedMsg);
            return;
        }
        if (CALLBACK_HIDE_REASONING.equals(callbackData)) {
            telegramUserService.updateThinkingMode(cq.getFrom().getId(), ThinkingMode.HIDE_REASONING);
            String label = messageLocalizationService.getMessage("telegram.thinking.label.tools_only", command.languageCode());
            String updatedMsg = messageLocalizationService.getMessage("telegram.thinking.updated", command.languageCode(), label);
            ackCallback(cq.getId(), updatedMsg);
            deleteMenuMessage(command.telegramId(), cq);
            sendConfirmationMessage(command.telegramId(), updatedMsg);
            return;
        }
        if (CALLBACK_SILENT.equals(callbackData)) {
            telegramUserService.updateThinkingMode(cq.getFrom().getId(), ThinkingMode.SILENT);
            String label = messageLocalizationService.getMessage("telegram.thinking.label.silent", command.languageCode());
            String updatedMsg = messageLocalizationService.getMessage("telegram.thinking.updated", command.languageCode(), label);
            ackCallback(cq.getId(), updatedMsg);
            deleteMenuMessage(command.telegramId(), cq);
            sendConfirmationMessage(command.telegramId(), updatedMsg);
            return;
        }
        ackCallback(cq.getId(), "❌");
        sendErrorMessage(command.telegramId(), messageLocalizationService.getMessage("telegram.thinking.unknown", command.languageCode()));
    }

    private void sendThinkingMenu(Long chatId, String languageCode, String currentMsg) {
        try {
            String labelShowAll = messageLocalizationService.getMessage("telegram.thinking.label.show_all", languageCode);
            String labelToolsOnly = messageLocalizationService.getMessage("telegram.thinking.label.tools_only", languageCode);
            String labelSilent = messageLocalizationService.getMessage("telegram.thinking.label.silent", languageCode);
            String closeLabel = messageLocalizationService.getMessage("telegram.thinking.close", languageCode);
            InlineKeyboardMarkup markup = new InlineKeyboardMarkup(List.of(
                    List.of(button(labelShowAll, CALLBACK_SHOW_ALL)),
                    List.of(button(labelToolsOnly, CALLBACK_HIDE_REASONING)),
                    List.of(button(labelSilent, CALLBACK_SILENT)),
                    List.of(button(closeLabel, CALLBACK_CANCEL))
            ));
            String selectText = messageLocalizationService.getMessage("telegram.thinking.select", languageCode);
            SendMessage msg = new SendMessage(chatId.toString(), currentMsg + "\n\n" + selectText);
            msg.setReplyMarkup(markup);
            telegramBotProvider.getObject().execute(msg);
        } catch (Exception e) {
            throw new TelegramCommandHandlerException("Failed to send thinking menu", e);
        }
    }

    private InlineKeyboardButton button(String label, String callbackData) {
        InlineKeyboardButton button = new InlineKeyboardButton(label);
        button.setCallbackData(callbackData);
        return button;
    }

    private String thinkingModeLabel(ThinkingMode mode, String languageCode) {
        return switch (mode) {
            case SHOW_ALL -> messageLocalizationService.getMessage("telegram.thinking.label.show_all", languageCode);
            case HIDE_REASONING -> messageLocalizationService.getMessage("telegram.thinking.label.tools_only", languageCode);
            case SILENT -> messageLocalizationService.getMessage("telegram.thinking.label.silent", languageCode);
        };
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

    /**
     * Posts a persistent confirmation message into the chat so the user sees the
     * selected thinking mode in conversation history (not just as a transient toast).
     */
    private void sendConfirmationMessage(Long chatId, String text) {
        try {
            SendMessage msg = new SendMessage(chatId.toString(), text);
            telegramBotProvider.getObject().execute(msg);
        } catch (Exception e) {
            log.warn("Failed to send thinking confirmation message: {}", e.getMessage());
        }
    }

    private void deleteMenuMessage(Long chatId, CallbackQuery callbackQuery) {
        if (callbackQuery.getMessage() instanceof Message menuMessage) {
            try {
                telegramBotProvider.getObject().execute(
                        new DeleteMessage(chatId.toString(), menuMessage.getMessageId()));
            } catch (Exception e) {
                log.warn("Failed to delete thinking menu message: {}", e.getMessage());
            }
        }
    }
}
