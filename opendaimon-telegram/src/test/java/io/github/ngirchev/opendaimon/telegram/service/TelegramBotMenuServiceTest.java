package io.github.ngirchev.opendaimon.telegram.service;

import io.github.ngirchev.opendaimon.telegram.TelegramBot;
import io.github.ngirchev.opendaimon.telegram.command.handler.TelegramSupportedCommandProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.ObjectProvider;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for TelegramBotMenuService.
 */
@ExtendWith(MockitoExtension.class)
class TelegramBotMenuServiceTest {

    @Mock
    private TelegramBot telegramBot;
    @Mock
    private ObjectProvider<TelegramBot> telegramBotProvider;
    @Mock
    private ObjectProvider<TelegramSupportedCommandProvider> commandHandlersProvider;

    private TelegramBotMenuService service;

    @BeforeEach
    void setUp() {
        when(telegramBotProvider.getObject()).thenReturn(telegramBot);
        service = new TelegramBotMenuService(telegramBotProvider, commandHandlersProvider);
    }

    @Test
    void setupBotMenu_whenHandlersReturnCommands_thenCallsSetMyCommandsForEachLanguage() throws TelegramApiException {
        TelegramSupportedCommandProvider h1 = lang -> "/start - Start";
        TelegramSupportedCommandProvider h2 = lang -> "/role - Set role";
        when(commandHandlersProvider.orderedStream()).thenAnswer(inv -> Stream.of(h1, h2));

        service.setupBotMenu();

        ArgumentCaptor<List<BotCommand>> commandsCaptor = ArgumentCaptor.forClass(List.class);
        verify(telegramBot, atLeast(1)).setMyCommands(commandsCaptor.capture(), eq("ru"));
        verify(telegramBot, atLeast(1)).setMyCommands(commandsCaptor.capture(), eq("en"));

        List<BotCommand> commands = commandsCaptor.getAllValues().get(0);
        assertTrue(commands.stream().anyMatch(c -> "/start".equals(c.getCommand()) && "Start".equals(c.getDescription())));
        assertTrue(commands.stream().anyMatch(c -> "/role".equals(c.getCommand()) && "Set role".equals(c.getDescription())));
    }

    @Test
    void setupBotMenu_whenHandlerReturnsCommandWithDescription_thenParsesCorrectly() throws TelegramApiException {
        TelegramSupportedCommandProvider handler = lang -> "/help - Help text";
        when(commandHandlersProvider.orderedStream()).thenAnswer(inv -> Stream.of(handler));

        service.setupBotMenu();

        ArgumentCaptor<List<BotCommand>> captor = ArgumentCaptor.forClass(List.class);
        verify(telegramBot, atLeast(1)).setMyCommands(captor.capture(), any());
        List<BotCommand> commands = captor.getValue();
        BotCommand help = commands.stream().filter(c -> "/help".equals(c.getCommand())).findFirst().orElse(null);
        assertNotNull(help);
        assertEquals("Help text", help.getDescription());
    }

    @Test
    void setupBotMenu_whenTelegramApiException_thenThrowsRuntimeException() throws TelegramApiException {
        TelegramSupportedCommandProvider handler = lang -> "/start - Start";
        when(commandHandlersProvider.orderedStream()).thenAnswer((Answer<Stream<TelegramSupportedCommandProvider>>) inv -> Stream.of(handler));
        // Stub any language so that the first setMyCommands call throws (Set iteration order is unspecified)
        doThrow(new TelegramApiException("API error")).when(telegramBot).setMyCommands(anyList(), any(String.class));

        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.setupBotMenu());
        assertTrue(ex.getCause() instanceof TelegramApiException, "Cause must be TelegramApiException");
        assertNotNull(ex.getMessage());
        assertTrue(ex.getMessage().contains("bot menu"), "Message should mention bot menu");
    }

    @Test
    void setupBotMenu_whenNoCommandsForLanguage_thenSkipsAndContinues() throws TelegramApiException {
        TelegramSupportedCommandProvider handler = lang -> null;
        when(commandHandlersProvider.orderedStream()).thenAnswer((Answer<Stream<TelegramSupportedCommandProvider>>) inv -> Stream.of(handler));

        service.setupBotMenu();

        verify(telegramBot, never()).setMyCommands(anyList(), any());
    }
}
