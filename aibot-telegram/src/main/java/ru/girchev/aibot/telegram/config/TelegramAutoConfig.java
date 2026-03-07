package ru.girchev.aibot.telegram.config;

import org.apache.http.client.config.RequestConfig;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.telegram.telegrambots.bots.DefaultBotOptions;
import ru.girchev.aibot.bulkhead.config.BulkHeadAutoConfig;
import ru.girchev.aibot.telegram.TelegramBot;
import ru.girchev.aibot.telegram.service.TelegramBotMenuService;
import ru.girchev.aibot.telegram.service.TelegramBotRegistrar;
import ru.girchev.aibot.telegram.service.TelegramCommandSyncService;
import ru.girchev.aibot.telegram.service.TelegramFileService;
import ru.girchev.aibot.telegram.service.TelegramUserService;

/**
 * Автоконфигурация для Telegram модуля
 * Создает бины для работы Telegram бота
 * Активируется только если включен Telegram модуль (ai-bot.telegram.enabled=true)
 */
@AutoConfiguration
@AutoConfigureAfter(BulkHeadAutoConfig.class)
@EnableConfigurationProperties({TelegramProperties.class, FileUploadProperties.class})
@Import({
        TelegramJpaConfig.class,
        TelegramFlywayConfig.class,
        TelegramServiceConfig.class,
        TelegramCommandHandlerConfig.class,
})
@ConditionalOnProperty(name = "ai-bot.telegram.enabled", havingValue = "true")
public class TelegramAutoConfig {

    @Bean
    @ConditionalOnMissingBean
    public TelegramBot telegramBot(TelegramProperties properties,
                                   TelegramCommandSyncService commandSyncService,
                                   TelegramUserService userService,
                                   ObjectProvider<TelegramFileService> fileServiceProvider,
                                   ObjectProvider<FileUploadProperties> fileUploadPropertiesProvider) {
        Integer socketTimeoutSec = properties.getLongPollingSocketTimeoutSeconds();
        Integer getUpdatesTimeoutSec = properties.getGetUpdatesTimeoutSeconds();
        DefaultBotOptions options = new DefaultBotOptions();
        options.setGetUpdatesTimeout(getUpdatesTimeoutSec != null ? getUpdatesTimeoutSec : 50);
        if (socketTimeoutSec != null) {
            int socketTimeoutMs = socketTimeoutSec * 1000;
            RequestConfig requestConfig = RequestConfig.custom()
                    .setSocketTimeout(socketTimeoutMs)
                    .setConnectTimeout(10_000)
                    .setConnectionRequestTimeout(10_000)
                    .build();
            options.setRequestConfig(requestConfig);
        }
        return new TelegramBot(properties, options, commandSyncService, userService,
                fileServiceProvider, fileUploadPropertiesProvider);
    }

    @Bean
    @ConditionalOnMissingBean
    public TelegramBotRegistrar telegramBotRegistrar(TelegramBot telegramBot,
                                                     ObjectProvider<TelegramBotMenuService> menuServiceProvider) {
        return new TelegramBotRegistrar(telegramBot, menuServiceProvider);
    }
}

