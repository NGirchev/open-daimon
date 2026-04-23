package io.github.ngirchev.opendaimon.telegram.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ngirchev.opendaimon.telegram.service.TypingIndicatorService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import io.github.ngirchev.opendaimon.common.config.FeatureToggle;
import io.github.ngirchev.opendaimon.bulkhead.service.IUserPriorityService;
import io.github.ngirchev.opendaimon.common.agent.AgentExecutor;
import io.github.ngirchev.opendaimon.common.ai.pipeline.AIRequestPipeline;
import io.github.ngirchev.opendaimon.common.config.CoreCommonProperties;
import io.github.ngirchev.opendaimon.common.repository.ConversationThreadRepository;
import io.github.ngirchev.opendaimon.common.repository.OpenDaimonMessageRepository;
import io.github.ngirchev.opendaimon.common.service.*;
import io.github.ngirchev.opendaimon.telegram.TelegramBot;
import io.github.ngirchev.opendaimon.telegram.command.handler.TelegramSupportedCommandProvider;
import io.github.ngirchev.opendaimon.telegram.command.handler.impl.*;
import io.github.ngirchev.opendaimon.telegram.command.handler.impl.fsm.MessageHandlerActions;
import io.github.ngirchev.opendaimon.telegram.command.handler.impl.fsm.MessageHandlerContext;
import io.github.ngirchev.opendaimon.telegram.command.handler.impl.fsm.MessageHandlerEvent;
import io.github.ngirchev.opendaimon.telegram.command.handler.impl.fsm.MessageHandlerFsmFactory;
import io.github.ngirchev.opendaimon.telegram.command.handler.impl.fsm.MessageHandlerState;
import io.github.ngirchev.opendaimon.telegram.command.handler.impl.fsm.TelegramMessageHandlerActions;
import io.github.ngirchev.opendaimon.telegram.command.handler.impl.fsm.TelegramMessageSender;
import io.github.ngirchev.opendaimon.telegram.service.InMemoryModelSelectionSession;
import io.github.ngirchev.opendaimon.telegram.service.ModelSelectionSession;
import io.github.ngirchev.opendaimon.telegram.service.TelegramAgentStreamRenderer;
import io.github.ngirchev.opendaimon.telegram.service.PersistentKeyboardService;
import io.github.ngirchev.opendaimon.telegram.service.ReplyImageAttachmentService;
import io.github.ngirchev.opendaimon.telegram.service.UserModelPreferenceService;
import io.github.ngirchev.opendaimon.telegram.service.UserRecentModelService;
import io.github.ngirchev.opendaimon.telegram.service.TelegramFileService;
import io.github.ngirchev.opendaimon.telegram.service.TelegramMessageService;
import io.github.ngirchev.opendaimon.telegram.repository.TelegramUserRepository;
import io.github.ngirchev.opendaimon.telegram.service.TelegramBotMenuService;
import io.github.ngirchev.opendaimon.telegram.service.TelegramUserService;
import io.github.ngirchev.opendaimon.telegram.service.TelegramUserSessionService;
import io.github.ngirchev.opendaimon.common.storage.service.FileStorageService;
import io.github.ngirchev.fsm.impl.extended.ExDomainFsm;

@Configuration
@ConditionalOnProperty(name = FeatureToggle.Module.TELEGRAM_ENABLED, havingValue = "true")
public class TelegramCommandHandlerConfig {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = FeatureToggle.TelegramCommand.PREFIX, name = FeatureToggle.TelegramCommand.BUGREPORT, havingValue = "true", matchIfMissing = true)
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
    @ConditionalOnProperty(prefix = FeatureToggle.TelegramCommand.PREFIX, name = FeatureToggle.TelegramCommand.START, havingValue = "true", matchIfMissing = true)
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
    @ConditionalOnProperty(prefix = FeatureToggle.TelegramCommand.PREFIX, name = FeatureToggle.TelegramCommand.ROLE, havingValue = "true", matchIfMissing = true)
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
    @ConditionalOnProperty(prefix = FeatureToggle.TelegramCommand.PREFIX, name = FeatureToggle.TelegramCommand.LANGUAGE, havingValue = "true", matchIfMissing = true)
    public LanguageTelegramCommandHandler languageTelegramCommandHandler(
            ObjectProvider<TelegramBot> telegramBotProvider,
            TypingIndicatorService typingIndicatorService,
            MessageLocalizationService messageLocalizationService,
            TelegramUserService telegramUserService,
            TelegramBotMenuService telegramBotMenuService) {
        return new LanguageTelegramCommandHandler(telegramBotProvider,
                typingIndicatorService, messageLocalizationService, telegramUserService, telegramBotMenuService);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(AgentExecutor.class)
    @ConditionalOnProperty(prefix = FeatureToggle.TelegramCommand.PREFIX, name = FeatureToggle.TelegramCommand.MODE, havingValue = "true", matchIfMissing = true)
    public ModeTelegramCommandHandler modeTelegramCommandHandler(
            ObjectProvider<TelegramBot> telegramBotProvider,
            TypingIndicatorService typingIndicatorService,
            MessageLocalizationService messageLocalizationService,
            TelegramUserService telegramUserService) {
        return new ModeTelegramCommandHandler(telegramBotProvider,
                typingIndicatorService, messageLocalizationService, telegramUserService);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = FeatureToggle.TelegramCommand.PREFIX, name = FeatureToggle.TelegramCommand.THINKING, havingValue = "true", matchIfMissing = true)
    public ThinkingTelegramCommandHandler thinkingTelegramCommandHandler(
            ObjectProvider<TelegramBot> telegramBotProvider,
            TypingIndicatorService typingIndicatorService,
            MessageLocalizationService messageLocalizationService,
            TelegramUserService telegramUserService,
            TelegramBotMenuService telegramBotMenuService) {
        return new ThinkingTelegramCommandHandler(telegramBotProvider,
                typingIndicatorService, messageLocalizationService, telegramUserService, telegramBotMenuService);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = FeatureToggle.TelegramCommand.PREFIX, name = FeatureToggle.TelegramCommand.NEW_THREAD, havingValue = "true", matchIfMissing = true)
    public NewThreadTelegramCommandHandler newThreadTelegramCommandHandler(
            ObjectProvider<TelegramBot> telegramBotProvider,
            TypingIndicatorService typingIndicatorService,
            MessageLocalizationService messageLocalizationService,
            ConversationThreadService threadService,
            ConversationThreadRepository threadRepository,
            TelegramUserService telegramUserService,
            ObjectProvider<PersistentKeyboardService> persistentKeyboardServiceProvider) {
        return new NewThreadTelegramCommandHandler(
                telegramBotProvider,
                typingIndicatorService,
                messageLocalizationService,
                threadService,
                threadRepository,
                telegramUserService,
                persistentKeyboardServiceProvider);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = FeatureToggle.TelegramCommand.PREFIX, name = FeatureToggle.TelegramCommand.HISTORY, havingValue = "true", matchIfMissing = true)
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
    @ConditionalOnProperty(prefix = FeatureToggle.TelegramCommand.PREFIX, name = FeatureToggle.TelegramCommand.THREADS, havingValue = "true", matchIfMissing = true)
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
    public ReplyImageAttachmentService replyImageAttachmentService(
            OpenDaimonMessageRepository messageRepository,
            ObjectProvider<FileStorageService> fileStorageServiceProvider,
            ObjectProvider<TelegramFileService> telegramFileServiceProvider) {
        return new ReplyImageAttachmentService(
                messageRepository, fileStorageServiceProvider, telegramFileServiceProvider);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = FeatureToggle.TelegramCommand.PREFIX, name = FeatureToggle.TelegramCommand.MESSAGE, havingValue = "true", matchIfMissing = true)
    public TelegramMessageSender telegramMessageSender(
            ObjectProvider<TelegramBot> telegramBotProvider,
            MessageLocalizationService messageLocalizationService,
            PersistentKeyboardService persistentKeyboardService) {
        return new TelegramMessageSender(telegramBotProvider, messageLocalizationService, persistentKeyboardService);
    }

    @Bean
    @ConditionalOnMissingBean
    public TelegramAgentStreamRenderer telegramAgentStreamRenderer(ObjectMapper objectMapper) {
        return new TelegramAgentStreamRenderer(objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean(MessageHandlerActions.class)
    @ConditionalOnProperty(prefix = FeatureToggle.TelegramCommand.PREFIX, name = FeatureToggle.TelegramCommand.MESSAGE, havingValue = "true", matchIfMissing = true)
    public TelegramMessageHandlerActions messageHandlerActions(
            TelegramUserService telegramUserService,
            TelegramUserSessionService telegramUserSessionService,
            TelegramMessageService telegramMessageService,
            AIGatewayRegistry aiGatewayRegistry,
            OpenDaimonMessageService messageService,
            AIRequestPipeline aiRequestPipeline,
            TelegramProperties telegramProperties,
            UserModelPreferenceService userModelPreferenceService,
            PersistentKeyboardService persistentKeyboardService,
            ReplyImageAttachmentService replyImageAttachmentService,
            TelegramMessageSender telegramMessageSender,
            ObjectProvider<AgentExecutor> agentExecutorProvider,
            TelegramAgentStreamRenderer agentStreamRenderer,
            // No default here — all defaults live in application.yml only (see coding-style.md)
            @Value("${open-daimon.agent.max-iterations}") int agentMaxIterations,
            @Value("${open-daimon.agent.enabled:false}") boolean defaultAgentModeEnabled) {
        return new TelegramMessageHandlerActions(
                telegramUserService, telegramUserSessionService,
                telegramMessageService, aiGatewayRegistry, messageService,
                aiRequestPipeline, telegramProperties, userModelPreferenceService,
                persistentKeyboardService, replyImageAttachmentService, telegramMessageSender,
                agentExecutorProvider.getIfAvailable(), agentStreamRenderer, agentMaxIterations,
                defaultAgentModeEnabled);
    }

    @Bean
    @ConditionalOnMissingBean(name = "messageHandlerFsm")
    @ConditionalOnProperty(prefix = FeatureToggle.TelegramCommand.PREFIX, name = FeatureToggle.TelegramCommand.MESSAGE, havingValue = "true", matchIfMissing = true)
    public ExDomainFsm<MessageHandlerContext, MessageHandlerState, MessageHandlerEvent> messageHandlerFsm(
            MessageHandlerActions actions) {
        return MessageHandlerFsmFactory.create(actions);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = FeatureToggle.TelegramCommand.PREFIX, name = FeatureToggle.TelegramCommand.MESSAGE, havingValue = "true", matchIfMissing = true)
    public MessageTelegramCommandHandler messageTelegramCommandHandler(
            ObjectProvider<TelegramBot> telegramBotProvider,
            TypingIndicatorService typingIndicatorService,
            MessageLocalizationService messageLocalizationService,
            ExDomainFsm<MessageHandlerContext, MessageHandlerState, MessageHandlerEvent> handlerFsm,
            TelegramMessageService telegramMessageService,
            TelegramProperties telegramProperties,
            PersistentKeyboardService persistentKeyboardService) {
        return new MessageTelegramCommandHandler(
                telegramBotProvider,
                typingIndicatorService,
                messageLocalizationService,
                handlerFsm,
                telegramMessageService,
                telegramProperties,
                persistentKeyboardService
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public UserModelPreferenceService userModelPreferenceService(
            TelegramUserRepository telegramUserRepository) {
        return new UserModelPreferenceService(telegramUserRepository);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = FeatureToggle.TelegramCommand.PREFIX, name = FeatureToggle.TelegramCommand.MODEL, havingValue = "true", matchIfMissing = true)
    public PersistentKeyboardService persistentKeyboardService(
            UserModelPreferenceService userModelPreferenceService,
            CoreCommonProperties coreCommonProperties,
            ObjectProvider<TelegramBot> telegramBotProvider,
            TelegramProperties telegramProperties,
            MessageLocalizationService messageLocalizationService,
            TelegramUserRepository telegramUserRepository) {
        return new PersistentKeyboardService(userModelPreferenceService, coreCommonProperties, telegramBotProvider,
                telegramProperties, messageLocalizationService, telegramUserRepository);
    }

    @Bean
    @ConditionalOnMissingBean
    public ModelSelectionSession modelSelectionSession() {
        return new InMemoryModelSelectionSession();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = FeatureToggle.TelegramCommand.PREFIX, name = FeatureToggle.TelegramCommand.MODEL, havingValue = "true", matchIfMissing = true)
    public ModelTelegramCommandHandler modelTelegramCommandHandler(
            ObjectProvider<TelegramBot> telegramBotProvider,
            TypingIndicatorService typingIndicatorService,
            MessageLocalizationService messageLocalizationService,
            TelegramUserService telegramUserService,
            UserModelPreferenceService userModelPreferenceService,
            AIGatewayRegistry aiGatewayRegistry,
            IUserPriorityService userPriorityService,
            PersistentKeyboardService persistentKeyboardService,
            ConversationThreadService conversationThreadService,
            ModelSelectionSession modelSelectionSession,
            UserRecentModelService userRecentModelService) {
        return new ModelTelegramCommandHandler(
                telegramBotProvider,
                typingIndicatorService,
                messageLocalizationService,
                telegramUserService,
                userModelPreferenceService,
                aiGatewayRegistry,
                userPriorityService,
                persistentKeyboardService,
                conversationThreadService,
                modelSelectionSession,
                userRecentModelService
        );
    }
}