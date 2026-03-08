package io.github.ngirchev.aibot.telegram.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Lazy;
import io.github.ngirchev.aibot.bulkhead.service.PriorityRequestExecutor;
import io.github.ngirchev.aibot.bulkhead.service.impl.DefaultUserPriorityService;
import io.github.ngirchev.aibot.common.command.CommandHandlerRegistry;
import io.github.ngirchev.aibot.common.config.CoreCommonProperties;
import io.github.ngirchev.aibot.common.meter.AIBotMeterRegistry;
import io.github.ngirchev.aibot.common.service.AIBotMessageService;
import io.github.ngirchev.aibot.common.service.AssistantRoleService;
import io.github.ngirchev.aibot.common.storage.config.StorageProperties;
import io.github.ngirchev.aibot.common.storage.service.FileStorageService;
import io.github.ngirchev.aibot.telegram.TelegramBot;
import io.github.ngirchev.aibot.telegram.command.handler.TelegramSupportedCommandProvider;
import io.github.ngirchev.aibot.telegram.repository.TelegramUserRepository;
import io.github.ngirchev.aibot.telegram.repository.TelegramUserSessionRepository;
import io.github.ngirchev.aibot.telegram.repository.TelegramWhitelistRepository;
import io.github.ngirchev.aibot.telegram.service.*;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

@Configuration
@DependsOn("telegramFlyway")
public class TelegramServiceConfig {

    @Bean
    @ConditionalOnMissingBean
    public TelegramUserService telegramUserService(
            TelegramUserRepository telegramUserRepository,
            TelegramUserSessionService telegramUserSessionService,
            AssistantRoleService assistantRoleService) {
        return new TelegramUserService(telegramUserRepository, telegramUserSessionService, assistantRoleService);
    }

    @Bean
    @ConditionalOnMissingBean
    public TelegramWhitelistService telegramWhitelistService(
            TelegramWhitelistRepository whitelistRepository,
            @Lazy TelegramBot telegramBot,
            TelegramUserRepository telegramUserRepository,
            TelegramProperties telegramProperties) {
        return new TelegramWhitelistService(
                whitelistRepository,
                telegramBot,
                telegramUserRepository,
                telegramProperties.getWhitelistChannelIdExceptionsSet());
    }

    @Bean
    @ConditionalOnMissingBean
    public TelegramUserSessionService telegramUserSessionService(
            TelegramUserSessionRepository telegramUserSessionRepository,
            TelegramUserRepository telegramUserRepository) {
        return new TelegramUserSessionService(telegramUserSessionRepository, telegramUserRepository);
    }

    @Bean
    @ConditionalOnMissingBean
    public TelegramUserActivityService telegramUserActivityService(
            TelegramUserSessionRepository sessionRepository,
            TelegramUserSessionService sessionService) {
        return new TelegramUserActivityService(sessionRepository, sessionService);
    }

    @Bean
    @ConditionalOnMissingBean
    public TelegramMessageService telegramMessageService(
            AIBotMessageService messageService,
            TelegramUserService telegramUserService,
            CoreCommonProperties coreCommonProperties,
            ObjectProvider<StorageProperties> storagePropertiesProvider) {
        return new TelegramMessageService(
                messageService,
                telegramUserService,
                coreCommonProperties,
                storagePropertiesProvider);
    }

    @Bean
    @ConditionalOnMissingBean
    public TelegramWhitelistInitializer telegramWhitelistInitializer(
            TelegramWhitelistService whitelistService,
            TelegramWhitelistRepository whitelistRepository,
            TelegramProperties telegramProperties) {
        return new TelegramWhitelistInitializer(whitelistService, whitelistRepository, telegramProperties);
    }

    @Bean
    @ConditionalOnMissingBean
    public ScheduledExecutorService typingIndicatorScheduledExecutor() {
        return Executors.newScheduledThreadPool(2, r -> {
            Thread thread = new Thread(r, "typing-indicator-");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Bean
    @ConditionalOnMissingBean
    public TypingIndicatorService typingIndicatorService(
            ObjectProvider<TelegramBot> telegramBotProvider,
            ScheduledExecutorService typingIndicatorScheduledExecutor) {
        return new TypingIndicatorService(telegramBotProvider, typingIndicatorScheduledExecutor);
    }

    @Bean
    @ConditionalOnMissingBean
    public TelegramBotMenuService telegramBotMenuService(
            ObjectProvider<TelegramBot> telegramBotProvider,
            ObjectProvider<TelegramSupportedCommandProvider> commandHandlersProvider) {
        return new TelegramBotMenuService(telegramBotProvider, commandHandlersProvider);
    }

    @Bean
    @ConditionalOnMissingBean
    public TelegramCommandSyncService telegramCommandSyncService(
            AIBotMeterRegistry meterRegistry,
            CommandHandlerRegistry registry,
            PriorityRequestExecutor priorityRequestExecutor,
            TelegramUserService userService,
            TelegramWhitelistService whitelistService) {
        return new TelegramCommandSyncService(meterRegistry, registry, priorityRequestExecutor,
                new DefaultUserPriorityService(userService, whitelistService));
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
            name = "ai-bot.telegram.file-upload.enabled", 
            havingValue = "true")
    public TelegramFileService telegramFileService(
            ObjectProvider<TelegramBot> telegramBotProvider,
            ObjectProvider<FileStorageService> fileStorageServiceProvider,
            FileUploadProperties fileUploadProperties) {
        return new TelegramFileService(telegramBotProvider, fileStorageServiceProvider, fileUploadProperties);
    }
}
