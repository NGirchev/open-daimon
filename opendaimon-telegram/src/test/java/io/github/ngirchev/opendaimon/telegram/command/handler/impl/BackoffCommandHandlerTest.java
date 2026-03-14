package io.github.ngirchev.opendaimon.telegram.command.handler.impl;

import io.github.ngirchev.opendaimon.common.command.ICommand;
import io.github.ngirchev.opendaimon.common.service.MessageLocalizationService;
import io.github.ngirchev.opendaimon.telegram.TelegramBot;
import io.github.ngirchev.opendaimon.telegram.command.TelegramCommand;
import io.github.ngirchev.opendaimon.telegram.command.TelegramCommandType;
import io.github.ngirchev.opendaimon.telegram.command.handler.TelegramSupportedCommandProvider;
import io.github.ngirchev.opendaimon.telegram.service.TypingIndicatorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.MessageSource;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.springframework.core.Ordered;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BackoffCommandHandlerTest {

    private static final Long CHAT_ID = 100L;

    @Mock
    private TelegramBot telegramBot;
    @Mock
    private TypingIndicatorService typingIndicatorService;
    @Mock
    private ObjectProvider<TelegramSupportedCommandProvider> handlersProvider;

    private MessageLocalizationService messageLocalizationService;
    private BackoffCommandHandler handler;

    @BeforeEach
    void setUp() {
        MessageSource messageSource = new ReloadableResourceBundleMessageSource();
        ((ReloadableResourceBundleMessageSource) messageSource).setBasenames(
                "classpath:messages/common", "classpath:messages/telegram");
        ((ReloadableResourceBundleMessageSource) messageSource).setDefaultEncoding("UTF-8");
        messageLocalizationService = new MessageLocalizationService(messageSource);

        ObjectProvider<TelegramBot> botProvider = mock(ObjectProvider.class);
        when(botProvider.getObject()).thenReturn(telegramBot);

        handler = new BackoffCommandHandler(botProvider, typingIndicatorService, messageLocalizationService, handlersProvider);
    }

    @Test
    void priority_returnsLowestPrecedence() {
        assertEquals(Ordered.LOWEST_PRECEDENCE, handler.priority());
    }

    @Test
    void canHandle_whenTelegramCommand_thenTrue() {
        Update update = new Update();
        update.setMessage(new Message());
        TelegramCommand command = new TelegramCommand(200L, CHAT_ID, new TelegramCommandType(TelegramCommand.START), update);
        assertTrue(handler.canHandle(command));
    }

    @Test
    void canHandle_whenNotTelegramCommand_thenFalse() {
        @SuppressWarnings("unchecked")
        ICommand<TelegramCommandType> other = mock(ICommand.class);
        assertFalse(handler.canHandle(other));
    }

    @Test
    void handleInner_clearsStatusAndReturnsWelcomeWithCommands() {
        when(handlersProvider.orderedStream()).thenReturn(Stream.of(lang -> "/start - Start"));
        Update update = new Update();
        update.setMessage(new Message());
        TelegramCommand command = new TelegramCommand(200L, CHAT_ID, new TelegramCommandType(TelegramCommand.START), update, "en");

        String result = handler.handleInner(command);

        verify(telegramBot).clearStatus(200L);
        assertNotNull(result);
        assertTrue(result.contains("/start") || result.contains("Start"));
    }

    @Test
    void getSupportedCommandText_returnsNull() {
        assertNull(handler.getSupportedCommandText("en"));
    }
}
