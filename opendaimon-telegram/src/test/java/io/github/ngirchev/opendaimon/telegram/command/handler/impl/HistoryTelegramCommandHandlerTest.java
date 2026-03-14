package io.github.ngirchev.opendaimon.telegram.command.handler.impl;

import io.github.ngirchev.opendaimon.common.model.OpenDaimonMessage;
import io.github.ngirchev.opendaimon.common.model.ConversationThread;
import io.github.ngirchev.opendaimon.common.model.MessageRole;
import io.github.ngirchev.opendaimon.common.repository.OpenDaimonMessageRepository;
import io.github.ngirchev.opendaimon.common.repository.ConversationThreadRepository;
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

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class HistoryTelegramCommandHandlerTest {

    private static final Long CHAT_ID = 300L;

    @Mock
    private TelegramBot telegramBot;
    @Mock
    private TypingIndicatorService typingIndicatorService;
    @Mock
    private ConversationThreadRepository threadRepository;
    @Mock
    private OpenDaimonMessageRepository messageRepository;
    @Mock
    private TelegramUserService userService;

    private MessageLocalizationService messageLocalizationService;
    private HistoryTelegramCommandHandler handler;

    @BeforeEach
    void setUp() {
        MessageSource messageSource = new ReloadableResourceBundleMessageSource();
        ((ReloadableResourceBundleMessageSource) messageSource).setBasenames(
                "classpath:messages/common", "classpath:messages/telegram");
        ((ReloadableResourceBundleMessageSource) messageSource).setDefaultEncoding("UTF-8");
        messageLocalizationService = new MessageLocalizationService(messageSource);

        ObjectProvider<TelegramBot> botProvider = mock(ObjectProvider.class);
        when(botProvider.getObject()).thenReturn(telegramBot);

        handler = new HistoryTelegramCommandHandler(
                botProvider, typingIndicatorService, messageLocalizationService,
                threadRepository, messageRepository, userService);
    }

    @Test
    void canHandle_whenHistoryCommandAndNoCallback_thenTrue() {
        Update update = new Update();
        update.setMessage(new Message());
        TelegramCommand command = new TelegramCommand(100L, CHAT_ID,
                new TelegramCommandType(TelegramCommand.HISTORY), update);

        assertTrue(handler.canHandle(command));
    }

    @Test
    void canHandle_whenCallback_thenFalse() {
        Update update = new Update();
        update.setCallbackQuery(new org.telegram.telegrambots.meta.api.objects.CallbackQuery());
        update.setMessage(null);
        TelegramCommand command = new TelegramCommand(100L, CHAT_ID,
                new TelegramCommandType(TelegramCommand.HISTORY), update);

        assertFalse(handler.canHandle(command));
    }

    @Test
    void canHandle_whenOtherCommand_thenFalse() {
        Update update = new Update();
        update.setMessage(new Message());
        TelegramCommand command = new TelegramCommand(100L, CHAT_ID,
                new TelegramCommandType(TelegramCommand.NEWTHREAD), update);

        assertFalse(handler.canHandle(command));
    }

    @Test
    void handleInner_whenMessageNull_thenThrows() {
        Update update = new Update();
        update.setMessage(null);
        TelegramCommand command = new TelegramCommand(100L, CHAT_ID,
                new TelegramCommandType(TelegramCommand.HISTORY), update);

        assertThrows(io.github.ngirchev.opendaimon.telegram.command.handler.TelegramCommandHandlerException.class,
                () -> handler.handleInner(command));
    }

    @Test
    void handleInner_whenNoActiveThread_returnsNoConversationMessage() {
        Message message = new Message();
        message.setFrom(new User(100L, "u", false));
        Update update = new Update();
        update.setMessage(message);

        TelegramUser telegramUser = new TelegramUser();
        when(userService.getOrCreateUser(any())).thenReturn(telegramUser);
        when(threadRepository.findMostRecentActiveThread(telegramUser)).thenReturn(Optional.empty());

        TelegramCommand command = new TelegramCommand(100L, CHAT_ID,
                new TelegramCommandType(TelegramCommand.HISTORY), update);

        String result = handler.handleInner(command);

        assertNotNull(result);
        assertTrue(result.contains("no active conversation"));
    }

    @Test
    void handleInner_whenEmptyMessages_returnsEmptyHistoryMessage() {
        Message message = new Message();
        message.setFrom(new User(100L, "u", false));
        Update update = new Update();
        update.setMessage(message);

        TelegramUser telegramUser = new TelegramUser();
        ConversationThread thread = new ConversationThread();
        thread.setThreadKey("thread-key-12");
        when(userService.getOrCreateUser(any())).thenReturn(telegramUser);
        when(threadRepository.findMostRecentActiveThread(telegramUser)).thenReturn(Optional.of(thread));
        when(messageRepository.findByThreadOrderBySequenceNumberAsc(thread)).thenReturn(List.of());

        TelegramCommand command = new TelegramCommand(100L, CHAT_ID,
                new TelegramCommandType(TelegramCommand.HISTORY), update);

        String result = handler.handleInner(command);

        assertNotNull(result);
        assertTrue(result.contains("Conversation history is empty"));
        assertTrue(result.contains("Thread ID:"));
    }

    @Test
    void handleInner_whenHasMessages_returnsFormattedHistory() {
        Message message = new Message();
        message.setFrom(new User(100L, "u", false));
        Update update = new Update();
        update.setMessage(message);

        TelegramUser telegramUser = new TelegramUser();
        ConversationThread thread = new ConversationThread();
        thread.setThreadKey("thread-key-ab");
        when(userService.getOrCreateUser(any())).thenReturn(telegramUser);
        when(threadRepository.findMostRecentActiveThread(telegramUser)).thenReturn(Optional.of(thread));

        OpenDaimonMessage userMsg = new OpenDaimonMessage();
        userMsg.setRole(MessageRole.USER);
        userMsg.setContent("Hello");
        OpenDaimonMessage assistantMsg = new OpenDaimonMessage();
        assistantMsg.setRole(MessageRole.ASSISTANT);
        assistantMsg.setContent("Hi there");
        when(messageRepository.findByThreadOrderBySequenceNumberAsc(thread))
                .thenReturn(List.of(userMsg, assistantMsg));

        TelegramCommand command = new TelegramCommand(100L, CHAT_ID,
                new TelegramCommandType(TelegramCommand.HISTORY), update);

        String result = handler.handleInner(command);

        assertNotNull(result);
        assertTrue(result.contains("Conversation history"));
        assertTrue(result.contains("Hello"));
        assertTrue(result.contains("Hi there"));
        assertTrue(result.contains("Total messages: 2"));
        verify(messageRepository).findByThreadOrderBySequenceNumberAsc(thread);
    }

    @Test
    void getSupportedCommandText_returnsLocalizedMessage() {
        String desc = handler.getSupportedCommandText("en");
        assertNotNull(desc);
    }
}
