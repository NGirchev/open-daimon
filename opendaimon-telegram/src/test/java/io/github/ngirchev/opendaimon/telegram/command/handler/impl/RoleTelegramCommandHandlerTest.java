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
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import io.github.ngirchev.opendaimon.common.config.CoreCommonProperties;
import io.github.ngirchev.opendaimon.common.model.AssistantRole;
import io.github.ngirchev.opendaimon.common.service.MessageLocalizationService;
import io.github.ngirchev.opendaimon.telegram.TelegramBot;
import io.github.ngirchev.opendaimon.telegram.command.TelegramCommand;
import io.github.ngirchev.opendaimon.common.command.ICommand;
import io.github.ngirchev.opendaimon.telegram.command.TelegramCommandType;
import io.github.ngirchev.opendaimon.telegram.model.TelegramUser;
import io.github.ngirchev.opendaimon.telegram.service.TelegramUserService;
import io.github.ngirchev.opendaimon.telegram.service.TypingIndicatorService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RoleTelegramCommandHandlerTest {

    private static final Long CHAT_ID = 100L;
    private static final String ROLE_CALLBACK_PREFIX = "ROLE_";

    @Mock
    private TelegramBot telegramBot;
    @Mock
    private TypingIndicatorService typingIndicatorService;
    @Mock
    private TelegramUserService telegramUserService;

    private MessageLocalizationService messageLocalizationService;
    private CoreCommonProperties coreCommonProperties;
    private RoleTelegramCommandHandler handler;

    @BeforeEach
    void setUp() {
        MessageSource messageSource = new ReloadableResourceBundleMessageSource();
        ((ReloadableResourceBundleMessageSource) messageSource).setBasenames(
                "classpath:messages/common", "classpath:messages/telegram");
        ((ReloadableResourceBundleMessageSource) messageSource).setDefaultEncoding("UTF-8");
        messageLocalizationService = new MessageLocalizationService(messageSource);

        coreCommonProperties = new CoreCommonProperties();
        coreCommonProperties.setAssistantRole("You are a helpful assistant.");

        ObjectProvider<TelegramBot> botProvider = mock(ObjectProvider.class);
        when(botProvider.getObject()).thenReturn(telegramBot);

        handler = new RoleTelegramCommandHandler(botProvider, typingIndicatorService, messageLocalizationService,
                telegramUserService, coreCommonProperties);
    }

    @Test
    void canHandle_whenTelegramCommandWithRoleCommand_thenTrue() {
        Update update = new Update();
        Message message = new Message();
        message.setFrom(new User(200L, "user", false));
        update.setMessage(message);

        TelegramCommand command = new TelegramCommand(200L, CHAT_ID, new TelegramCommandType(TelegramCommand.ROLE), update);
        assertTrue(handler.canHandle(command));
    }

    @Test
    void canHandle_whenCallbackQueryWithRolePrefix_thenTrue() {
        Update update = new Update();
        CallbackQuery cq = new CallbackQuery();
        cq.setData(ROLE_CALLBACK_PREFIX + "DEFAULT");
        cq.setFrom(new User(200L, "user", false));
        update.setCallbackQuery(cq);

        TelegramCommand command = new TelegramCommand(200L, CHAT_ID, new TelegramCommandType(TelegramCommand.ROLE), update);
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
        TelegramCommand command = new TelegramCommand(200L, CHAT_ID, new TelegramCommandType(TelegramCommand.LANGUAGE), update);
        assertFalse(handler.canHandle(command));
    }

    @Test
    void handleInner_whenMessageNull_thenThrows() {
        Update update = new Update();
        update.setMessage(null);
        TelegramCommand command = new TelegramCommand(200L, CHAT_ID, new TelegramCommandType(TelegramCommand.ROLE), update);

        assertThrows(TelegramCommandHandlerException.class,
                () -> handler.handleInner(command));
    }

    @Test
    void handleInner_whenEmptyUserText_thenShowsCurrentRoleAndMenu() throws Exception {
        Update update = new Update();
        Message message = new Message();
        message.setMessageId(1);
        User from = new User(200L, "user", false);
        message.setFrom(from);
        update.setMessage(message);

        TelegramUser telegramUser = new TelegramUser();
        telegramUser.setTelegramId(200L);
        AssistantRole role = new AssistantRole();
        role.setId(1L);
        role.setVersion(1);
        role.setContent("Default role content");
        telegramUser.setCurrentAssistantRole(role);

        when(telegramUserService.getOrCreateAssistantRole(any(TelegramUser.class), eq("You are a helpful assistant.")))
                .thenReturn(role);

        when(telegramUserService.getOrCreateUser(from)).thenReturn(telegramUser);

        TelegramCommand command = new TelegramCommand(200L, CHAT_ID, new TelegramCommandType(TelegramCommand.ROLE), update, "   ");

        assertNull(handler.handleInner(command));

        verify(telegramUserService).getOrCreateUser(from);
        verify(telegramUserService).getOrCreateAssistantRole(any(TelegramUser.class), eq("You are a helpful assistant."));
        verify(telegramBot, atLeast(1)).sendMessage(eq(CHAT_ID), anyString(), any());
    }

    @Test
    void handleInner_whenUserTextProvided_thenUpdatesRoleAndSendsConfirmation() throws Exception {
        Update update = new Update();
        Message message = new Message();
        message.setMessageId(1);
        User from = new User(200L, "user", false);
        message.setFrom(from);
        update.setMessage(message);

        TelegramUser telegramUser = new TelegramUser();
        telegramUser.setTelegramId(200L);
        when(telegramUserService.getOrCreateUser(from)).thenReturn(telegramUser);
        when(telegramUserService.updateAssistantRole(eq(from), eq("New role text"))).thenReturn(telegramUser);

        TelegramCommand command = new TelegramCommand(200L, CHAT_ID, new TelegramCommandType(TelegramCommand.ROLE), update, "New role text");

        assertNull(handler.handleInner(command));

        verify(telegramUserService).getOrCreateUser(from);
        verify(telegramUserService).updateAssistantRole(from, "New role text");
        verify(telegramBot).clearStatus(200L);
        verify(telegramBot).sendMessage(eq(CHAT_ID), contains("Assistant role updated successfully"), any());
    }

    @Test
    void handleInner_whenCallbackCustom_thenUpdatesSessionAndSendsPrompt() throws Exception {
        Update update = new Update();
        CallbackQuery cq = new CallbackQuery();
        cq.setId("cq1");
        cq.setData(ROLE_CALLBACK_PREFIX + "CUSTOM");
        User from = new User(200L, "user", false);
        cq.setFrom(from);
        update.setCallbackQuery(cq);

        TelegramUser telegramUser = new TelegramUser();
        telegramUser.setTelegramId(200L);
        when(telegramUserService.getOrCreateUser(from)).thenReturn(telegramUser);

        TelegramCommand command = new TelegramCommand(200L, CHAT_ID, new TelegramCommandType(TelegramCommand.ROLE), update);

        assertNull(handler.handleInner(command));

        verify(telegramUserService).updateUserSession(telegramUser, TelegramCommand.ROLE);
        verify(telegramBot, atLeast(1)).execute(any(org.telegram.telegrambots.meta.api.methods.BotApiMethod.class));
    }

    @Test
    void handleInner_whenCallbackPreset_thenUpdatesRoleAndSendsConfirmation() throws Exception {
        Update update = new Update();
        CallbackQuery cq = new CallbackQuery();
        cq.setId("cq1");
        cq.setData(ROLE_CALLBACK_PREFIX + "DEFAULT");
        User from = new User(200L, "user", false);
        cq.setFrom(from);
        update.setCallbackQuery(cq);

        TelegramUser telegramUser = new TelegramUser();
        telegramUser.setTelegramId(200L);
        when(telegramUserService.getOrCreateUser(from)).thenReturn(telegramUser);
        when(telegramUserService.updateAssistantRole(eq(from), anyString())).thenReturn(telegramUser);

        TelegramCommand command = new TelegramCommand(200L, CHAT_ID, new TelegramCommandType(TelegramCommand.ROLE), update);

        assertNull(handler.handleInner(command));

        verify(telegramUserService).updateAssistantRole(from, "You are a helpful assistant.");
        verify(telegramBot).clearStatus(200L);
        verify(telegramBot, atLeast(1)).execute(any(org.telegram.telegrambots.meta.api.methods.BotApiMethod.class));
    }

    @Test
    void handleInner_whenCallbackUnknownPreset_thenSendsError() throws Exception {
        Update update = new Update();
        CallbackQuery cq = new CallbackQuery();
        cq.setId("cq1");
        cq.setData(ROLE_CALLBACK_PREFIX + "UNKNOWN_KEY");
        User from = new User(200L, "user", false);
        cq.setFrom(from);
        update.setCallbackQuery(cq);

        when(telegramUserService.getOrCreateUser(from)).thenReturn(new TelegramUser());

        TelegramCommand command = new TelegramCommand(200L, CHAT_ID, new TelegramCommandType(TelegramCommand.ROLE), update);

        assertNull(handler.handleInner(command));

        verify(telegramBot, atLeast(1)).execute(any(org.telegram.telegrambots.meta.api.methods.BotApiMethod.class));
    }

    @Test
    void handleInner_whenCallbackInvalidData_thenThrows() {
        Update update = new Update();
        CallbackQuery cq = new CallbackQuery();
        cq.setId("cq1");
        cq.setData("OTHER_");
        cq.setFrom(new User(200L, "user", false));
        update.setCallbackQuery(cq);

        TelegramCommand command = new TelegramCommand(200L, CHAT_ID, new TelegramCommandType(TelegramCommand.ROLE), update);

        assertThrows(TelegramCommandHandlerException.class,
                () -> handler.handleInner(command));
    }

    @Test
    void getSupportedCommandText_returnsLocalizedDesc() {
        String text = handler.getSupportedCommandText("en");
        assertNotNull(text);
        assertTrue(text.contains("role") || text.length() > 0);
    }
}
