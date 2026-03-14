package io.github.ngirchev.opendaimon.telegram.command.handler.impl;

import io.github.ngirchev.opendaimon.common.service.BugreportService;
import io.github.ngirchev.opendaimon.common.service.MessageLocalizationService;
import io.github.ngirchev.opendaimon.telegram.TelegramBot;
import io.github.ngirchev.opendaimon.telegram.command.TelegramCommand;
import io.github.ngirchev.opendaimon.telegram.command.TelegramCommandType;
import io.github.ngirchev.opendaimon.telegram.model.TelegramUser;
import io.github.ngirchev.opendaimon.telegram.model.TelegramUserSession;
import io.github.ngirchev.opendaimon.telegram.service.TelegramUserService;
import io.github.ngirchev.opendaimon.telegram.service.TypingIndicatorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.MessageSource;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Chat;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BugreportTelegramCommandHandlerTest {

    private static final Long CHAT_ID = 400L;

    @Mock
    private TelegramBot telegramBot;
    @Mock
    private TypingIndicatorService typingIndicatorService;
    @Mock
    private TelegramUserService telegramUserService;
    @Mock
    private BugreportService bugReportService;

    private MessageLocalizationService messageLocalizationService;
    private BugreportTelegramCommandHandler handler;

    @BeforeEach
    void setUp() {
        MessageSource messageSource = new ReloadableResourceBundleMessageSource();
        ((ReloadableResourceBundleMessageSource) messageSource).setBasenames(
                "classpath:messages/common", "classpath:messages/telegram");
        ((ReloadableResourceBundleMessageSource) messageSource).setDefaultEncoding("UTF-8");
        messageLocalizationService = new MessageLocalizationService(messageSource);

        ObjectProvider<TelegramBot> botProvider = mock(ObjectProvider.class);
        when(botProvider.getObject()).thenReturn(telegramBot);

        handler = new BugreportTelegramCommandHandler(
                botProvider, typingIndicatorService, messageLocalizationService,
                telegramUserService, bugReportService);
    }

    @Test
    void canHandle_whenBugreportCommand_thenTrue() {
        Update update = new Update();
        update.setMessage(new Message());
        TelegramCommand command = new TelegramCommand(100L, CHAT_ID,
                new TelegramCommandType(TelegramCommand.BUGREPORT), update);

        assertTrue(handler.canHandle(command));
    }

    @Test
    void canHandle_whenOtherCommand_thenFalse() {
        Update update = new Update();
        update.setMessage(new Message());
        TelegramCommand command = new TelegramCommand(100L, CHAT_ID,
                new TelegramCommandType(TelegramCommand.HISTORY), update);

        assertFalse(handler.canHandle(command));
    }

    @Test
    void handleInner_whenCallbackError_sendsErrorPromptAndUpdatesSession() throws TelegramApiException {
        CallbackQuery cq = new CallbackQuery();
        cq.setId("cq-1");
        cq.setData("ERROR");
        Message cqMessage = new Message();
        Chat chat = new Chat();
        chat.setId(CHAT_ID);
        cqMessage.setChat(chat);
        cq.setMessage(cqMessage);
        cq.setFrom(new User(100L, "u", false));

        Update update = new Update();
        update.setCallbackQuery(cq);

        TelegramUser telegramUser = new TelegramUser();
        TelegramUserSession session = new TelegramUserSession();
        session.setTelegramUser(telegramUser);
        when(telegramUserService.getOrCreateSession(cq.getFrom())).thenReturn(session);

        TelegramCommand command = new TelegramCommand(100L, CHAT_ID,
                new TelegramCommandType(TelegramCommand.BUGREPORT), update);

        handler.handleInner(command);

        verify(telegramBot).showTyping(CHAT_ID);
        verify(telegramBot, times(2)).execute(any(org.telegram.telegrambots.meta.api.methods.BotApiMethod.class));
        verify(telegramUserService).updateUserSession(telegramUser, TelegramCommand.BUGREPORT + "/ERROR");
    }

    @Test
    void handleInner_whenCallbackImprovement_sendsSuggestionPrompt() throws TelegramApiException {
        CallbackQuery cq = new CallbackQuery();
        cq.setId("cq-2");
        cq.setData("IMPROVEMENT");
        Message cqMessage = new Message();
        Chat chat = new Chat();
        chat.setId(CHAT_ID);
        cqMessage.setChat(chat);
        cq.setMessage(cqMessage);
        cq.setFrom(new User(100L, "u", false));

        Update update = new Update();
        update.setCallbackQuery(cq);

        TelegramUserSession session = new TelegramUserSession();
        session.setTelegramUser(new TelegramUser());
        when(telegramUserService.getOrCreateSession(any())).thenReturn(session);

        TelegramCommand command = new TelegramCommand(100L, CHAT_ID,
                new TelegramCommandType(TelegramCommand.BUGREPORT), update);

        handler.handleInner(command);

        verify(telegramUserService).updateUserSession(session.getTelegramUser(), TelegramCommand.BUGREPORT + "/IMPROVEMENT");
    }

    @Test
    void handleInner_whenMessageAndNoBotStatus_sendsMenu() throws TelegramApiException {
        Message message = new Message();
        message.setFrom(new User(100L, "u", false));
        Update update = new Update();
        update.setMessage(message);

        TelegramUserSession session = new TelegramUserSession();
        session.setTelegramUser(new TelegramUser());
        session.setBotStatus("");
        when(telegramUserService.getOrCreateSession(message.getFrom())).thenReturn(session);

        TelegramCommand command = new TelegramCommand(100L, CHAT_ID,
                new TelegramCommandType(TelegramCommand.BUGREPORT), update);

        handler.handleInner(command);

        verify(telegramUserService).updateUserSession(session.getTelegramUser(), TelegramCommand.BUGREPORT);
        verify(telegramBot).execute(any(org.telegram.telegrambots.meta.api.methods.send.SendMessage.class));
    }

    @Test
    void handleInner_whenMessageAndStatusError_savesBugAndSendsConfirmation() throws TelegramApiException {
        User from = new User(100L, "u", false);
        Message message = new Message();
        message.setFrom(from);
        message.setText("  Something broke  ");
        Update update = new Update();
        update.setMessage(message);

        TelegramUser telegramUser = new TelegramUser();
        TelegramUserSession session = new TelegramUserSession();
        session.setTelegramUser(telegramUser);
        session.setBotStatus(TelegramCommand.BUGREPORT + "/ERROR");
        session.setIsActive(true);
        when(telegramUserService.getOrCreateSession(from)).thenReturn(session);

        TelegramCommand command = new TelegramCommand(100L, CHAT_ID,
                new TelegramCommandType(TelegramCommand.BUGREPORT), update);

        handler.handleInner(command);

        verify(bugReportService).saveBug(telegramUser, "Something broke");
        verify(telegramBot).clearStatus(100L);
        verify(telegramBot).sendMessage(eq(CHAT_ID), eq("Message saved"), any());
    }

    @Test
    void handleInner_whenMessageAndStatusImprovement_savesImprovementAndSendsConfirmation() throws TelegramApiException {
        User from = new User(200L, "u", false);
        Message message = new Message();
        message.setFrom(from);
        message.setText("Add dark mode");
        Update update = new Update();
        update.setMessage(message);

        TelegramUser telegramUser = new TelegramUser();
        TelegramUserSession session = new TelegramUserSession();
        session.setTelegramUser(telegramUser);
        session.setBotStatus(TelegramCommand.BUGREPORT + "/IMPROVEMENT");
        session.setIsActive(true);
        when(telegramUserService.getOrCreateSession(from)).thenReturn(session);

        TelegramCommand command = new TelegramCommand(200L, CHAT_ID,
                new TelegramCommandType(TelegramCommand.BUGREPORT), update);

        handler.handleInner(command);

        verify(bugReportService).saveImprovementProposal(telegramUser, "Add dark mode");
        verify(telegramBot).clearStatus(200L);
        verify(telegramBot).sendMessage(eq(CHAT_ID), eq("Message saved"), any());
    }

    @Test
    void getSupportedCommandText_returnsLocalizedMessage() {
        String desc = handler.getSupportedCommandText("en");
        assertNotNull(desc);
    }
}
