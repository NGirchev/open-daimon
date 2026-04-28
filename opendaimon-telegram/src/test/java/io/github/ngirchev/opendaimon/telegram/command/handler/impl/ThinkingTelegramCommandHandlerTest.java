package io.github.ngirchev.opendaimon.telegram.command.handler.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import io.github.ngirchev.opendaimon.common.command.ICommand;
import io.github.ngirchev.opendaimon.common.model.ThinkingMode;
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
class ThinkingTelegramCommandHandlerTest {

    private static final Long CHAT_ID = 100500L;
    private static final Long USER_ID = 123L;

    @Mock private ObjectProvider<TelegramBot> telegramBotProvider;
    @Mock private TelegramBot telegramBot;
    @Mock private TypingIndicatorService typingIndicatorService;
    @Mock private MessageLocalizationService messageLocalizationService;
    @Mock private TelegramUserService telegramUserService;
    @Mock private TelegramBotMenuService telegramBotMenuService;
    @Mock private io.github.ngirchev.opendaimon.telegram.service.ChatSettingsService chatSettingsService;

    private ThinkingTelegramCommandHandler handler;

    @BeforeEach
    void setUp() {
        when(telegramBotProvider.getObject()).thenReturn(telegramBot);
        when(messageLocalizationService.getMessage(eq("telegram.command.thinking.desc"), anyString()))
            .thenReturn("/thinking - configure reasoning visibility");
        when(messageLocalizationService.getMessage(eq("telegram.thinking.current"), anyString(), anyString()))
            .thenAnswer(inv -> "Current setting: " + inv.getArgument(2));
        when(messageLocalizationService.getMessage(eq("telegram.thinking.select"), anyString()))
            .thenReturn("Choose reasoning visibility:");
        when(messageLocalizationService.getMessage(eq("telegram.thinking.label.show_all"), anyString()))
            .thenReturn("Show reasoning");
        when(messageLocalizationService.getMessage(eq("telegram.thinking.label.tools_only"), anyString()))
            .thenReturn("Tools only");
        when(messageLocalizationService.getMessage(eq("telegram.thinking.label.silent"), anyString()))
            .thenReturn("Silent mode");
        when(messageLocalizationService.getMessage(eq("telegram.thinking.updated"), anyString(), anyString()))
            .thenAnswer(inv -> "Reasoning visibility updated: " + inv.getArgument(2));
        when(messageLocalizationService.getMessage(eq("telegram.thinking.close"), anyString()))
            .thenReturn("Cancel / Close");
        when(messageLocalizationService.getMessage(eq("telegram.thinking.unknown"), anyString()))
            .thenReturn("Unknown option");
        handler = new ThinkingTelegramCommandHandler(
            telegramBotProvider, typingIndicatorService, messageLocalizationService, telegramUserService, telegramBotMenuService,
            chatSettingsService);
    }

    @Test
    void canHandle_whenTelegramCommandWithThinkingCommand_thenTrue() {
        Update update = mock(Update.class);
        when(update.hasCallbackQuery()).thenReturn(false);
        TelegramCommand command = new TelegramCommand(USER_ID, CHAT_ID, new TelegramCommandType(TelegramCommand.THINKING), update);
        assertTrue(handler.canHandle(command));
    }

    @Test
    void canHandle_whenNotTelegramCommand_thenFalse() {
        assertFalse(handler.canHandle(mock(ICommand.class)));
    }

    @Test
    void canHandle_whenCallbackQueryWithThinkingPrefix_thenTrue() {
        Update update = mock(Update.class);
        when(update.hasCallbackQuery()).thenReturn(true);
        CallbackQuery cq = mock(CallbackQuery.class);
        when(update.getCallbackQuery()).thenReturn(cq);
        when(cq.getData()).thenReturn("THINKING_SHOW_ALL");
        TelegramCommand command = new TelegramCommand(USER_ID, CHAT_ID, new TelegramCommandType(TelegramCommand.THINKING), update);
        assertTrue(handler.canHandle(command));
    }

    @Test
    void canHandle_whenCallbackQueryWithOtherPrefix_thenFalse() {
        Update update = mock(Update.class);
        when(update.hasCallbackQuery()).thenReturn(true);
        CallbackQuery cq = mock(CallbackQuery.class);
        when(update.getCallbackQuery()).thenReturn(cq);
        when(cq.getData()).thenReturn("LANG_ru");
        TelegramCommand command = new TelegramCommand(USER_ID, CHAT_ID, new TelegramCommandType(TelegramCommand.THINKING), update);
        assertFalse(handler.canHandle(command));
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
        TelegramCommand command = new TelegramCommand(USER_ID, CHAT_ID, new TelegramCommandType(TelegramCommand.THINKING), update);
        assertThrows(TelegramCommandHandlerException.class, () -> handler.handleInner(command));
    }

    @Test
    void handleInner_whenPlainCommand_thenSendsCurrentSettingAndFourButtonMenu() throws TelegramApiException {
        Update update = mock(Update.class);
        when(update.hasCallbackQuery()).thenReturn(false);
        Message message = mock(Message.class);
        User from = mock(User.class);
        when(update.getMessage()).thenReturn(message);
        when(message.getFrom()).thenReturn(from);

        TelegramUser telegramUser = new TelegramUser();
        telegramUser.setId(1L);
        telegramUser.setThinkingMode(ThinkingMode.HIDE_REASONING);
        when(telegramUserService.getOrCreateUser(from)).thenReturn(telegramUser);

        TelegramCommand command = new TelegramCommand(USER_ID, CHAT_ID, new TelegramCommandType(TelegramCommand.THINKING), update);
        command.languageCode("en");

        handler.handleInner(command);

        ArgumentCaptor<SendMessage> messageCaptor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramBot).execute(messageCaptor.capture());
        SendMessage sentMessage = messageCaptor.getValue();
        assertEquals(CHAT_ID.toString(), sentMessage.getChatId());
        assertTrue(sentMessage.getText().contains("Current setting"));
        assertTrue(sentMessage.getText().contains("Choose reasoning visibility"));

        InlineKeyboardMarkup markup = (InlineKeyboardMarkup) sentMessage.getReplyMarkup();
        assertNotNull(markup);
        // 4 rows: show_all, tools_only, silent, cancel
        assertEquals(4, markup.getKeyboard().size());
        assertEquals("THINKING_SHOW_ALL", markup.getKeyboard().get(0).get(0).getCallbackData());
        assertEquals("THINKING_HIDE_REASONING", markup.getKeyboard().get(1).get(0).getCallbackData());
        assertEquals("THINKING_SILENT", markup.getKeyboard().get(2).get(0).getCallbackData());
        assertEquals("THINKING_CANCEL", markup.getKeyboard().get(3).get(0).getCallbackData());
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
        telegramUser.setThinkingMode(ThinkingMode.HIDE_REASONING);
        when(telegramUserService.getOrCreateUser(from)).thenReturn(telegramUser);

        TelegramCommand command = new TelegramCommand(USER_ID, CHAT_ID, new TelegramCommandType(TelegramCommand.THINKING), update);
        command.languageCode("en");

        handler.handle(command);

        verify(typingIndicatorService, never()).startTyping(CHAT_ID);
        verify(typingIndicatorService, never()).stopTyping(CHAT_ID);
    }

    @Test
    void shouldShowCurrentModeInPromptWhenUserHasShowAll() throws TelegramApiException {
        Update update = mock(Update.class);
        when(update.hasCallbackQuery()).thenReturn(false);
        Message message = mock(Message.class);
        User from = mock(User.class);
        when(update.getMessage()).thenReturn(message);
        when(message.getFrom()).thenReturn(from);

        TelegramUser user = new TelegramUser();
        user.setThinkingMode(ThinkingMode.SHOW_ALL);
        when(telegramUserService.getOrCreateUser(from)).thenReturn(user);

        TelegramCommand command = new TelegramCommand(USER_ID, CHAT_ID, new TelegramCommandType(TelegramCommand.THINKING), update);
        command.languageCode("en");
        handler.handleInner(command);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramBot).execute(captor.capture());
        assertTrue(captor.getValue().getText().contains("Show reasoning"));
    }

    @Test
    void shouldShowCurrentModeInPromptWhenUserHasToolsOnly() throws TelegramApiException {
        Update update = mock(Update.class);
        when(update.hasCallbackQuery()).thenReturn(false);
        Message message = mock(Message.class);
        User from = mock(User.class);
        when(update.getMessage()).thenReturn(message);
        when(message.getFrom()).thenReturn(from);

        TelegramUser user = new TelegramUser();
        user.setThinkingMode(ThinkingMode.HIDE_REASONING);
        when(telegramUserService.getOrCreateUser(from)).thenReturn(user);

        TelegramCommand command = new TelegramCommand(USER_ID, CHAT_ID, new TelegramCommandType(TelegramCommand.THINKING), update);
        command.languageCode("en");
        handler.handleInner(command);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramBot).execute(captor.capture());
        assertTrue(captor.getValue().getText().contains("Tools only"));
    }

    @Test
    void shouldShowCurrentModeInPromptWhenUserHasSilent() throws TelegramApiException {
        Update update = mock(Update.class);
        when(update.hasCallbackQuery()).thenReturn(false);
        Message message = mock(Message.class);
        User from = mock(User.class);
        when(update.getMessage()).thenReturn(message);
        when(message.getFrom()).thenReturn(from);

        TelegramUser user = new TelegramUser();
        user.setThinkingMode(ThinkingMode.SILENT);
        when(telegramUserService.getOrCreateUser(from)).thenReturn(user);

        TelegramCommand command = new TelegramCommand(USER_ID, CHAT_ID, new TelegramCommandType(TelegramCommand.THINKING), update);
        command.languageCode("en");
        handler.handleInner(command);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramBot).execute(captor.capture());
        assertTrue(captor.getValue().getText().contains("Silent mode"));
    }

    @Test
    void shouldPersistShowAllWhenThinkingShowAllCallback() throws TelegramApiException {
        Update update = mock(Update.class);
        when(update.hasCallbackQuery()).thenReturn(true);
        CallbackQuery cq = mock(CallbackQuery.class);
        User from = mock(User.class);
        when(update.getCallbackQuery()).thenReturn(cq);
        when(cq.getData()).thenReturn("THINKING_SHOW_ALL");
        when(cq.getFrom()).thenReturn(from);
        when(from.getId()).thenReturn(USER_ID);
        when(cq.getId()).thenReturn("cq-1");
        Message callbackMessage = mock(Message.class);
        when(callbackMessage.getMessageId()).thenReturn(77);
        when(cq.getMessage()).thenReturn(callbackMessage);

        TelegramCommand command = new TelegramCommand(USER_ID, CHAT_ID, new TelegramCommandType(TelegramCommand.THINKING), update);
        command.languageCode("en");

        handler.handleInner(command);

        verify(chatSettingsService).updateThinkingMode(any(), eq(ThinkingMode.SHOW_ALL));
        verify(telegramBot).execute(any(AnswerCallbackQuery.class));
        verify(telegramBot).execute(any(DeleteMessage.class));
    }

    @Test
    void shouldPersistHideReasoningWhenThinkingHideReasoningCallback() throws TelegramApiException {
        Update update = mock(Update.class);
        when(update.hasCallbackQuery()).thenReturn(true);
        CallbackQuery cq = mock(CallbackQuery.class);
        User from = mock(User.class);
        when(update.getCallbackQuery()).thenReturn(cq);
        when(cq.getData()).thenReturn("THINKING_HIDE_REASONING");
        when(cq.getFrom()).thenReturn(from);
        when(from.getId()).thenReturn(USER_ID);
        when(cq.getId()).thenReturn("cq-2");
        Message callbackMessage = mock(Message.class);
        when(callbackMessage.getMessageId()).thenReturn(88);
        when(cq.getMessage()).thenReturn(callbackMessage);

        TelegramCommand command = new TelegramCommand(USER_ID, CHAT_ID, new TelegramCommandType(TelegramCommand.THINKING), update);
        command.languageCode("en");

        handler.handleInner(command);

        verify(chatSettingsService).updateThinkingMode(any(), eq(ThinkingMode.HIDE_REASONING));
        verify(telegramBot).execute(any(AnswerCallbackQuery.class));
        verify(telegramBot).execute(any(DeleteMessage.class));
    }

    @Test
    void shouldPersistSilentWhenThinkingSilentCallback() throws TelegramApiException {
        Update update = mock(Update.class);
        when(update.hasCallbackQuery()).thenReturn(true);
        CallbackQuery cq = mock(CallbackQuery.class);
        User from = mock(User.class);
        when(update.getCallbackQuery()).thenReturn(cq);
        when(cq.getData()).thenReturn("THINKING_SILENT");
        when(cq.getFrom()).thenReturn(from);
        when(from.getId()).thenReturn(USER_ID);
        when(cq.getId()).thenReturn("cq-3");
        Message callbackMessage = mock(Message.class);
        when(callbackMessage.getMessageId()).thenReturn(89);
        when(cq.getMessage()).thenReturn(callbackMessage);

        TelegramCommand command = new TelegramCommand(USER_ID, CHAT_ID, new TelegramCommandType(TelegramCommand.THINKING), update);
        command.languageCode("en");

        handler.handleInner(command);

        verify(chatSettingsService).updateThinkingMode(any(), eq(ThinkingMode.SILENT));
        verify(telegramBot).execute(any(AnswerCallbackQuery.class));
        verify(telegramBot).execute(any(DeleteMessage.class));
    }

    @Test
    void shouldDeleteMenuWhenThinkingCancelCallback() throws TelegramApiException {
        Update update = mock(Update.class);
        when(update.hasCallbackQuery()).thenReturn(true);
        CallbackQuery cq = mock(CallbackQuery.class);
        when(update.getCallbackQuery()).thenReturn(cq);
        when(cq.getData()).thenReturn("THINKING_CANCEL");
        when(cq.getId()).thenReturn("cq-4");
        Message callbackMessage = mock(Message.class);
        when(callbackMessage.getMessageId()).thenReturn(99);
        when(cq.getMessage()).thenReturn(callbackMessage);

        TelegramCommand command = new TelegramCommand(USER_ID, CHAT_ID, new TelegramCommandType(TelegramCommand.THINKING), update);

        handler.handleInner(command);

        verify(telegramBot).execute(any(AnswerCallbackQuery.class));
        verify(telegramBot).execute(any(DeleteMessage.class));
        verify(telegramUserService, never()).updateThinkingMode(anyLong(), any(ThinkingMode.class));
    }

    @Test
    void getSupportedCommandText_returnsLocalizedDesc() {
        assertEquals("/thinking - configure reasoning visibility", handler.getSupportedCommandText("en"));
    }
}
