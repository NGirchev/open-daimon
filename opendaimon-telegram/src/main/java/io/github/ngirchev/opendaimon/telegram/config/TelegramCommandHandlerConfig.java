package io.github.ngirchev.opendaimon.telegram.config;

import io.github.ngirchev.opendaimon.telegram.service.TypingIndicatorService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import io.github.ngirchev.opendaimon.common.ai.factory.AICommandFactoryRegistry;
import io.github.ngirchev.opendaimon.common.config.CoreCommonProperties;
import io.github.ngirchev.opendaimon.common.repository.ConversationThreadRepository;
import io.github.ngirchev.opendaimon.common.service.MessageLocalizationService;
import io.github.ngirchev.opendaimon.common.repository.OpenDaimonMessageRepository;
import io.github.ngirchev.opendaimon.common.service.*;
import io.github.ngirchev.opendaimon.telegram.TelegramBot;
import io.github.ngirchev.opendaimon.telegram.command.handler.TelegramSupportedCommandProvider;
import io.github.ngirchev.opendaimon.telegram.command.handler.impl.*;
import io.github.ngirchev.opendaimon.telegram.service.TelegramMessageService;
import io.github.ngirchev.opendaimon.telegram.service.TelegramUserService;
import io.github.ngirchev.opendaimon.telegram.service.TelegramUserSessionService;

@Configuration
@ConditionalOnProperty(name = "open-daimon.telegram.enabled", havingValue = "true", matchIfMissing = true)
public class TelegramCommandHandlerConfig {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "open-daimon.telegram.commands", name = "bugreport-enabled", havingValue = "true", matchIfMissing = true)
    public BugreportTelegramCommandHandler callbackQueryTelegramCommandHandler(
            ObjectProvider<TelegramBot> telegramBotProvider,
            TypingIndicatorService typingIndicatorService,
            MessageLocalizationService messageLocalizationService,
            TelegramUserService telegramUserService,
            BugreportService bugreportService) {
        return new BugreportTelegramCommandHandler(telegramBotProvider, typingIndicatorService, messageLocalizationService, telegramUserService, bugreportService);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "open-daimon.telegram.commands", name = "start-enabled", havingValue = "true", matchIfMissing = true)
    public StartTelegramCommandHandler startTelegramCommandHandler(
            ObjectProvider<TelegramBot> telegramBotProvider,
            TypingIndicatorService typingIndicatorService,
            MessageLocalizationService messageLocalizationService,
            ObjectProvider<TelegramSupportedCommandProvider> handlersProvider) {
        return new StartTelegramCommandHandler(telegramBotProvider, typingIndicatorService, messageLocalizationService, handlersProvider);
    }

    @Bean
    @ConditionalOnMissingBean
    public BackoffCommandHandler backoffCommandHandler(
            ObjectProvider<TelegramBot> telegramBotProvider,
            TypingIndicatorService typingIndicatorService,
            MessageLocalizationService messageLocalizationService,
            ObjectProvider<TelegramSupportedCommandProvider> handlersProvider) {
        return new BackoffCommandHandler(telegramBotProvider, typingIndicatorService, messageLocalizationService, handlersProvider);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "open-daimon.telegram.commands", name = "role-enabled", havingValue = "true", matchIfMissing = true)
    public RoleTelegramCommandHandler roleTelegramCommandHandler(
            ObjectProvider<TelegramBot> telegramBotProvider,
            TypingIndicatorService typingIndicatorService,
            MessageLocalizationService messageLocalizationService,
            TelegramUserService telegramUserService,
            CoreCommonProperties coreCommonProperties) {
        return new RoleTelegramCommandHandler(telegramBotProvider,
                typingIndicatorService, messageLocalizationService, telegramUserService, coreCommonProperties);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "open-daimon.telegram.commands", name = "language-enabled", havingValue = "true", matchIfMissing = true)
    public LanguageTelegramCommandHandler languageTelegramCommandHandler(
            ObjectProvider<TelegramBot> telegramBotProvider,
            TypingIndicatorService typingIndicatorService,
            MessageLocalizationService messageLocalizationService,
            TelegramUserService telegramUserService) {
        return new LanguageTelegramCommandHandler(telegramBotProvider,
                typingIndicatorService, messageLocalizationService, telegramUserService);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "open-daimon.telegram.commands", name = "newthread-enabled", havingValue = "true", matchIfMissing = true)
    public NewThreadTelegramCommandHandler newThreadTelegramCommandHandler(
            ObjectProvider<TelegramBot> telegramBotProvider,
            TypingIndicatorService typingIndicatorService,
            MessageLocalizationService messageLocalizationService,
            ConversationThreadService threadService,
            ConversationThreadRepository threadRepository,
            TelegramUserService telegramUserService) {
        return new NewThreadTelegramCommandHandler(
                telegramBotProvider,
                typingIndicatorService,
                messageLocalizationService,
                threadService,
                threadRepository,
                telegramUserService);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "open-daimon.telegram.commands", name = "history-enabled", havingValue = "true", matchIfMissing = true)
    public HistoryTelegramCommandHandler historyTelegramCommandHandler(
            ObjectProvider<TelegramBot> telegramBotProvider,
            TypingIndicatorService typingIndicatorService,
            MessageLocalizationService messageLocalizationService,
            ConversationThreadRepository threadRepository,
            OpenDaimonMessageRepository messageRepository,
            TelegramUserService telegramUserService) {
        return new HistoryTelegramCommandHandler(
                telegramBotProvider,
                typingIndicatorService,
                messageLocalizationService,
                threadRepository,
                messageRepository,
                telegramUserService);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "open-daimon.telegram.commands", name = "threads-enabled", havingValue = "true", matchIfMissing = true)
    public ThreadsTelegramCommandHandler threadsTelegramCommandHandler(
            ObjectProvider<TelegramBot> telegramBotProvider,
            TypingIndicatorService typingIndicatorService,
            MessageLocalizationService messageLocalizationService,
            ConversationThreadRepository threadRepository,
            ConversationThreadService threadService,
            TelegramUserService telegramUserService) {
        return new ThreadsTelegramCommandHandler(
                telegramBotProvider,
                typingIndicatorService,
                messageLocalizationService,
                threadRepository,
                threadService,
                telegramUserService);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "open-daimon.telegram.commands", name = "message-enabled", havingValue = "true", matchIfMissing = true)
    public MessageTelegramCommandHandler messageTelegramCommandHandler(
            ObjectProvider<TelegramBot> telegramBotProvider,
            TypingIndicatorService typingIndicatorService,
            MessageLocalizationService messageLocalizationService,
            TelegramUserService telegramUserService,
            TelegramUserSessionService telegramUserSessionService,
            TelegramMessageService telegramMessageService,
            AIGatewayRegistry aiGatewayRegistry,
            OpenDaimonMessageService messageService,
            AICommandFactoryRegistry aiCommandFactoryRegistry,
            TelegramProperties telegramProperties) {
        return new MessageTelegramCommandHandler(
                telegramBotProvider,
                typingIndicatorService,
                messageLocalizationService,
                telegramUserService,
                telegramUserSessionService,
                telegramMessageService,
                aiGatewayRegistry,
                messageService,
                aiCommandFactoryRegistry,
                telegramProperties
        );
    }
}