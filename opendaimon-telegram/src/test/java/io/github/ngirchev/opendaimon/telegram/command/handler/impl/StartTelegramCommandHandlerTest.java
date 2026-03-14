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
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
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
class StartTelegramCommandHandlerTest {

    private static final Long CHAT_ID = 100L;

    @Mock
    private TelegramBot telegramBot;
    @Mock
    private TypingIndicatorService typingIndicatorService;
    @Mock
    private ObjectProvider<TelegramSupportedCommandProvider> handlersProvider;

    private MessageLocalizationService messageLocalizationService;
    private StartTelegramCommandHandler handler;

    @BeforeEach
    void setUp() {
        MessageSource messageSource = new ReloadableResourceBundleMessageSource();
        ((ReloadableResourceBundleMessageSource) messageSource).setBasenames(
                "classpath:messages/common", "classpath:messages/telegram");
        ((ReloadableResourceBundleMessageSource) messageSource).setDefaultEncoding("UTF-8");
        messageLocalizationService = new MessageLocalizationService(messageSource);

        ObjectProvider<TelegramBot> botProvider = mock(ObjectProvider.class);
        when(botProvider.getObject()).thenReturn(telegramBot);

        handler = new StartTelegramCommandHandler(botProvider, typingIndicatorService, messageLocalizationService, handlersProvider);
    }

    @Test
    void canHandle_whenStartCommandAndNoCallback_thenTrue() {
        Update update = new Update();
        Message message = new Message();
        message.setFrom(new User(200L, "user", false));
        update.setMessage(message);

        TelegramCommand command = new TelegramCommand(200L, CHAT_ID, new TelegramCommandType(TelegramCommand.START), update);
        assertTrue(handler.canHandle(command));
    }

    @Test
    void canHandle_whenStartCommandWithCallback_thenFalse() {
        Update update = new Update();
        update.setCallbackQuery(new CallbackQuery());
        update.setMessage(null);

        TelegramCommand command = new TelegramCommand(200L, CHAT_ID, new TelegramCommandType(TelegramCommand.START), update);
        assertFalse(handler.canHandle(command));
    }

    @Test
    void canHandle_whenNotTelegramCommand_thenFalse() {
        @SuppressWarnings("unchecked")
        ICommand<TelegramCommandType> other = mock(ICommand.class);
        assertFalse(handler.canHandle(other));
    }

    @Test
    void canHandle_whenOtherCommandType_thenFalse() {
        Update update = new Update();
        update.setMessage(new Message());
        TelegramCommand command = new TelegramCommand(200L, CHAT_ID, new TelegramCommandType(TelegramCommand.ROLE), update);
        assertFalse(handler.canHandle(command));
    }

    @Test
    void handleInner_returnsWelcomeAndCommandsFromOtherHandlers() {
        TelegramSupportedCommandProvider otherHandler = lang -> "/role - Set role";
        when(handlersProvider.orderedStream()).thenReturn(Stream.of(otherHandler));

        Update update = new Update();
        update.setMessage(new Message());
        TelegramCommand command = new TelegramCommand(200L, CHAT_ID, new TelegramCommandType(TelegramCommand.START), update, "en");

        String result = handler.handleInner(command);

        assertNotNull(result);
        assertTrue(result.contains("/role") || result.contains("Set role"));
    }

    @Test
    void getSupportedCommandText_returnsNull() {
        assertNull(handler.getSupportedCommandText("en"));
    }
}
