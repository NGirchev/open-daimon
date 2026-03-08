package io.github.ngirchev.aibot.telegram.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import io.github.ngirchev.aibot.telegram.TelegramBot;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Service for Telegram typing indicator.
 * Periodically sends typing indicator while user command is being processed.
 */
@Slf4j
@RequiredArgsConstructor
public class TypingIndicatorService {

    private final ObjectProvider<TelegramBot> telegramBotProvider;
    private final ScheduledExecutorService scheduledExecutorService;

    /**
     * Typing indicator send interval in seconds.
     * Telegram API requires updating indicator every 4-5 seconds.
     */
    private static final long TYPING_INDICATOR_INTERVAL_SECONDS = 2;

    /**
     * Store of active typing indicator tasks per user.
     */
    private final ConcurrentHashMap<Long, ScheduledFuture<?>> activeTypingIndicators = new ConcurrentHashMap<>();

    /**
     * Starts periodic typing indicator for user.
     * If user already has active indicator, it is stopped and replaced.
     *
     * @param userId user identifier (chatId)
     */
    public void startTyping(Long userId) {
        // Stop previous indicator if any
        stopTyping(userId);

        // Send indicator immediately
        sendTypingIndicator(userId);

        // Start periodic send
        ScheduledFuture<?> future = scheduledExecutorService.scheduleAtFixedRate(
                () -> sendTypingIndicator(userId),
                TYPING_INDICATOR_INTERVAL_SECONDS,
                TYPING_INDICATOR_INTERVAL_SECONDS,
                TimeUnit.SECONDS
        );

        activeTypingIndicators.put(userId, future);
        log.debug("Started typing indicator for user {}", userId);
    }

    /**
     * Stops periodic typing indicator for user.
     *
     * @param userId user identifier (chatId)
     */
    public void stopTyping(Long userId) {
        ScheduledFuture<?> future = activeTypingIndicators.remove(userId);
        if (future != null) {
            future.cancel(false);
            log.debug("Stopped typing indicator for user {}", userId);
        }
    }

    /**
     * Sends typing indicator to user.
     *
     * @param userId user identifier (chatId)
     */
    private void sendTypingIndicator(Long userId) {
        try {
            telegramBotProvider.getObject().showTyping(userId);
        } catch (TelegramApiException e) {
            log.warn("Failed to send typing indicator for user {}: {}", userId, e.getMessage());
            // Stop indicator on error
            stopTyping(userId);
        } catch (Exception e) {
            log.error("Unexpected error while sending typing indicator for user {}", userId, e);
            stopTyping(userId);
        }
    }
}

