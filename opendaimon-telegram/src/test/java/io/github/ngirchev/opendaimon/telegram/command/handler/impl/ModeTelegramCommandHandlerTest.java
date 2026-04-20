package io.github.ngirchev.opendaimon.telegram.command.handler.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import io.github.ngirchev.opendaimon.common.command.ICommand;
import io.github.ngirchev.opendaimon.common.service.MessageLocalizationService;
import io.github.ngirchev.opendaimon.telegram.TelegramBot;
import io.github.ngirchev.opendaimon.telegram.command.TelegramCommand;
import io.github.ngirchev.opendaimon.telegram.command.TelegramCommandType;
import io.github.ngirchev.opendaimon.telegram.command.handler.TelegramCommandHandlerException;
import io.github.ngirchev.opendaimon.telegram.model.TelegramUser;
import io.github.ngirchev.opendaimon.telegram.service.TelegramUserService;
import io.github.ngirchev.opendaimon.telegram.service.TypingIndicatorService;
import org.springframework.beans.factory.ObjectProvider;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ModeTelegramCommandHandlerTest {

    private static final Long CHAT_ID = 100500L;
    private static final Long USER_ID = 123L;

    @Mock
    private ObjectProvider<TelegramBot> telegramBotProvider;
    @Mock
    private TelegramBot telegramBot;
    @Mock
    private TypingIndicatorService typingIndicatorService;
    @Mock
    private MessageLocalizationService messageLocalizationService;
    @Mock
    private TelegramUserService telegramUserService;

    private ModeTelegramCommandHandler handler;

    @BeforeEach
    void setUp() {
        when(telegramBotProvider.getObject()).thenReturn(telegramBot);
        when(messageLocalizationService.getMessage(eq("telegram.command.mode.desc"), anyString()))
            .thenReturn("/mode - switch agent mode");
        when(messageLocalizationService.getMessage(eq("telegram.mode.current"), anyString(), anyString()))
            .thenReturn("Current mode: {0}");
        when(messageLocalizationService.getMessage(eq("telegram.mode.select"), anyString()))
            .thenReturn("Choose mode:");
        when(messageLocalizationService.getMessage(eq("telegram.mode.label.agent"), anyString()))
            .thenReturn("Agent mode");
        when(messageLocalizationService.getMessage(eq("telegram.mode.label.regular"), anyString()))
            .thenReturn("Regular mode");
        when(messageLocalizationService.getMessage(eq("telegram.mode.updated"), anyString(), anyString()))
            .thenReturn("Mode switched: {0}");
        when(messageLocalizationService.getMessage(eq("telegram.mode.close"), anyString()))
            .thenReturn("Cancel / Close");
        when(messageLocalizationService.getMessage(eq("telegram.mode.unknown"), anyString()))
            .thenReturn("Unknown mode");
        handler = new ModeTelegramCommandHandler(
            telegramBotProvider, typingIndicatorService, messageLocalizationService, telegramUserService);
    }

    @Test
    void canHandle_whenTelegramCommandWithModeCommand_thenTrue() {
        Update update = mock(Update.class);
        when(update.hasCallbackQuery()).thenReturn(false);
        TelegramCommand command = new TelegramCommand(USER_ID, CHAT_ID, new TelegramCommandType(TelegramCommand.MODE), update);
        assertTrue(handler.canHandle(command));
    }

    @Test
    void canHandle_whenNotTelegramCommand_thenFalse() {
        assertFalse(handler.canHandle(mock(ICommand.class)));
    }

    @Test
    void canHandle_whenCallbackQueryWithModePrefix_thenTrue() {
        Update update = mock(Update.class);
        when(update.hasCallbackQuery()).thenReturn(true);
        CallbackQuery cq = mock(CallbackQuery.class);
        when(update.getCallbackQuery()).thenReturn(cq);
        when(cq.getData()).thenReturn("MODE_AGENT");
        TelegramCommand command = new TelegramCommand(USER_ID, CHAT_ID, new TelegramCommandType(TelegramCommand.MODE), update);
        assertTrue(handler.canHandle(command));
    }

    @Test
    void canHandle_whenCallbackQueryWithCancel_thenTrue() {
        Update update = mock(Update.class);
        when(update.hasCallbackQuery()).thenReturn(true);
        CallbackQuery cq = mock(CallbackQuery.class);
        when(update.getCallbackQuery()).thenReturn(cq);
        when(cq.getData()).thenReturn("MODE_CANCEL");
        TelegramCommand command = new TelegramCommand(USER_ID, CHAT_ID, new TelegramCommandType(TelegramCommand.MODE), update);
        assertTrue(handler.canHandle(command));
    }

    @Test
    void canHandle_whenCommandTypeNull_thenFalse() {
        Update update = mock(Update.class);
        when(update.hasCallbackQuery()).thenReturn(false);
        TelegramCommand command = new TelegramCommand(USER_ID, CHAT_ID, null, update);
        assertFalse(handler.canHandle(command));
    }

    @Test
    void handleInner_whenMessageNull_thenThrows() {
        Update update = mock(Update.class);
        when(update.hasCallbackQuery()).thenReturn(false);
        when(update.getMessage()).thenReturn(null);
        TelegramCommand command = new TelegramCommand(USER_ID, CHAT_ID, new TelegramCommandType(TelegramCommand.MODE), update);
        assertThrows(TelegramCommandHandlerException.class, () -> handler.handleInner(command));
    }

    @Test
    void handleInner_whenPlainCommand_thenSendsCurrentModeAndMenu() throws TelegramApiException {
        Update update = mock(Update.class);
        when(update.hasCallbackQuery()).thenReturn(false);
        Message message = mock(Message.class);
        User from = mock(User.class);
        when(update.getMessage()).thenReturn(message);
        when(message.getFrom()).thenReturn(from);

        TelegramUser telegramUser = new TelegramUser();
        telegramUser.setId(1L);
        telegramUser.setAgentModeEnabled(true);
        when(telegramUserService.getOrCreateUser(from)).thenReturn(telegramUser);

        TelegramCommand command = new TelegramCommand(USER_ID, CHAT_ID, new TelegramCommandType(TelegramCommand.MODE), update);
        command.languageCode("en");

        handler.handleInner(command);

        ArgumentCaptor<SendMessage> messageCaptor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramBot).execute(messageCaptor.capture());
        SendMessage sentMessage = messageCaptor.getValue();
        assertEquals(CHAT_ID.toString(), sentMessage.getChatId());
        assertTrue(sentMessage.getText().contains("Current mode"));
        assertTrue(sentMessage.getText().contains("Choose mode"));

        InlineKeyboardMarkup markup = (InlineKeyboardMarkup) sentMessage.getReplyMarkup();
        assertNotNull(markup);
        assertEquals(2, markup.getKeyboard().size());
        assertEquals("MODE_AGENT", markup.getKeyboard().getFirst().get(0).getCallbackData());
        assertEquals("MODE_REGULAR", markup.getKeyboard().getFirst().get(1).getCallbackData());
        assertEquals("MODE_CANCEL", markup.getKeyboard().get(1).getFirst().getCallbackData());
    }

    @Test
    void handle_whenPlainCommand_doesNotStartTyping() {
        Update update = mock(Update.class);
        when(update.hasCallbackQuery()).thenReturn(false);
        Message message = mock(Message.class);
        User from = mock(User.class);
        when(update.getMessage()).thenReturn(message);
        when(message.getFrom()).thenReturn(from);

        TelegramUser telegramUser = new TelegramUser();
        telegramUser.setId(1L);
        telegramUser.setAgentModeEnabled(false);
        when(telegramUserService.getOrCreateUser(from)).thenReturn(telegramUser);

        TelegramCommand command = new TelegramCommand(USER_ID, CHAT_ID, new TelegramCommandType(TelegramCommand.MODE), update);
        command.languageCode("en");

        handler.handle(command);

        verify(typingIndicatorService, never()).startTyping(CHAT_ID);
        verify(typingIndicatorService, never()).stopTyping(CHAT_ID);
    }

    @Test
    void handleInner_whenCallbackAgent_thenUpdatesAgentModeAndClosesMenu() throws TelegramApiException {
        Update update = mock(Update.class);
        when(update.hasCallbackQuery()).thenReturn(true);
        CallbackQuery cq = mock(CallbackQuery.class);
        User from = mock(User.class);
        when(update.getCallbackQuery()).thenReturn(cq);
        when(cq.getData()).thenReturn("MODE_AGENT");
        when(cq.getFrom()).thenReturn(from);
        when(from.getId()).thenReturn(USER_ID);
        when(cq.getId()).thenReturn("cq-1");
        Message callbackMessage = mock(Message.class);
        when(callbackMessage.getMessageId()).thenReturn(77);
        when(cq.getMessage()).thenReturn(callbackMessage);

        TelegramCommand command = new TelegramCommand(USER_ID, CHAT_ID, new TelegramCommandType(TelegramCommand.MODE), update);
        command.languageCode("en");

        handler.handleInner(command);

        verify(telegramUserService).updateAgentMode(USER_ID, true);
        verify(telegramBot).execute(any(org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery.class));
        verify(telegramBot).execute(any(DeleteMessage.class));
    }

    @Test
    void handleInner_whenCallbackRegular_thenUpdatesRegularModeAndClosesMenu() throws TelegramApiException {
        Update update = mock(Update.class);
        when(update.hasCallbackQuery()).thenReturn(true);
        CallbackQuery cq = mock(CallbackQuery.class);
        User from = mock(User.class);
        when(update.getCallbackQuery()).thenReturn(cq);
        when(cq.getData()).thenReturn("MODE_REGULAR");
        when(cq.getFrom()).thenReturn(from);
        when(from.getId()).thenReturn(USER_ID);
        when(cq.getId()).thenReturn("cq-1");
        Message callbackMessage = mock(Message.class);
        when(callbackMessage.getMessageId()).thenReturn(78);
        when(cq.getMessage()).thenReturn(callbackMessage);

        TelegramCommand command = new TelegramCommand(USER_ID, CHAT_ID, new TelegramCommandType(TelegramCommand.MODE), update);
        command.languageCode("en");

        handler.handleInner(command);

        verify(telegramUserService).updateAgentMode(USER_ID, false);
        verify(telegramBot).execute(any(org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery.class));
        verify(telegramBot).execute(any(DeleteMessage.class));
    }

    @Test
    void handleInner_whenCallbackCancel_thenDeletesMenuWithoutUpdatingMode() throws TelegramApiException {
        Update update = mock(Update.class);
        when(update.hasCallbackQuery()).thenReturn(true);
        CallbackQuery cq = mock(CallbackQuery.class);
        when(update.getCallbackQuery()).thenReturn(cq);
        when(cq.getData()).thenReturn("MODE_CANCEL");
        when(cq.getId()).thenReturn("cq-1");
        Message callbackMessage = mock(Message.class);
        when(callbackMessage.getMessageId()).thenReturn(79);
        when(cq.getMessage()).thenReturn(callbackMessage);

        TelegramCommand command = new TelegramCommand(USER_ID, CHAT_ID, new TelegramCommandType(TelegramCommand.MODE), update);

        handler.handleInner(command);

        verify(telegramBot).execute(any(org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery.class));
        verify(telegramBot).execute(any(DeleteMessage.class));
        verify(telegramUserService, never()).updateAgentMode(anyLong(), anyBoolean());
    }

    @Test
    void handleInner_whenCallbackUnknown_thenSendsError() throws TelegramApiException {
        Update update = mock(Update.class);
        when(update.hasCallbackQuery()).thenReturn(true);
        CallbackQuery cq = mock(CallbackQuery.class);
        when(update.getCallbackQuery()).thenReturn(cq);
        when(cq.getData()).thenReturn("MODE_UNKNOWN_VALUE");
        when(cq.getFrom()).thenReturn(mock(User.class));
        when(cq.getId()).thenReturn("cq-1");

        TelegramCommand command = new TelegramCommand(USER_ID, CHAT_ID, new TelegramCommandType(TelegramCommand.MODE), update);
        command.languageCode("en");

        handler.handleInner(command);

        verify(telegramBot).sendErrorMessage(eq(CHAT_ID), eq("Unknown mode"), isNull());
    }

    @Test
    void getSupportedCommandText_returnsLocalizedDesc() {
        assertEquals("/mode - switch agent mode", handler.getSupportedCommandText("en"));
    }
}
