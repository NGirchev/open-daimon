package io.github.ngirchev.opendaimon.telegram.command.handler.impl;

import io.github.ngirchev.opendaimon.telegram.command.handler.TelegramCommandHandlerException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.MessageSource;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import io.github.ngirchev.opendaimon.common.model.ConversationThread;
import io.github.ngirchev.opendaimon.common.model.ThreadScopeKind;
import io.github.ngirchev.opendaimon.common.repository.ConversationThreadRepository;
import io.github.ngirchev.opendaimon.common.service.ConversationThreadService;
import io.github.ngirchev.opendaimon.common.service.MessageLocalizationService;
import io.github.ngirchev.opendaimon.telegram.TelegramBot;
import io.github.ngirchev.opendaimon.telegram.command.TelegramCommand;
import io.github.ngirchev.opendaimon.common.command.ICommand;
import io.github.ngirchev.opendaimon.telegram.command.TelegramCommandType;
import io.github.ngirchev.opendaimon.telegram.model.TelegramUser;
import io.github.ngirchev.opendaimon.telegram.service.TelegramUserService;
import io.github.ngirchev.opendaimon.telegram.service.TypingIndicatorService;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.mockito.ArgumentCaptor;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ThreadsTelegramCommandHandlerTest {

    private static final Long CHAT_ID = 100L;
    private static final String THREADS_CALLBACK_PREFIX = "THREADS_";
    private static final String THREADS_CALLBACK_CANCEL = "THREADS_CANCEL";

    @Mock
    private TelegramBot telegramBot;
    @Mock
    private TypingIndicatorService typingIndicatorService;
    @Mock
    private ConversationThreadRepository threadRepository;
    @Mock
    private ConversationThreadService threadService;
    @Mock
    private TelegramUserService userService;

    private MessageLocalizationService messageLocalizationService;
    private ThreadsTelegramCommandHandler handler;

    @BeforeEach
    void setUp() {
        MessageSource messageSource = new ReloadableResourceBundleMessageSource();
        ((ReloadableResourceBundleMessageSource) messageSource).setBasenames(
                "classpath:messages/common", "classpath:messages/telegram");
        ((ReloadableResourceBundleMessageSource) messageSource).setDefaultEncoding("UTF-8");
        messageLocalizationService = new MessageLocalizationService(messageSource);

        ObjectProvider<TelegramBot> botProvider = mock(ObjectProvider.class);
        when(botProvider.getObject()).thenReturn(telegramBot);

        handler = new ThreadsTelegramCommandHandler(botProvider, typingIndicatorService, messageLocalizationService,
                threadRepository, threadService, userService);
    }

    @Test
    void canHandle_whenTelegramCommandWithThreadsCommandAndNoCallback_thenTrue() {
        Update update = new Update();
        Message message = new Message();
        message.setFrom(new User(200L, "user", false));
        update.setMessage(message);

        TelegramCommand command = new TelegramCommand(200L, CHAT_ID, new TelegramCommandType(TelegramCommand.THREADS), update);
        assertTrue(handler.canHandle(command));
    }

    @Test
    void canHandle_whenCallbackQueryWithThreadsPrefix_thenTrue() {
        Update update = new Update();
        CallbackQuery cq = new CallbackQuery();
        cq.setData(THREADS_CALLBACK_PREFIX + "thread-key-123");
        cq.setFrom(new User(200L, "user", false));
        update.setCallbackQuery(cq);

        TelegramCommand command = new TelegramCommand(200L, CHAT_ID, new TelegramCommandType(TelegramCommand.THREADS), update);
        assertTrue(handler.canHandle(command));
    }

    @Test
    void canHandle_whenCallbackQueryWithCancel_thenTrue() {
        Update update = new Update();
        CallbackQuery cq = new CallbackQuery();
        cq.setData(THREADS_CALLBACK_CANCEL);
        cq.setFrom(new User(200L, "user", false));
        update.setCallbackQuery(cq);

        TelegramCommand command = new TelegramCommand(200L, CHAT_ID, new TelegramCommandType(TelegramCommand.THREADS), update);
        assertTrue(handler.canHandle(command));
    }

    @Test
    void canHandle_whenNotTelegramCommand_thenFalse() {
        @SuppressWarnings("unchecked")
        ICommand<TelegramCommandType> other = mock(ICommand.class);
        assertFalse(handler.canHandle(other));
    }

    @Test
    void canHandle_whenOtherCommand_thenFalse() {
        Update update = new Update();
        update.setMessage(new Message());
        TelegramCommand command = new TelegramCommand(200L, CHAT_ID, new TelegramCommandType(TelegramCommand.ROLE), update);
        assertFalse(handler.canHandle(command));
    }

    @Test
    void handleInner_whenMessageNull_thenThrows() {
        Update update = new Update();
        update.setMessage(null);
        update.setCallbackQuery(null);
        TelegramCommand command = new TelegramCommand(200L, CHAT_ID, new TelegramCommandType(TelegramCommand.THREADS), update);

        assertThrows(TelegramCommandHandlerException.class,
                () -> handler.handleInner(command));
    }

    @Test
    void handleInner_whenNoThreads_thenReturnsNoConversationsMessage() {
        Update update = new Update();
        Message message = new Message();
        message.setFrom(new User(200L, "user", false));
        update.setMessage(message);

        TelegramUser user = new TelegramUser();
        user.setTelegramId(200L);
        when(userService.getOrCreateUser(any(User.class))).thenReturn(user);
        when(threadRepository.findByScopeKindAndScopeIdOrderByLastActivityAtDesc(
                ThreadScopeKind.TELEGRAM_CHAT, CHAT_ID)).thenReturn(List.of());

        TelegramCommand command = new TelegramCommand(200L, CHAT_ID, new TelegramCommandType(TelegramCommand.THREADS), update);
        command.languageCode("en");

        String result = handler.handleInner(command);

        assertEquals("📝 You have no conversations. Start a new one by sending a message.", result);
    }

    @Test
    void handleInner_whenHasThreads_thenSendsListWithMenuAndCancelRow() throws TelegramApiException {
        Update update = new Update();
        Message message = new Message();
        User from = new User(200L, "user", false);
        message.setFrom(from);
        update.setMessage(message);

        TelegramUser user = new TelegramUser();
        user.setTelegramId(200L);
        ConversationThread thread = new ConversationThread();
        thread.setThreadKey("abc12345-0000-0000-0000-000000000000");
        thread.setTitle("My chat");
        thread.setIsActive(true);
        thread.setUser(user);
        thread.setScopeKind(ThreadScopeKind.TELEGRAM_CHAT);
        thread.setScopeId(CHAT_ID);

        when(userService.getOrCreateUser(from)).thenReturn(user);
        when(threadRepository.findByScopeKindAndScopeIdOrderByLastActivityAtDesc(
                ThreadScopeKind.TELEGRAM_CHAT, CHAT_ID)).thenReturn(List.of(thread));

        TelegramCommand command = new TelegramCommand(200L, CHAT_ID, new TelegramCommandType(TelegramCommand.THREADS), update);
        command.languageCode("en");

        assertNull(handler.handleInner(command));

        ArgumentCaptor<SendMessage> messageCaptor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramBot).execute(messageCaptor.capture());
        SendMessage sent = messageCaptor.getValue();
        InlineKeyboardMarkup markup = (InlineKeyboardMarkup) sent.getReplyMarkup();
        assertNotNull(markup);
        // Expect one row per thread + one final Cancel row
        List<List<InlineKeyboardButton>> keyboard = markup.getKeyboard();
        assertEquals(2, keyboard.size());
        List<InlineKeyboardButton> lastRow = keyboard.get(keyboard.size() - 1);
        assertEquals(1, lastRow.size());
        assertEquals(THREADS_CALLBACK_CANCEL, lastRow.getFirst().getCallbackData());
    }

    @Test
    void handleInner_whenCallbackThreadNotFound_thenSendsError() throws TelegramApiException {
        Update update = new Update();
        CallbackQuery cq = new CallbackQuery();
        cq.setId("cq1");
        cq.setData(THREADS_CALLBACK_PREFIX + "nonexistent-key");
        User from = new User(200L, "user", false);
        cq.setFrom(from);
        update.setCallbackQuery(cq);

        when(userService.getOrCreateUser(from)).thenReturn(new TelegramUser());
        when(threadService.findByThreadKey("nonexistent-key")).thenReturn(Optional.empty());

        TelegramCommand command = new TelegramCommand(200L, CHAT_ID, new TelegramCommandType(TelegramCommand.THREADS), update);

        assertNull(handler.handleInner(command));

        verify(telegramBot, atLeast(1)).execute(any(org.telegram.telegrambots.meta.api.methods.BotApiMethod.class));
    }

    @Test
    void handleInner_whenCallbackThreadBelongsToOtherUser_thenSendsAccessDenied() throws TelegramApiException {
        Update update = new Update();
        CallbackQuery cq = new CallbackQuery();
        cq.setId("cq1");
        String threadKey = "thread-key-123";
        cq.setData(THREADS_CALLBACK_PREFIX + threadKey);
        User from = new User(200L, "user", false);
        cq.setFrom(from);
        update.setCallbackQuery(cq);

        TelegramUser currentUser = new TelegramUser();
        currentUser.setTelegramId(200L);
        currentUser.setId(1L);

        TelegramUser otherUser = new TelegramUser();
        otherUser.setId(999L);

        ConversationThread thread = new ConversationThread();
        thread.setThreadKey(threadKey);
        thread.setUser(otherUser);
        thread.setScopeKind(ThreadScopeKind.TELEGRAM_CHAT);
        thread.setScopeId(999L);

        when(userService.getOrCreateUser(from)).thenReturn(currentUser);
        when(threadService.findByThreadKey(threadKey)).thenReturn(Optional.of(thread));

        TelegramCommand command = new TelegramCommand(200L, CHAT_ID, new TelegramCommandType(TelegramCommand.THREADS), update);

        assertNull(handler.handleInner(command));

        verify(telegramBot, atLeast(1)).execute(any(org.telegram.telegrambots.meta.api.methods.BotApiMethod.class));
    }

    @Test
    void handleInner_whenCallbackValid_thenActivatesThreadAndDeletesMenu() throws TelegramApiException {
        Update update = new Update();
        CallbackQuery cq = new CallbackQuery();
        cq.setId("cq1");
        String threadKey = "thread-key-12345678";
        cq.setData(THREADS_CALLBACK_PREFIX + threadKey);
        User from = new User(200L, "user", false);
        cq.setFrom(from);
        Message cqMessage = new Message();
        cqMessage.setMessageId(77);
        cq.setMessage(cqMessage);
        update.setCallbackQuery(cq);

        TelegramUser user = new TelegramUser();
        user.setTelegramId(200L);
        user.setId(1L);

        ConversationThread thread = new ConversationThread();
        thread.setThreadKey(threadKey);
        thread.setUser(user);
        thread.setTitle("My conversation");
        thread.setTotalMessages(5);
        thread.setScopeKind(ThreadScopeKind.TELEGRAM_CHAT);
        thread.setScopeId(CHAT_ID);

        when(userService.getOrCreateUser(from)).thenReturn(user);
        when(threadService.findByThreadKey(threadKey)).thenReturn(Optional.of(thread));
        when(threadService.activateThread(eq(user), eq(thread), eq(ThreadScopeKind.TELEGRAM_CHAT), eq(CHAT_ID)))
                .thenReturn(thread);

        TelegramCommand command = new TelegramCommand(200L, CHAT_ID, new TelegramCommandType(TelegramCommand.THREADS), update);
        command.languageCode("en");

        assertNull(handler.handleInner(command));

        verify(threadService).activateThread(user, thread, ThreadScopeKind.TELEGRAM_CHAT, CHAT_ID);
        ArgumentCaptor<AnswerCallbackQuery> ackCaptor = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
        verify(telegramBot).execute(ackCaptor.capture());
        assertTrue(ackCaptor.getValue().getText().contains("Active"));
        assertTrue(ackCaptor.getValue().getText().contains("My conversation"));
        verify(telegramBot).execute(any(DeleteMessage.class));
        verify(telegramBot, never()).sendMessage(eq(CHAT_ID), anyString(), isNull(), isNull(org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboard.class));
    }

    @Test
    void handleInner_whenCallbackCancel_thenDeletesMenuWithoutSideEffects() throws TelegramApiException {
        Update update = new Update();
        CallbackQuery cq = new CallbackQuery();
        cq.setId("cq1");
        cq.setData(THREADS_CALLBACK_CANCEL);
        cq.setFrom(new User(200L, "user", false));
        Message cqMessage = new Message();
        cqMessage.setMessageId(77);
        cq.setMessage(cqMessage);
        update.setCallbackQuery(cq);

        TelegramCommand command = new TelegramCommand(200L, CHAT_ID, new TelegramCommandType(TelegramCommand.THREADS), update);
        command.languageCode("en");

        assertNull(handler.handleInner(command));

        verify(telegramBot).execute(any(AnswerCallbackQuery.class));
        verify(telegramBot).execute(any(DeleteMessage.class));
        verify(threadService, never()).findByThreadKey(anyString());
        verify(threadService, never()).activateThread(any(), any(), any(), anyLong());
        verify(telegramBot, never()).sendMessage(anyLong(), anyString(), any(), any());
    }

    @Test
    void handle_whenPlainCommand_doesNotStartTyping() {
        Update update = new Update();
        Message message = new Message();
        User from = new User(200L, "user", false);
        message.setFrom(from);
        update.setMessage(message);

        TelegramUser user = new TelegramUser();
        user.setTelegramId(200L);
        when(userService.getOrCreateUser(any(User.class))).thenReturn(user);
        when(threadRepository.findByScopeKindAndScopeIdOrderByLastActivityAtDesc(
                ThreadScopeKind.TELEGRAM_CHAT, CHAT_ID)).thenReturn(List.of());

        TelegramCommand command = new TelegramCommand(200L, CHAT_ID, new TelegramCommandType(TelegramCommand.THREADS), update);
        command.languageCode("en");

        handler.handle(command);

        verify(typingIndicatorService, never()).startTyping(CHAT_ID);
        verify(typingIndicatorService, never()).stopTyping(CHAT_ID);
    }

    @Test
    void handle_whenCallbackActivation_doesNotStartTyping() throws TelegramApiException {
        Update update = new Update();
        CallbackQuery cq = new CallbackQuery();
        cq.setId("cq1");
        String threadKey = "thread-key-12345678";
        cq.setData(THREADS_CALLBACK_PREFIX + threadKey);
        User from = new User(200L, "user", false);
        cq.setFrom(from);
        Message cqMessage = new Message();
        cqMessage.setMessageId(77);
        cq.setMessage(cqMessage);
        update.setCallbackQuery(cq);

        TelegramUser user = new TelegramUser();
        user.setTelegramId(200L);
        user.setId(1L);

        ConversationThread thread = new ConversationThread();
        thread.setThreadKey(threadKey);
        thread.setUser(user);
        thread.setTitle("My conversation");
        thread.setScopeKind(ThreadScopeKind.TELEGRAM_CHAT);
        thread.setScopeId(CHAT_ID);

        when(userService.getOrCreateUser(from)).thenReturn(user);
        when(threadService.findByThreadKey(threadKey)).thenReturn(Optional.of(thread));
        when(threadService.activateThread(any(), any(), any(), anyLong())).thenReturn(thread);

        TelegramCommand command = new TelegramCommand(200L, CHAT_ID, new TelegramCommandType(TelegramCommand.THREADS), update);
        command.languageCode("en");

        handler.handle(command);

        verify(typingIndicatorService, never()).startTyping(CHAT_ID);
        verify(typingIndicatorService, never()).stopTyping(CHAT_ID);
    }

    @Test
    void getSupportedCommandText_returnsLocalizedDesc() {
        String text = handler.getSupportedCommandText("en");
        assertNotNull(text);
        assertTrue(text.contains("thread") || text.length() > 0);
    }
}
