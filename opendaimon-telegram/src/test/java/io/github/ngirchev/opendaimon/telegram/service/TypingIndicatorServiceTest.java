package io.github.ngirchev.opendaimon.telegram.service;

import io.github.ngirchev.opendaimon.telegram.TelegramBot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TypingIndicatorServiceTest {

    @Mock
    private ObjectProvider<TelegramBot> telegramBotProvider;
    @Mock
    private ScheduledExecutorService scheduledExecutorService;
    @Mock
    private TelegramBot telegramBot;
    @Mock
    private ScheduledFuture<?> scheduledFuture;

    private TypingIndicatorService service;

    @BeforeEach
    void setUp() {
        service = new TypingIndicatorService(telegramBotProvider, scheduledExecutorService);
        when(telegramBotProvider.getObject()).thenReturn(telegramBot);
    }

    @SuppressWarnings("unchecked")
    private void stubScheduleAtFixedRate() {
        when(scheduledExecutorService.scheduleAtFixedRate(
                any(Runnable.class), eq(2L), eq(2L), eq(TimeUnit.SECONDS)))
                .thenReturn((ScheduledFuture) scheduledFuture);
    }

    @Test
    void startTyping_sendsIndicatorAndSchedules() throws TelegramApiException {
        stubScheduleAtFixedRate();

        service.startTyping(12345L);

        verify(telegramBot).showTyping(12345L);
        verify(scheduledExecutorService).scheduleAtFixedRate(
                any(Runnable.class), eq(2L), eq(2L), eq(TimeUnit.SECONDS));
    }

    @Test
    void stopTyping_whenActive_cancelsFuture() throws TelegramApiException {
        stubScheduleAtFixedRate();

        service.startTyping(999L);
        service.stopTyping(999L);

        verify(scheduledFuture).cancel(false);
    }

    @Test
    void startTyping_whenAlreadyActive_stopsPreviousAndStartsNew() throws TelegramApiException {
        stubScheduleAtFixedRate();

        service.startTyping(100L);
        service.startTyping(100L);

        verify(scheduledFuture).cancel(false);
        verify(scheduledExecutorService, times(2)).scheduleAtFixedRate(
                any(Runnable.class), eq(2L), eq(2L), eq(TimeUnit.SECONDS));
    }

    @Test
    void stopTyping_whenNeverStarted_doesNotThrow() {
        assertDoesNotThrow(() -> service.stopTyping(42L));
    }

    @Test
    void whenShowTypingThrows_doesNotPropagate() throws TelegramApiException {
        doThrow(new TelegramApiException("Network error")).when(telegramBot).showTyping(200L);
        stubScheduleAtFixedRate();

        service.startTyping(200L);

        verify(telegramBot).showTyping(200L);
        verify(scheduledExecutorService).scheduleAtFixedRate(
                any(Runnable.class), eq(2L), eq(2L), eq(TimeUnit.SECONDS));
    }
}
