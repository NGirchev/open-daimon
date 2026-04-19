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
import io.github.ngirchev.opendaimon.telegram.service.TelegramBotMenuService;
import io.github.ngirchev.opendaimon.telegram.service.TelegramUserService;
import io.github.ngirchev.opendaimon.telegram.service.TypingIndicatorService;
import org.springframework.beans.factory.ObjectProvider;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LanguageTelegramCommandHandlerTest {

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
    @Mock
    private TelegramBotMenuService telegramBotMenuService;

    private LanguageTelegramCommandHandler handler;

    @BeforeEach
    void setUp() {
        when(telegramBotProvider.getObject()).thenReturn(telegramBot);
        when(messageLocalizationService.getMessage(eq("telegram.command.language.desc"), anyString()))
            .thenReturn("/language - set language");
        when(messageLocalizationService.getMessage(eq("telegram.language.current"), anyString(), anyString()))
            .thenReturn("Current language: {0}");
        when(messageLocalizationService.getMessage(eq("telegram.language.select"), anyString()))
            .thenReturn("Choose language:");
        when(messageLocalizationService.getMessage(eq("telegram.language.label.ru"), anyString()))
            .thenReturn("Russian");
        when(messageLocalizationService.getMessage(eq("telegram.language.label.en"), anyString()))
            .thenReturn("English");
        when(messageLocalizationService.getMessage(eq("telegram.language.updated"), anyString(), anyString()))
            .thenReturn("Language updated: {0}");
        when(messageLocalizationService.getMessage(eq("telegram.language.close"), anyString()))
            .thenReturn("Cancel / Close");
        when(messageLocalizationService.getMessage(eq("telegram.language.unknown"), anyString()))
            .thenReturn("Unknown language");
        handler = new LanguageTelegramCommandHandler(
            telegramBotProvider, typingIndicatorService, messageLocalizationService, telegramUserService, telegramBotMenuService);
    }

    @Test
    void canHandle_whenTelegramCommandWithLanguageCommand_thenTrue() {
        Update update = mock(Update.class);
        when(update.hasCallbackQuery()).thenReturn(false);
        TelegramCommand command = new TelegramCommand(USER_ID, CHAT_ID, new TelegramCommandType(TelegramCommand.LANGUAGE), update);
        assertTrue(handler.canHandle(command));
    }

    @Test
    void canHandle_whenNotTelegramCommand_thenFalse() {
        assertFalse(handler.canHandle(mock(ICommand.class)));
    }

    @Test
    void canHandle_whenCallbackQueryWithLangPrefix_thenTrue() {
        Update update = mock(Update.class);
        when(update.hasCallbackQuery()).thenReturn(true);
        CallbackQuery cq = mock(CallbackQuery.class);
        when(update.getCallbackQuery()).thenReturn(cq);
        when(cq.getData()).thenReturn("LANG_ru");
        TelegramCommand command = new TelegramCommand(USER_ID, CHAT_ID, new TelegramCommandType(TelegramCommand.LANGUAGE), update);
        assertTrue(handler.canHandle(command));
    }

    @Test
    void canHandle_whenCallbackQueryWithCancel_thenTrue() {
        Update update = mock(Update.class);
        when(update.hasCallbackQuery()).thenReturn(true);
        CallbackQuery cq = mock(CallbackQuery.class);
        when(update.getCallbackQuery()).thenReturn(cq);
        when(cq.getData()).thenReturn("LANG_CANCEL");
        TelegramCommand command = new TelegramCommand(USER_ID, CHAT_ID, new TelegramCommandType(TelegramCommand.LANGUAGE), update);
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
        TelegramCommand command = new TelegramCommand(USER_ID, CHAT_ID, new TelegramCommandType(TelegramCommand.LANGUAGE), update);
        assertThrows(TelegramCommandHandlerException.class, () -> handler.handleInner(command));
    }

    @Test
    void handleInner_whenPlainCommand_thenSendsCurrentLanguageAndMenu() throws TelegramApiException {
        Update update = mock(Update.class);
        when(update.hasCallbackQuery()).thenReturn(false);
        Message message = mock(Message.class);
        User from = mock(User.class);
        when(update.getMessage()).thenReturn(message);
        when(message.getFrom()).thenReturn(from);

        TelegramUser telegramUser = new TelegramUser();
        telegramUser.setId(1L);
        telegramUser.setLanguageCode("ru");
        when(telegramUserService.getOrCreateUser(from)).thenReturn(telegramUser);

        TelegramCommand command = new TelegramCommand(USER_ID, CHAT_ID, new TelegramCommandType(TelegramCommand.LANGUAGE), update);
        command.languageCode("ru");

        handler.handleInner(command);

        verify(telegramBot, never()).sendMessage(anyLong(), anyString(), any(), any());
        ArgumentCaptor<SendMessage> messageCaptor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramBot).execute(messageCaptor.capture());
        SendMessage sentMessage = messageCaptor.getValue();
        assertEquals(CHAT_ID.toString(), sentMessage.getChatId());
        assertTrue(sentMessage.getText().contains("Current language"));
        assertTrue(sentMessage.getText().contains("Choose language"));

        InlineKeyboardMarkup markup = (InlineKeyboardMarkup) sentMessage.getReplyMarkup();
        assertNotNull(markup);
        assertEquals(2, markup.getKeyboard().size());
        assertEquals("LANG_ru", markup.getKeyboard().getFirst().get(0).getCallbackData());
        assertEquals("LANG_en", markup.getKeyboard().getFirst().get(1).getCallbackData());
        assertEquals("LANG_CANCEL", markup.getKeyboard().get(1).getFirst().getCallbackData());
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
        telegramUser.setLanguageCode("ru");
        when(telegramUserService.getOrCreateUser(from)).thenReturn(telegramUser);

        TelegramCommand command = new TelegramCommand(USER_ID, CHAT_ID, new TelegramCommandType(TelegramCommand.LANGUAGE), update);
        command.languageCode("ru");

        handler.handle(command);

        verify(typingIndicatorService, never()).startTyping(CHAT_ID);
        verify(typingIndicatorService, never()).stopTyping(CHAT_ID);
    }

    @Test
    void handleInner_whenCallbackRu_thenUpdatesLanguageAndClosesMenu() throws TelegramApiException {
        Update update = mock(Update.class);
        when(update.hasCallbackQuery()).thenReturn(true);
        CallbackQuery cq = mock(CallbackQuery.class);
        User from = mock(User.class);
        when(update.getCallbackQuery()).thenReturn(cq);
        when(cq.getData()).thenReturn("LANG_ru");
        when(cq.getFrom()).thenReturn(from);
        when(cq.getId()).thenReturn("cq-1");
        Message callbackMessage = mock(Message.class);
        when(callbackMessage.getMessageId()).thenReturn(77);
        when(cq.getMessage()).thenReturn(callbackMessage);

        when(telegramUserService.getOrCreateUser(from)).thenReturn(new TelegramUser());

        TelegramCommand command = new TelegramCommand(USER_ID, CHAT_ID, new TelegramCommandType(TelegramCommand.LANGUAGE), update);
        command.languageCode("en");

        handler.handleInner(command);

        verify(telegramUserService).updateLanguageCode(eq(from.getId()), eq("ru"));
        verify(telegramBotMenuService).setupBotMenuForUser(eq(CHAT_ID), eq("ru"));
        verify(telegramBot).execute(any(org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery.class));
        verify(telegramBot).execute(any(DeleteMessage.class));
        verify(telegramBot, never()).sendMessage(anyLong(), anyString(), any(), any());
    }

    @Test
    void handle_whenCallbackRu_doesNotStartTyping() throws TelegramApiException {
        Update update = mock(Update.class);
        when(update.hasCallbackQuery()).thenReturn(true);
        CallbackQuery cq = mock(CallbackQuery.class);
        User from = mock(User.class);
        when(update.getCallbackQuery()).thenReturn(cq);
        when(cq.getData()).thenReturn("LANG_ru");
        when(cq.getFrom()).thenReturn(from);
        when(from.getId()).thenReturn(USER_ID);
        when(cq.getId()).thenReturn("cq-1");
        Message callbackMessage = mock(Message.class);
        when(callbackMessage.getMessageId()).thenReturn(77);
        when(cq.getMessage()).thenReturn(callbackMessage);

        TelegramCommand command = new TelegramCommand(USER_ID, CHAT_ID, new TelegramCommandType(TelegramCommand.LANGUAGE), update);
        command.languageCode("en");

        handler.handle(command);

        verify(typingIndicatorService, never()).startTyping(CHAT_ID);
        verify(typingIndicatorService, never()).stopTyping(CHAT_ID);
        verify(telegramUserService).updateLanguageCode(USER_ID, "ru");
        verify(telegramBot).execute(any(DeleteMessage.class));
    }

    @Test
    void handleInner_whenCallbackEn_thenUpdatesLanguage() throws TelegramApiException {
        Update update = mock(Update.class);
        when(update.hasCallbackQuery()).thenReturn(true);
        CallbackQuery cq = mock(CallbackQuery.class);
        User from = mock(User.class);
        when(update.getCallbackQuery()).thenReturn(cq);
        when(cq.getData()).thenReturn("LANG_en");
        when(cq.getFrom()).thenReturn(from);
        when(cq.getId()).thenReturn("cq-1");
        Message callbackMessage = mock(Message.class);
        when(callbackMessage.getMessageId()).thenReturn(77);
        when(cq.getMessage()).thenReturn(callbackMessage);

        when(telegramUserService.getOrCreateUser(from)).thenReturn(new TelegramUser());

        TelegramCommand command = new TelegramCommand(USER_ID, CHAT_ID, new TelegramCommandType(TelegramCommand.LANGUAGE), update);

        handler.handleInner(command);

        verify(telegramUserService).updateLanguageCode(eq(from.getId()), eq("en"));
        verify(telegramBot).execute(any(DeleteMessage.class));
        verify(telegramBot, never()).sendMessage(anyLong(), anyString(), any(), any());
    }

    @Test
    void handleInner_whenCallbackCancel_thenDeletesMenuWithoutUpdatingLanguage() throws TelegramApiException {
        Update update = mock(Update.class);
        when(update.hasCallbackQuery()).thenReturn(true);
        CallbackQuery cq = mock(CallbackQuery.class);
        when(update.getCallbackQuery()).thenReturn(cq);
        when(cq.getData()).thenReturn("LANG_CANCEL");
        when(cq.getId()).thenReturn("cq-1");
        Message callbackMessage = mock(Message.class);
        when(callbackMessage.getMessageId()).thenReturn(77);
        when(cq.getMessage()).thenReturn(callbackMessage);

        TelegramCommand command = new TelegramCommand(USER_ID, CHAT_ID, new TelegramCommandType(TelegramCommand.LANGUAGE), update);

        handler.handleInner(command);

        verify(telegramBot).execute(any(org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery.class));
        verify(telegramBot).execute(any(DeleteMessage.class));
        verify(telegramUserService, never()).updateLanguageCode(anyLong(), anyString());
        verify(telegramBotMenuService, never()).setupBotMenuForUser(anyLong(), anyString());
        verify(telegramBot, never()).sendMessage(anyLong(), anyString(), any(), any());
    }

    @Test
    void handleInner_whenCallbackUnknownLang_thenSendsError() throws TelegramApiException {
        Update update = mock(Update.class);
        when(update.hasCallbackQuery()).thenReturn(true);
        CallbackQuery cq = mock(CallbackQuery.class);
        when(update.getCallbackQuery()).thenReturn(cq);
        when(cq.getData()).thenReturn("LANG_fr");
        when(cq.getFrom()).thenReturn(mock(User.class));
        when(cq.getId()).thenReturn("cq-1");

        when(telegramUserService.getOrCreateUser(any())).thenReturn(new TelegramUser());
        when(messageLocalizationService.getMessage(eq("telegram.language.unknown"), any())).thenReturn("Unknown language");

        TelegramCommand command = new TelegramCommand(USER_ID, CHAT_ID, new TelegramCommandType(TelegramCommand.LANGUAGE), update);

        handler.handleInner(command);

        verify(telegramBot).sendErrorMessage(eq(CHAT_ID), eq("Unknown language"), isNull());
    }

    @Test
    void getSupportedCommandText_returnsLocalizedDesc() {
        assertEquals("/language - set language", handler.getSupportedCommandText("en"));
    }
}
