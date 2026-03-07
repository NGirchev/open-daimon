package ru.girchev.aibot.telegram.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.girchev.aibot.common.ai.factory.AICommandFactoryRegistry;
import ru.girchev.aibot.common.config.CoreCommonProperties;
import ru.girchev.aibot.common.repository.ConversationThreadRepository;
import ru.girchev.aibot.common.repository.AIBotMessageRepository;
import ru.girchev.aibot.common.service.*;
import ru.girchev.aibot.telegram.TelegramBot;
import ru.girchev.aibot.telegram.command.handler.TelegramSupportedCommandProvider;
import ru.girchev.aibot.telegram.command.handler.impl.*;
import ru.girchev.aibot.telegram.service.TelegramMessageService;
import ru.girchev.aibot.telegram.service.TelegramUserService;
import ru.girchev.aibot.telegram.service.TelegramUserSessionService;

@Configuration
@ConditionalOnProperty(name = "ai-bot.telegram.enabled", havingValue = "true", matchIfMissing = true)
public class TelegramCommandHandlerConfig {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "ai-bot.telegram.commands", name = "bugreport-enabled", havingValue = "true", matchIfMissing = true)
    public BugreportTelegramCommandHandler callbackQueryTelegramCommandHandler(
            ObjectProvider<TelegramBot> telegramBotProvider,
            ru.girchev.aibot.telegram.service.TypingIndicatorService typingIndicatorService,
            TelegramUserService telegramUserService,
            BugreportService bugreportService) {
        return new BugreportTelegramCommandHandler(telegramBotProvider, typingIndicatorService, telegramUserService, bugreportService);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "ai-bot.telegram.commands", name = "start-enabled", havingValue = "true", matchIfMissing = true)
    public StartTelegramCommandHandler startTelegramCommandHandler(
            ObjectProvider<TelegramBot> telegramBotProvider,
            ru.girchev.aibot.telegram.service.TypingIndicatorService typingIndicatorService,
            ObjectProvider<TelegramSupportedCommandProvider> handlersProvider,
            TelegramProperties telegramProperties) {
        return new StartTelegramCommandHandler(telegramBotProvider, typingIndicatorService, handlersProvider, telegramProperties);
    }

    @Bean
    @ConditionalOnMissingBean
    public BackoffCommandHandler backoffCommandHandler(
            ObjectProvider<TelegramBot> telegramBotProvider,
            ru.girchev.aibot.telegram.service.TypingIndicatorService typingIndicatorService,
            ObjectProvider<TelegramSupportedCommandProvider> handlersProvider,
            TelegramProperties telegramProperties) {
        return new BackoffCommandHandler(telegramBotProvider, typingIndicatorService, handlersProvider, telegramProperties);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "ai-bot.telegram.commands", name = "role-enabled", havingValue = "true", matchIfMissing = true)
    public RoleTelegramCommandHandler roleTelegramCommandHandler(
            ObjectProvider<TelegramBot> telegramBotProvider,
            ru.girchev.aibot.telegram.service.TypingIndicatorService typingIndicatorService,
            TelegramUserService telegramUserService,
            CoreCommonProperties coreCommonProperties) {
        return new RoleTelegramCommandHandler(telegramBotProvider,
                typingIndicatorService, telegramUserService, coreCommonProperties);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "ai-bot.telegram.commands", name = "newthread-enabled", havingValue = "true", matchIfMissing = true)
    public NewThreadTelegramCommandHandler newThreadTelegramCommandHandler(
            ObjectProvider<TelegramBot> telegramBotProvider,
            ru.girchev.aibot.telegram.service.TypingIndicatorService typingIndicatorService,
            ConversationThreadService threadService,
            ConversationThreadRepository threadRepository,
            TelegramUserService telegramUserService) {
        return new NewThreadTelegramCommandHandler(
                telegramBotProvider,
                typingIndicatorService,
                threadService,
                threadRepository,
                telegramUserService);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "ai-bot.telegram.commands", name = "history-enabled", havingValue = "true", matchIfMissing = true)
    public HistoryTelegramCommandHandler historyTelegramCommandHandler(
            ObjectProvider<TelegramBot> telegramBotProvider,
            ru.girchev.aibot.telegram.service.TypingIndicatorService typingIndicatorService,
            ConversationThreadRepository threadRepository,
            AIBotMessageRepository messageRepository,
            TelegramUserService telegramUserService) {
        return new HistoryTelegramCommandHandler(
                telegramBotProvider,
                typingIndicatorService,
                threadRepository,
                messageRepository,
                telegramUserService);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "ai-bot.telegram.commands", name = "threads-enabled", havingValue = "true", matchIfMissing = true)
    public ThreadsTelegramCommandHandler threadsTelegramCommandHandler(
            ObjectProvider<TelegramBot> telegramBotProvider,
            ru.girchev.aibot.telegram.service.TypingIndicatorService typingIndicatorService,
            ConversationThreadRepository threadRepository,
            ConversationThreadService threadService,
            TelegramUserService telegramUserService) {
        return new ThreadsTelegramCommandHandler(
                telegramBotProvider,
                typingIndicatorService,
                threadRepository,
                threadService,
                telegramUserService);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "ai-bot.telegram.commands", name = "message-enabled", havingValue = "true", matchIfMissing = true)
    public MessageTelegramCommandHandler messageTelegramCommandHandler(
            ObjectProvider<TelegramBot> telegramBotProvider,
            ru.girchev.aibot.telegram.service.TypingIndicatorService typingIndicatorService,
            TelegramUserService telegramUserService,
            TelegramUserSessionService telegramUserSessionService,
            TelegramMessageService telegramMessageService,
            AIGatewayRegistry aiGatewayRegistry,
            AIBotMessageService messageService,
            AICommandFactoryRegistry aiCommandFactoryRegistry,
            TelegramProperties telegramProperties) {
        return new MessageTelegramCommandHandler(
                telegramBotProvider,
                typingIndicatorService,
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