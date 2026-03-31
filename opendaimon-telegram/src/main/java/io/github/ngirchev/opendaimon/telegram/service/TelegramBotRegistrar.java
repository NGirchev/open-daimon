package io.github.ngirchev.opendaimon.telegram.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.methods.GetMe;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;
import io.github.ngirchev.opendaimon.telegram.TelegramBot;

@Slf4j
@RequiredArgsConstructor
public class TelegramBotRegistrar implements ApplicationListener<ApplicationReadyEvent> {

    private final TelegramBot telegramBot;
    private final ObjectProvider<TelegramBotMenuService> menuServiceProvider;

    @Override
    public void onApplicationEvent(@org.springframework.lang.NonNull ApplicationReadyEvent event) {
        try {
            TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
            botsApi.registerBot(telegramBot);
            log.info("Telegram bot '{}' successfully registered", telegramBot.getBotUsername());

            // Log bot capabilities from Telegram API
            try {
                User me = telegramBot.execute(new GetMe());
                log.info("Bot info from Telegram API: username={}, canJoinGroups={}, canReadAllGroupMessages={}, supportsInlineQueries={}",
                        me.getUserName(), me.getCanJoinGroups(), me.getCanReadAllGroupMessages(), me.getSupportInlineQueries());
            } catch (TelegramApiException ex) {
                log.warn("Failed to fetch bot info via getMe: {}", ex.getMessage());
            }

            // Set command menu after bot registration
            menuServiceProvider.ifAvailable(TelegramBotMenuService::setupBotMenu);
        } catch (TelegramApiException e) {
            log.error("Application can't start, telegram bot is not accessible", e);
            throw new RuntimeException("Failed to register Telegram bot", e);
        }
    }
}
