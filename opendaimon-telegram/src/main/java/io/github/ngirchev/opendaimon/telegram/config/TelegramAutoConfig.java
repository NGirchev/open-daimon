package io.github.ngirchev.opendaimon.telegram.config;

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
import io.github.ngirchev.opendaimon.bulkhead.config.BulkHeadAutoConfig;
import io.github.ngirchev.opendaimon.common.service.MessageLocalizationService;
import io.github.ngirchev.opendaimon.telegram.TelegramBot;
import io.github.ngirchev.opendaimon.telegram.service.TelegramBotMenuService;
import io.github.ngirchev.opendaimon.telegram.service.TelegramBotRegistrar;
import io.github.ngirchev.opendaimon.telegram.service.TelegramCommandSyncService;
import io.github.ngirchev.opendaimon.telegram.service.TelegramFileService;
import io.github.ngirchev.opendaimon.telegram.service.TelegramUserService;

/**
 * Auto-configuration for Telegram module.
 * Creates beans for Telegram bot.
 * Active only when Telegram module is enabled (open-daimon.telegram.enabled=true).
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
@ConditionalOnProperty(name = "open-daimon.telegram.enabled", havingValue = "true")
public class TelegramAutoConfig {

    @Bean
    @ConditionalOnMissingBean
    public TelegramBot telegramBot(TelegramProperties properties,
                                   TelegramCommandSyncService commandSyncService,
                                   TelegramUserService userService,
                                   MessageLocalizationService messageLocalizationService,
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
                messageLocalizationService, fileServiceProvider, fileUploadPropertiesProvider);
    }

    @Bean
    @ConditionalOnMissingBean
    public TelegramBotRegistrar telegramBotRegistrar(TelegramBot telegramBot,
                                                     ObjectProvider<TelegramBotMenuService> menuServiceProvider) {
        return new TelegramBotRegistrar(telegramBot, menuServiceProvider);
    }
}

