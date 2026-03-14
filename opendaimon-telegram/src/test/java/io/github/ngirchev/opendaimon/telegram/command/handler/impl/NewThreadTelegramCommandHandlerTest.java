package io.github.ngirchev.opendaimon.telegram.command.handler.impl;

import io.github.ngirchev.opendaimon.common.model.ConversationThread;
import io.github.ngirchev.opendaimon.common.repository.ConversationThreadRepository;
import io.github.ngirchev.opendaimon.common.service.ConversationThreadService;
import io.github.ngirchev.opendaimon.common.service.MessageLocalizationService;
import io.github.ngirchev.opendaimon.telegram.TelegramBot;
import io.github.ngirchev.opendaimon.telegram.command.TelegramCommand;
import io.github.ngirchev.opendaimon.telegram.command.TelegramCommandType;
import io.github.ngirchev.opendaimon.telegram.model.TelegramUser;
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
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NewThreadTelegramCommandHandlerTest {

    private static final Long CHAT_ID = 200L;

    @Mock
    private TelegramBot telegramBot;
    @Mock
    private TypingIndicatorService typingIndicatorService;
    @Mock
    private ConversationThreadService threadService;
    @Mock
    private ConversationThreadRepository threadRepository;
    @Mock
    private TelegramUserService userService;

    private MessageLocalizationService messageLocalizationService;
    private NewThreadTelegramCommandHandler handler;

    @BeforeEach
    void setUp() {
        MessageSource messageSource = new ReloadableResourceBundleMessageSource();
        ((ReloadableResourceBundleMessageSource) messageSource).setBasenames(
                "classpath:messages/common", "classpath:messages/telegram");
        ((ReloadableResourceBundleMessageSource) messageSource).setDefaultEncoding("UTF-8");
        messageLocalizationService = new MessageLocalizationService(messageSource);

        ObjectProvider<TelegramBot> botProvider = mock(ObjectProvider.class);
        when(botProvider.getObject()).thenReturn(telegramBot);

        handler = new NewThreadTelegramCommandHandler(
                botProvider, typingIndicatorService, messageLocalizationService,
                threadService, threadRepository, userService);
    }

    @Test
    void canHandle_whenNewThreadCommandAndNoCallback_thenTrue() {
        Update update = new Update();
        Message message = new Message();
        message.setFrom(new User(100L, "u", false));
        update.setMessage(message);

        TelegramCommand command = new TelegramCommand(100L, CHAT_ID,
                new TelegramCommandType(TelegramCommand.NEWTHREAD), update);

        assertTrue(handler.canHandle(command));
    }

    @Test
    void canHandle_whenCallbackQuery_thenFalse() {
        Update update = new Update();
        update.setCallbackQuery(new org.telegram.telegrambots.meta.api.objects.CallbackQuery());
        update.setMessage(null);

        TelegramCommand command = new TelegramCommand(100L, CHAT_ID,
                new TelegramCommandType(TelegramCommand.NEWTHREAD), update);

        assertFalse(handler.canHandle(command));
    }

    @Test
    void canHandle_whenOtherCommand_thenFalse() {
        Update update = new Update();
        update.setMessage(new Message());

        TelegramCommand command = new TelegramCommand(100L, CHAT_ID,
                new TelegramCommandType(TelegramCommand.THREADS), update);

        assertFalse(handler.canHandle(command));
    }

    @Test
    void handleInner_whenMessageNull_thenThrows() {
        Update update = new Update();
        update.setMessage(null);
        TelegramCommand command = new TelegramCommand(100L, CHAT_ID,
                new TelegramCommandType(TelegramCommand.NEWTHREAD), update);

        assertThrows(io.github.ngirchev.opendaimon.telegram.command.handler.TelegramCommandHandlerException.class,
                () -> handler.handleInner(command));
    }

    @Test
    void handleInner_whenNoPreviousThread_createsNewAndReturnsMessage() {
        User from = new User(100L, "user", false);
        Message message = new Message();
        message.setFrom(from);
        Update update = new Update();
        update.setMessage(message);

        TelegramUser telegramUser = new TelegramUser();
        telegramUser.setTelegramId(100L);
        when(userService.getOrCreateUser(from)).thenReturn(telegramUser);
        when(threadRepository.findMostRecentActiveThread(telegramUser)).thenReturn(Optional.empty());

        ConversationThread newThread = new ConversationThread();
        newThread.setThreadKey("thread-key-abcdef12");
        when(threadService.createNewThread(telegramUser)).thenReturn(newThread);

        TelegramCommand command = new TelegramCommand(100L, CHAT_ID,
                new TelegramCommandType(TelegramCommand.NEWTHREAD), update);

        String result = handler.handleInner(command);

        assertNotNull(result);
        assertTrue(result.contains("New conversation started"));
        assertTrue(result.contains("Thread ID:"));
        verify(threadService).createNewThread(telegramUser);
        verify(threadRepository).findMostRecentActiveThread(telegramUser);
    }

    @Test
    void handleInner_whenHasPreviousThread_closesAndCreatesNew() {
        User from = new User(100L, "user", false);
        Message message = new Message();
        message.setFrom(from);
        Update update = new Update();
        update.setMessage(message);

        TelegramUser telegramUser = new TelegramUser();
        telegramUser.setTelegramId(100L);
        when(userService.getOrCreateUser(from)).thenReturn(telegramUser);

        ConversationThread oldThread = new ConversationThread();
        oldThread.setId(1L);
        oldThread.setThreadKey("old-key");
        when(threadRepository.findMostRecentActiveThread(telegramUser)).thenReturn(Optional.of(oldThread));

        ConversationThread newThread = new ConversationThread();
        newThread.setThreadKey("new-thread-key-12");
        when(threadService.createNewThread(telegramUser)).thenReturn(newThread);

        TelegramCommand command = new TelegramCommand(100L, CHAT_ID,
                new TelegramCommandType(TelegramCommand.NEWTHREAD), update);

        String result = handler.handleInner(command);

        assertNotNull(result);
        assertTrue(result.contains("Previous conversation history was saved"));
        verify(threadService).closeThread(oldThread);
        verify(threadService).createNewThread(telegramUser);
    }

    @Test
    void getSupportedCommandText_returnsLocalizedMessage() {
        String desc = handler.getSupportedCommandText("en");
        assertNotNull(desc);
    }
}
