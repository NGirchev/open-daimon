package io.github.ngirchev.opendaimon.telegram.service;

import io.github.ngirchev.opendaimon.telegram.TelegramBot;
import io.github.ngirchev.opendaimon.telegram.command.handler.TelegramSupportedCommandProvider;
import io.github.ngirchev.opendaimon.telegram.model.TelegramUser;
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

import static org.assertj.core.api.Assertions.assertThat;
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
    @Mock
    private ObjectProvider<io.github.ngirchev.opendaimon.telegram.service.ChatSettingsService> chatSettingsServiceProvider;

    private TelegramBotMenuService service;

    @BeforeEach
    void setUp() {
        service = new TelegramBotMenuService(telegramBotProvider, commandHandlersProvider, chatSettingsServiceProvider);
    }

    @Test
    void setupBotMenu_whenHandlersReturnCommands_thenCallsSetMyCommandsForEachLanguage() throws TelegramApiException {
        when(telegramBotProvider.getObject()).thenReturn(telegramBot);
        TelegramSupportedCommandProvider h1 = lang -> "/start - Start";
        TelegramSupportedCommandProvider h2 = lang -> "/role - Set role";
        when(commandHandlersProvider.orderedStream()).thenAnswer(inv -> Stream.of(h1, h2));

        service.setupBotMenu();

        ArgumentCaptor<List<BotCommand>> commandsCaptor = ArgumentCaptor.forClass(List.class);
        verify(telegramBot, atLeast(1)).setMyCommands(commandsCaptor.capture(), eq((String) "ru"));
        verify(telegramBot, atLeast(1)).setMyCommands(commandsCaptor.capture(), eq((String) "en"));

        List<BotCommand> commands = commandsCaptor.getAllValues().get(0);
        assertTrue(commands.stream().anyMatch(c -> "/start".equals(c.getCommand()) && "Start".equals(c.getDescription())));
        assertTrue(commands.stream().anyMatch(c -> "/role".equals(c.getCommand()) && "Set role".equals(c.getDescription())));
    }

    @Test
    void setupBotMenu_whenHandlerReturnsCommandWithDescription_thenParsesCorrectly() throws TelegramApiException {
        when(telegramBotProvider.getObject()).thenReturn(telegramBot);
        TelegramSupportedCommandProvider handler = lang -> "/help - Help text";
        when(commandHandlersProvider.orderedStream()).thenAnswer(inv -> Stream.of(handler));

        service.setupBotMenu();

        ArgumentCaptor<List<BotCommand>> captor = ArgumentCaptor.forClass(List.class);
        verify(telegramBot, atLeast(1)).setMyCommands(captor.capture(), any(String.class));
        List<BotCommand> commands = captor.getValue();
        BotCommand help = commands.stream().filter(c -> "/help".equals(c.getCommand())).findFirst().orElse(null);
        assertNotNull(help);
        assertEquals("Help text", help.getDescription());
    }

    @Test
    void setupBotMenu_whenTelegramApiException_thenThrowsRuntimeException() throws TelegramApiException {
        when(telegramBotProvider.getObject()).thenReturn(telegramBot);
        TelegramSupportedCommandProvider handler = lang -> "/start - Start";
        when(commandHandlersProvider.orderedStream()).thenAnswer((Answer<Stream<TelegramSupportedCommandProvider>>) inv -> Stream.of(handler));
        // Stub any language so that the first setMyCommands call throws (Set iteration order is unspecified)
        doThrow(new TelegramApiException("API error")).when(telegramBot).setMyCommands(anyList(), any(String.class));

        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.setupBotMenu());
        assertInstanceOf(TelegramApiException.class, ex.getCause(), "Cause must be TelegramApiException");
        assertNotNull(ex.getMessage());
        assertTrue(ex.getMessage().contains("bot menu"), "Message should mention bot menu");
    }

    @Test
    void setupBotMenu_whenNoCommandsForLanguage_thenSkipsAndContinues() throws TelegramApiException {
        when(telegramBotProvider.getObject()).thenReturn(telegramBot);
        TelegramSupportedCommandProvider handler = lang -> null;
        when(commandHandlersProvider.orderedStream()).thenAnswer((Answer<Stream<TelegramSupportedCommandProvider>>) inv -> Stream.of(handler));

        service.setupBotMenu();

        verify(telegramBot, never()).setMyCommands(anyList(), any(String.class));
    }

    // ── Menu version hash / reconcile ────────────────────────────────────

    @Test
    void shouldComputeStableHashAcrossInvocations() {
        TelegramSupportedCommandProvider h1 = lang -> "/start - Start";
        TelegramSupportedCommandProvider h2 = lang -> "/role - Set role";
        when(commandHandlersProvider.orderedStream()).thenAnswer(inv -> Stream.of(h1, h2));

        String first = service.computeCurrentMenuVersionHash();
        String second = service.computeCurrentMenuVersionHash();

        assertThat(first).isNotBlank().hasSize(64);
        assertThat(second).isEqualTo(first);
    }

    @Test
    void shouldReturnDifferentHashWhenCommandSetChanges() {
        TelegramSupportedCommandProvider h1 = lang -> "/start - Start";
        TelegramSupportedCommandProvider h2 = lang -> "/role - Set role";
        TelegramSupportedCommandProvider h3 = lang -> "/mode - Toggle mode";
        when(commandHandlersProvider.orderedStream())
                .thenAnswer(inv -> Stream.of(h1, h2))
                .thenAnswer(inv -> Stream.of(h1, h2))
                .thenAnswer(inv -> Stream.of(h1, h2, h3))
                .thenAnswer(inv -> Stream.of(h1, h2, h3));

        String before = service.computeCurrentMenuVersionHash();
        String after = service.computeCurrentMenuVersionHash();

        assertThat(before).isNotEqualTo(after);
    }

    @Test
    void shouldReconcileWhenHashIsNull() throws TelegramApiException {
        when(telegramBotProvider.getObject()).thenReturn(telegramBot);
        TelegramSupportedCommandProvider handler = lang -> "/start - Start";
        when(commandHandlersProvider.orderedStream()).thenAnswer(inv -> Stream.of(handler));

        TelegramUser user = new TelegramUser();
        user.setTelegramId(4242L);
        user.setLanguageCode("en");
        user.setMenuVersionHash(null);

        boolean changed = service.reconcileMenuIfStale(user, user.getTelegramId());

        assertThat(changed).isTrue();
        verify(telegramBot).setMyCommands(anyList(), eq(4242L));
        assertThat(user.getMenuVersionHash()).isNotBlank().hasSize(64);
    }

    @Test
    void shouldReconcileWhenHashDiffers() throws TelegramApiException {
        when(telegramBotProvider.getObject()).thenReturn(telegramBot);
        TelegramSupportedCommandProvider handler = lang -> "/start - Start";
        when(commandHandlersProvider.orderedStream()).thenAnswer(inv -> Stream.of(handler));

        TelegramUser user = new TelegramUser();
        user.setTelegramId(4242L);
        user.setLanguageCode("en");
        user.setMenuVersionHash("stale-hash-from-an-older-deployment");

        boolean changed = service.reconcileMenuIfStale(user, user.getTelegramId());

        assertThat(changed).isTrue();
        verify(telegramBot).setMyCommands(anyList(), eq(4242L));
        assertThat(user.getMenuVersionHash())
                .isNotBlank()
                .isNotEqualTo("stale-hash-from-an-older-deployment");
    }

    @Test
    void shouldSkipReconcileWhenHashMatches() throws TelegramApiException {
        TelegramSupportedCommandProvider handler = lang -> "/start - Start";
        when(commandHandlersProvider.orderedStream()).thenAnswer(inv -> Stream.of(handler));

        String currentHash = service.computeCurrentMenuVersionHash();

        TelegramUser user = new TelegramUser();
        user.setTelegramId(4242L);
        user.setLanguageCode("en");
        user.setMenuVersionHash(currentHash);

        boolean changed = service.reconcileMenuIfStale(user, user.getTelegramId());

        assertThat(changed).isFalse();
        verify(telegramBot, never()).setMyCommands(anyList(), any(Long.class));
        assertThat(user.getMenuVersionHash()).isEqualTo(currentHash);
    }

    @Test
    void shouldReconcileWithDefaultLanguageWhenLanguageCodeIsNull() throws TelegramApiException {
        when(telegramBotProvider.getObject()).thenReturn(telegramBot);
        TelegramSupportedCommandProvider h1 = lang -> "/start - Start";
        when(commandHandlersProvider.orderedStream()).thenAnswer(inv -> Stream.of(h1));

        TelegramUser user = new TelegramUser();
        user.setTelegramId(4242L);
        user.setLanguageCode(null);
        user.setMenuVersionHash(null);

        boolean changed = service.reconcileMenuIfStale(user, user.getTelegramId());

        assertThat(changed).isTrue();
        verify(telegramBot).setMyCommands(anyList(), eq(4242L));
        assertThat(user.getMenuVersionHash()).isNotNull();
    }
}
