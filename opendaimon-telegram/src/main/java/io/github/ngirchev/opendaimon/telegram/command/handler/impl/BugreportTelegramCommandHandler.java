package io.github.ngirchev.opendaimon.telegram.command.handler.impl;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import io.github.ngirchev.opendaimon.common.command.ICommand;
import io.github.ngirchev.opendaimon.common.service.BugreportService;
import io.github.ngirchev.opendaimon.telegram.TelegramBot;
import io.github.ngirchev.opendaimon.telegram.command.TelegramCommand;
import io.github.ngirchev.opendaimon.telegram.command.TelegramCommandType;
import io.github.ngirchev.opendaimon.common.service.MessageLocalizationService;
import io.github.ngirchev.opendaimon.telegram.command.handler.AbstractTelegramCommandHandler;
import io.github.ngirchev.opendaimon.telegram.model.TelegramUserSession;
import io.github.ngirchev.opendaimon.telegram.service.TelegramUserService;
import io.github.ngirchev.opendaimon.telegram.service.TypingIndicatorService;

import java.util.List;

@Slf4j
public class BugreportTelegramCommandHandler extends AbstractTelegramCommandHandler {

    private final TelegramUserService telegramUserService;
    private final BugreportService bugReportService;

    public BugreportTelegramCommandHandler(ObjectProvider<TelegramBot> telegramBotProvider,
                                           TypingIndicatorService typingIndicatorService,
                                           MessageLocalizationService messageLocalizationService,
                                           TelegramUserService telegramUserService,
                                           BugreportService bugReportService) {
        super(telegramBotProvider, typingIndicatorService, messageLocalizationService);
        this.telegramUserService = telegramUserService;
        this.bugReportService = bugReportService;
    }

    @Override
    public String getSupportedCommandText(String languageCode) {
        return messageLocalizationService.getMessage("telegram.command.bugreport.desc", languageCode);
    }

    @Override
    public boolean canHandle(ICommand<TelegramCommandType> command) {
        if (!(command instanceof TelegramCommand)) return false;
        var commandType = command.commandType();
        if (commandType == null || commandType.command() == null) return false;
        return commandType.command().startsWith(TelegramCommand.BUGREPORT);
    }

    @Override
    public void handleInner(TelegramCommand command) throws TelegramApiException {
        if (command.update().hasCallbackQuery()) {
            handleCallbackQuery(command.update().getCallbackQuery());
        } else if (command.update().hasMessage()) {
            handleBugreportMessage(command);
        }
    }

    private void handleCallbackQuery(CallbackQuery cq) throws TelegramApiException {
        String data = cq.getData();
        var message = cq.getMessage();
        Long chatId = message.getChatId();
        var telegramBot = telegramBotProvider.getObject();
        var userSession = telegramUserService.getOrCreateSession(cq.getFrom());
        telegramBot.showTyping(chatId);
        ackCallback(cq.getId());
        switch (data) {
            case "ERROR" -> telegramBot.execute(new SendMessage(chatId.toString(), "Enter error description"));
            case "IMPROVEMENT" -> telegramBot.execute(new SendMessage(chatId.toString(), "Enter your suggestion"));
            default -> telegramBot.execute(new SendMessage(chatId.toString(), "Unknown command: " + data));
        }
        telegramUserService.updateUserSession(userSession.getTelegramUser(), TelegramCommand.BUGREPORT + "/" + data);
    }

    private void handleBugreportMessage(TelegramCommand command) throws TelegramApiException {
        Message message = command.update().getMessage();
        var userSession = telegramUserService.getOrCreateSession(message.getFrom());
        if (!StringUtils.isBlank(userSession.getBotStatus())) {
            handleBugreportStatusReply(command, userSession, message);
        } else {
            telegramUserService.updateUserSession(userSession.getTelegramUser(), TelegramCommand.BUGREPORT);
            sendMenu(command.telegramId());
        }
    }

    private void handleBugreportStatusReply(TelegramCommand command, TelegramUserSession userSession,
                                            Message message) throws TelegramApiException {
        if (!userSession.getIsActive()) {
            log.warn("We don't know what to do sessionIsActive[{}] and botStatus[{}]", userSession.getIsActive(), userSession.getBotStatus());
        }
        String status = userSession.getBotStatus();
        if ((TelegramCommand.BUGREPORT + "/ERROR").equals(status)) {
            bugReportService.saveBug(userSession.getTelegramUser(), message.getText().strip());
            telegramBotProvider.getObject().clearStatus(message.getFrom().getId());
            sendMessage(command.telegramId(), "Message saved");
        } else if ((TelegramCommand.BUGREPORT + "/IMPROVEMENT").equals(status)) {
            bugReportService.saveImprovementProposal(userSession.getTelegramUser(), message.getText().strip());
            telegramBotProvider.getObject().clearStatus(message.getFrom().getId());
            sendMessage(command.telegramId(), "Message saved");
        } else {
            throw new IllegalArgumentException();
        }
    }

    public void sendMenu(Long chatId) throws TelegramApiException {
        InlineKeyboardButton b1 = new InlineKeyboardButton("Report a bug");
        b1.setCallbackData("ERROR");

        InlineKeyboardButton b2 = new InlineKeyboardButton("Suggest improvement");
        b2.setCallbackData("IMPROVEMENT");

        InlineKeyboardMarkup kb = new InlineKeyboardMarkup();
        kb.setKeyboard(List.of(
                List.of(b1, b2) // one row, two buttons
        ));

        SendMessage msg = new SendMessage(chatId.toString(), "Choose an action:");
        msg.setReplyMarkup(kb);
        telegramBotProvider.getObject().execute(msg);
    }

    public void ackCallback(String callbackQueryId) throws TelegramApiException {
        AnswerCallbackQuery ack = new AnswerCallbackQuery();
        ack.setCallbackQueryId(callbackQueryId);
        ack.setText("OK! Processing...");
        ack.setShowAlert(false);
        telegramBotProvider.getObject().execute(ack);
    }
}
