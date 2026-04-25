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
import io.github.ngirchev.opendaimon.common.model.User;
import io.github.ngirchev.opendaimon.common.service.MessageLocalizationService;
import io.github.ngirchev.opendaimon.telegram.TelegramBot;
import io.github.ngirchev.opendaimon.telegram.command.TelegramCommand;
import io.github.ngirchev.opendaimon.telegram.command.TelegramCommandType;
import io.github.ngirchev.opendaimon.telegram.command.handler.AbstractTelegramCommandHandlerWithResponseSend;
import io.github.ngirchev.opendaimon.telegram.command.handler.TelegramCommandHandlerException;
import io.github.ngirchev.opendaimon.telegram.model.TelegramUser;
import io.github.ngirchev.opendaimon.telegram.service.ChatSettingsService;
import io.github.ngirchev.opendaimon.telegram.service.TelegramUserService;
import io.github.ngirchev.opendaimon.telegram.service.TypingIndicatorService;

import java.util.List;

@Slf4j
public class ModeTelegramCommandHandler extends AbstractTelegramCommandHandlerWithResponseSend {

    private static final String CALLBACK_PREFIX = "MODE_";
    private static final String CALLBACK_CANCEL = CALLBACK_PREFIX + "CANCEL";
    private static final String CALLBACK_AGENT = CALLBACK_PREFIX + "AGENT";
    private static final String CALLBACK_REGULAR = CALLBACK_PREFIX + "REGULAR";

    private final TelegramUserService telegramUserService;
    private final ChatSettingsService chatSettingsService;

    public ModeTelegramCommandHandler(ObjectProvider<TelegramBot> telegramBotProvider,
                                      TypingIndicatorService typingIndicatorService,
                                      MessageLocalizationService messageLocalizationService,
                                      TelegramUserService telegramUserService,
                                      ChatSettingsService chatSettingsService) {
        super(telegramBotProvider, typingIndicatorService, messageLocalizationService);
        this.telegramUserService = telegramUserService;
        this.chatSettingsService = chatSettingsService;
    }

    @Override
    public String getSupportedCommandText(String languageCode) {
        return messageLocalizationService.getMessage("telegram.command.mode.desc", languageCode);
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
                && commandType.command().equals(TelegramCommand.MODE);
    }

    @Override
    public String handleInner(TelegramCommand command) {
        if (command.update().hasCallbackQuery()) {
            handleCallbackQuery(command);
            return null;
        }
        Message message = command.update().getMessage();
        if (message == null) {
            throw new TelegramCommandHandlerException(command.telegramId(), "Message is required for mode command");
        }
        TelegramUser user = telegramUserService.getOrCreateUser(message.getFrom());
        User owner = TelegramCommand.resolveOwner(command,user);
        Boolean currentMode = owner.getAgentModeEnabled();
        String currentLabel = modeLabel(currentMode, command.languageCode());
        String currentMsg = messageLocalizationService.getMessage("telegram.mode.current", command.languageCode(), currentLabel);
        sendModeMenu(command.telegramId(), command.languageCode(), currentMsg);
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
        User owner = TelegramCommand.resolveOwner(command,telegramUserService.getOrCreateUser(cq.getFrom()));
        if (CALLBACK_AGENT.equals(callbackData)) {
            chatSettingsService.updateAgentMode(owner, true);
            String label = messageLocalizationService.getMessage("telegram.mode.label.agent", command.languageCode());
            String updatedMsg = messageLocalizationService.getMessage("telegram.mode.updated", command.languageCode(), label);
            ackCallback(cq.getId(), updatedMsg);
            deleteMenuMessage(command.telegramId(), cq);
            sendConfirmationMessage(command.telegramId(), updatedMsg);
            return;
        }
        if (CALLBACK_REGULAR.equals(callbackData)) {
            chatSettingsService.updateAgentMode(owner, false);
            String label = messageLocalizationService.getMessage("telegram.mode.label.regular", command.languageCode());
            String updatedMsg = messageLocalizationService.getMessage("telegram.mode.updated", command.languageCode(), label);
            ackCallback(cq.getId(), updatedMsg);
            deleteMenuMessage(command.telegramId(), cq);
            sendConfirmationMessage(command.telegramId(), updatedMsg);
            return;
        }
        ackCallback(cq.getId(), "❌");
        sendErrorMessage(command.telegramId(), messageLocalizationService.getMessage("telegram.mode.unknown", command.languageCode()));
    }

    private void sendModeMenu(Long chatId, String languageCode, String currentMsg) {
        try {
            String labelAgent = messageLocalizationService.getMessage("telegram.mode.label.agent", languageCode);
            String labelRegular = messageLocalizationService.getMessage("telegram.mode.label.regular", languageCode);
            List<InlineKeyboardButton> modeRow = List.of(
                    button(labelAgent, CALLBACK_AGENT),
                    button(labelRegular, CALLBACK_REGULAR)
            );
            String closeLabel = messageLocalizationService.getMessage("telegram.mode.close", languageCode);
            InlineKeyboardMarkup markup = new InlineKeyboardMarkup(List.of(
                    modeRow,
                    List.of(button(closeLabel, CALLBACK_CANCEL))
            ));
            String selectText = messageLocalizationService.getMessage("telegram.mode.select", languageCode);
            SendMessage msg = new SendMessage(chatId.toString(), currentMsg + "\n\n" + selectText);
            msg.setReplyMarkup(markup);
            telegramBotProvider.getObject().execute(msg);
        } catch (Exception e) {
            throw new TelegramCommandHandlerException("Failed to send mode menu", e);
        }
    }

    private InlineKeyboardButton button(String label, String callbackData) {
        InlineKeyboardButton button = new InlineKeyboardButton(label);
        button.setCallbackData(callbackData);
        return button;
    }

    private String modeLabel(Boolean agentModeEnabled, String languageCode) {
        if (Boolean.TRUE.equals(agentModeEnabled)) {
            return messageLocalizationService.getMessage("telegram.mode.label.agent", languageCode);
        }
        return messageLocalizationService.getMessage("telegram.mode.label.regular", languageCode);
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
     * selected mode in conversation history (not just as a transient toast).
     */
    private void sendConfirmationMessage(Long chatId, String text) {
        try {
            SendMessage msg = new SendMessage(chatId.toString(), text);
            telegramBotProvider.getObject().execute(msg);
        } catch (Exception e) {
            log.warn("Failed to send mode confirmation message: {}", e.getMessage());
        }
    }

    private void deleteMenuMessage(Long chatId, CallbackQuery callbackQuery) {
        if (callbackQuery.getMessage() instanceof Message menuMessage) {
            try {
                telegramBotProvider.getObject().execute(
                        new DeleteMessage(chatId.toString(), menuMessage.getMessageId()));
            } catch (Exception e) {
                log.warn("Failed to delete mode menu message: {}", e.getMessage());
            }
        }
    }
}
