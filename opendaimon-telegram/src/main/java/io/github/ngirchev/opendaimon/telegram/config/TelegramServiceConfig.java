package io.github.ngirchev.opendaimon.telegram.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Lazy;
import io.github.ngirchev.opendaimon.common.config.FeatureToggle;
import io.github.ngirchev.opendaimon.bulkhead.service.PriorityRequestExecutor;
import io.github.ngirchev.opendaimon.common.command.CommandHandlerRegistry;
import io.github.ngirchev.opendaimon.common.config.CoreCommonProperties;
import io.github.ngirchev.opendaimon.common.meter.OpenDaimonMeterRegistry;
import io.github.ngirchev.opendaimon.common.repository.ConversationThreadRepository;
import io.github.ngirchev.opendaimon.common.repository.UserRecentModelRepository;
import io.github.ngirchev.opendaimon.common.repository.UserRepository;
import io.github.ngirchev.opendaimon.common.service.AssistantRoleService;
import io.github.ngirchev.opendaimon.common.service.ConversationThreadService;
import io.github.ngirchev.opendaimon.common.service.MessageLocalizationService;
import io.github.ngirchev.opendaimon.common.service.OpenDaimonMessageService;
import io.github.ngirchev.opendaimon.common.storage.config.StorageProperties;
import io.github.ngirchev.opendaimon.common.storage.service.FileStorageService;
import io.github.ngirchev.opendaimon.telegram.TelegramBot;
import io.github.ngirchev.opendaimon.telegram.command.handler.TelegramSupportedCommandProvider;
import io.github.ngirchev.opendaimon.telegram.repository.TelegramUserRepository;
import io.github.ngirchev.opendaimon.telegram.repository.TelegramUserSessionRepository;
import io.github.ngirchev.opendaimon.telegram.repository.TelegramWhitelistRepository;
import io.github.ngirchev.opendaimon.telegram.service.*;
import io.github.ngirchev.opendaimon.telegram.service.impl.UserRecentModelServiceImpl;

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
            AssistantRoleService assistantRoleService,
            @Value("${open-daimon.agent.enabled:false}") boolean defaultAgentModeEnabled) {
        return new TelegramUserService(telegramUserRepository, telegramUserSessionService, assistantRoleService,
                defaultAgentModeEnabled);
    }

    @Bean
    @ConditionalOnMissingBean
    public TelegramUsersStartupInitializer telegramUsersStartupInitializer(
            TelegramUserService telegramUserService,
            TelegramProperties telegramProperties,
            org.springframework.beans.factory.ObjectProvider<TelegramBot> telegramBotProvider) {
        return new TelegramUsersStartupInitializer(telegramUserService, telegramProperties, telegramBotProvider);
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
                telegramProperties.getAllAccessChannels());
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
            OpenDaimonMessageService messageService,
            TelegramUserService telegramUserService,
            CoreCommonProperties coreCommonProperties,
            MessageLocalizationService messageLocalizationService,
            ObjectProvider<StorageProperties> storagePropertiesProvider,
            ConversationThreadService conversationThreadService,
            ObjectProvider<TelegramMessageService> telegramMessageServiceSelfProvider) {
        return new TelegramMessageService(
                messageService,
                telegramUserService,
                coreCommonProperties,
                messageLocalizationService,
                storagePropertiesProvider,
                conversationThreadService,
                telegramMessageServiceSelfProvider);
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
            @Qualifier("typingIndicatorScheduledExecutor")
            ScheduledExecutorService typingIndicatorScheduledExecutor) {
        return new TypingIndicatorService(telegramBotProvider, typingIndicatorScheduledExecutor);
    }

    @Bean
    @ConditionalOnMissingBean(name = "messageCoalescingScheduledExecutor")
    public ScheduledExecutorService messageCoalescingScheduledExecutor() {
        return Executors.newScheduledThreadPool(1, r -> {
            Thread thread = new Thread(r, "telegram-message-coalescing-");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Bean
    @ConditionalOnMissingBean
    public TelegramMessageCoalescingService telegramMessageCoalescingService(
            TelegramProperties telegramProperties,
            @Qualifier("messageCoalescingScheduledExecutor")
            ScheduledExecutorService messageCoalescingScheduledExecutor) {
        return new TelegramMessageCoalescingService(
                telegramProperties.getMessageCoalescing(),
                messageCoalescingScheduledExecutor
        );
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
            OpenDaimonMeterRegistry meterRegistry,
            CommandHandlerRegistry registry,
            PriorityRequestExecutor priorityRequestExecutor,
            TelegramUserService userService,
            TelegramWhitelistService whitelistService,
            TelegramProperties telegramProperties) {
        return new TelegramCommandSyncService(meterRegistry, registry, priorityRequestExecutor,
                new TelegramUserPriorityService(userService, whitelistService, telegramProperties));
    }

    @Bean
    @ConditionalOnMissingBean
    public TelegramSummarizationListener telegramSummarizationListener(
            ObjectProvider<TelegramBot> telegramBotProvider,
            ConversationThreadRepository conversationThreadRepository,
            MessageLocalizationService messageLocalizationService) {
        return new TelegramSummarizationListener(telegramBotProvider, conversationThreadRepository, messageLocalizationService);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
            name = FeatureToggle.Feature.TELEGRAM_FILE_UPLOAD_ENABLED,
            havingValue = "true")
    public TelegramFileService telegramFileService(
            ObjectProvider<TelegramBot> telegramBotProvider,
            ObjectProvider<FileStorageService> fileStorageServiceProvider,
            FileUploadProperties fileUploadProperties) {
        return new TelegramFileService(telegramBotProvider, fileStorageServiceProvider, fileUploadProperties);
    }

    @Bean
    @ConditionalOnMissingBean
    public UserRecentModelService userRecentModelService(
            UserRecentModelRepository userRecentModelRepository,
            UserRepository userRepository) {
        return new UserRecentModelServiceImpl(userRecentModelRepository, userRepository);
    }
}
