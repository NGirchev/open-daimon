package io.github.ngirchev.opendaimon.it.fixture.config;

import io.github.ngirchev.opendaimon.ai.mock.service.MockGateway;
import io.github.ngirchev.opendaimon.bulkhead.config.BulkHeadProperties;
import io.github.ngirchev.opendaimon.bulkhead.model.UserPriority;
import io.github.ngirchev.opendaimon.bulkhead.service.IUserPriorityService;
import io.github.ngirchev.opendaimon.bulkhead.service.PriorityRequestExecutor;
import io.github.ngirchev.opendaimon.common.ai.factory.AICommandFactory;
import io.github.ngirchev.opendaimon.common.ai.factory.AICommandFactoryRegistry;
import io.github.ngirchev.opendaimon.common.ai.pipeline.AIRequestPipeline;
import io.github.ngirchev.opendaimon.common.ai.factory.DefaultAICommandFactory;
import io.github.ngirchev.opendaimon.common.command.CommandHandlerRegistry;
import io.github.ngirchev.opendaimon.common.command.ICommand;
import io.github.ngirchev.opendaimon.common.command.ICommandHandler;
import io.github.ngirchev.opendaimon.common.command.ICommandType;
import io.github.ngirchev.opendaimon.common.config.CoreCommonProperties;
import io.github.ngirchev.opendaimon.common.meter.OpenDaimonMeterRegistry;
import io.github.ngirchev.opendaimon.common.repository.AssistantRoleRepository;
import io.github.ngirchev.opendaimon.common.repository.ConversationThreadRepository;
import io.github.ngirchev.opendaimon.common.repository.OpenDaimonMessageRepository;
import io.github.ngirchev.opendaimon.common.service.AIGatewayRegistry;
import io.github.ngirchev.opendaimon.common.service.AssistantRoleService;
import io.github.ngirchev.opendaimon.common.service.CommandSyncService;
import io.github.ngirchev.opendaimon.common.service.ConversationThreadService;
import io.github.ngirchev.opendaimon.common.service.MessageLocalizationService;
import io.github.ngirchev.opendaimon.common.service.OpenDaimonMessageService;
import io.github.ngirchev.opendaimon.common.service.TokenCounter;
import io.github.ngirchev.opendaimon.common.service.impl.AssistantRoleServiceImpl;
import io.github.ngirchev.opendaimon.common.storage.config.StorageProperties;
import io.github.ngirchev.opendaimon.telegram.TelegramBot;
import io.github.ngirchev.opendaimon.telegram.command.handler.impl.MessageTelegramCommandHandler;
import io.github.ngirchev.opendaimon.telegram.command.handler.impl.fsm.MessageHandlerContext;
import io.github.ngirchev.opendaimon.telegram.command.handler.impl.fsm.MessageHandlerEvent;
import io.github.ngirchev.opendaimon.telegram.command.handler.impl.fsm.MessageHandlerFsmFactory;
import io.github.ngirchev.opendaimon.telegram.command.handler.impl.fsm.MessageHandlerState;
import io.github.ngirchev.opendaimon.telegram.command.handler.impl.fsm.TelegramMessageHandlerActions;
import io.github.ngirchev.opendaimon.telegram.command.handler.impl.fsm.TelegramMessageSender;
import io.github.ngirchev.fsm.impl.extended.ExDomainFsm;
import io.github.ngirchev.opendaimon.telegram.config.TelegramProperties;
import io.github.ngirchev.opendaimon.telegram.repository.TelegramUserRepository;
import io.github.ngirchev.opendaimon.telegram.repository.TelegramUserSessionRepository;
import io.github.ngirchev.opendaimon.telegram.service.PersistentKeyboardService;
import io.github.ngirchev.opendaimon.telegram.service.ReplyImageAttachmentService;
import io.github.ngirchev.opendaimon.telegram.service.TelegramFileService;
import io.github.ngirchev.opendaimon.telegram.service.TelegramMessageService;
import io.github.ngirchev.opendaimon.telegram.service.TelegramUserService;
import io.github.ngirchev.opendaimon.telegram.service.TelegramUserSessionService;
import io.github.ngirchev.opendaimon.telegram.service.TypingIndicatorService;
import io.github.ngirchev.opendaimon.telegram.service.UserModelPreferenceService;
import io.github.ngirchev.opendaimon.common.storage.service.FileStorageService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Shared test configuration for all Telegram-based fixture tests.
 * Provides the full bean wiring needed to test Telegram message handling
 * with MockGateway (no real AI calls).
 *
 * <p>User priority is configurable per test via {@code AtomicReference<UserPriority>}.
 */
@TestConfiguration
public class TelegramFixtureConfig {

    @Bean
    public AtomicReference<UserPriority> userPriorityRef() {
        return new AtomicReference<>(UserPriority.REGULAR);
    }

    @Bean
    public IUserPriorityService userPriorityService(AtomicReference<UserPriority> userPriorityRef) {
        return userId -> userPriorityRef.get();
    }

    @Bean
    public AIGatewayRegistry aiGatewayRegistry() {
        return new AIGatewayRegistry();
    }

    @Bean
    public MockGateway mockGateway(AIGatewayRegistry aiGatewayRegistry) {
        return new MockGateway(aiGatewayRegistry);
    }

    @Bean
    public DefaultAICommandFactory defaultAICommandFactory(
            IUserPriorityService userPriorityService,
            CoreCommonProperties coreCommonProperties) {
        return new DefaultAICommandFactory(
                userPriorityService,
                null,
                coreCommonProperties);
    }

    @Bean
    public AICommandFactoryRegistry aiCommandFactoryRegistry(List<AICommandFactory<?, ?>> factories) {
        return new AICommandFactoryRegistry(factories);
    }

    @Bean
    public AIRequestPipeline aiRequestPipeline(AICommandFactoryRegistry aiCommandFactoryRegistry) {
        return new AIRequestPipeline(null, aiCommandFactoryRegistry);
    }

    @Bean
    public BulkHeadProperties bulkHeadProperties() {
        return new BulkHeadProperties();
    }

    @Bean
    public PriorityRequestExecutor priorityRequestExecutor(
            IUserPriorityService userPriorityService,
            BulkHeadProperties bulkHeadProperties) {
        return new PriorityRequestExecutor(userPriorityService, bulkHeadProperties);
    }

    @Bean
    public MeterRegistry meterRegistry() {
        return new SimpleMeterRegistry();
    }

    @Bean
    public OpenDaimonMeterRegistry openDaimonMeterRegistry(MeterRegistry meterRegistry) {
        return new OpenDaimonMeterRegistry(meterRegistry);
    }

    @Bean
    public CommandHandlerRegistry commandHandlerRegistry(List<ICommandHandler<?, ?, ?>> handlers) {
        return new CommandHandlerRegistry(handlers);
    }

    @Bean
    public CommandSyncService commandSyncService(
            OpenDaimonMeterRegistry meterRegistry,
            CommandHandlerRegistry registry,
            PriorityRequestExecutor priorityRequestExecutor,
            IUserPriorityService userPriorityService) {
        return new CommandSyncService(meterRegistry, registry, priorityRequestExecutor) {
            @Override
            public <T extends ICommandType, C extends ICommand<T>, R> R syncAndHandle(C command) {
                return syncAndHandle(command, userPriorityService::getUserPriority);
            }
        };
    }

    @Bean
    public AssistantRoleService assistantRoleService(
            AssistantRoleRepository assistantRoleRepository,
            ObjectProvider<AssistantRoleService> assistantRoleServiceSelfProvider) {
        return new AssistantRoleServiceImpl(assistantRoleRepository, assistantRoleServiceSelfProvider);
    }

    @Bean
    public ConversationThreadService conversationThreadService(
            ConversationThreadRepository threadRepository,
            OpenDaimonMessageRepository messageRepository) {
        return new ConversationThreadService(threadRepository, messageRepository);
    }

    @Bean
    public OpenDaimonMessageService openDaimonMessageService(
            OpenDaimonMessageRepository messageRepository,
            ConversationThreadService conversationThreadService,
            AssistantRoleService assistantRoleService,
            CoreCommonProperties coreCommonProperties,
            MessageLocalizationService messageLocalizationService,
            ObjectProvider<OpenDaimonMessageService> messageServiceSelfProvider) {
        return new OpenDaimonMessageService(
                messageRepository,
                conversationThreadService,
                assistantRoleService,
                coreCommonProperties,
                messageLocalizationService,
                new TokenCounter(),
                messageServiceSelfProvider);
    }

    @Bean
    public MessageSource messageSource() {
        var source = new ReloadableResourceBundleMessageSource();
        source.setBasenames("classpath:messages/common", "classpath:messages/telegram");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        return source;
    }

    @Bean
    public MessageLocalizationService messageLocalizationService(MessageSource messageSource) {
        return new MessageLocalizationService(messageSource);
    }

    @Bean
    public TelegramProperties telegramProperties() {
        var props = new TelegramProperties();
        props.setToken("test-token");
        props.setUsername("test-bot");
        props.setMaxMessageLength(4096);
        return props;
    }

    @Bean
    public TelegramUserSessionService telegramUserSessionService(
            TelegramUserSessionRepository telegramUserSessionRepository,
            TelegramUserRepository telegramUserRepository) {
        return new TelegramUserSessionService(telegramUserSessionRepository, telegramUserRepository);
    }

    @Bean
    public TelegramUserService telegramUserService(
            TelegramUserRepository telegramUserRepository,
            TelegramUserSessionService telegramUserSessionService,
            AssistantRoleService assistantRoleService) {
        return new TelegramUserService(telegramUserRepository, telegramUserSessionService, assistantRoleService);
    }

    @Bean
    public ObjectProvider<StorageProperties> storagePropertiesProvider() {
        @SuppressWarnings("unchecked")
        ObjectProvider<StorageProperties> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    @Bean
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
    public ScheduledExecutorService typingIndicatorScheduledExecutor() {
        return Executors.newScheduledThreadPool(1, r -> {
            Thread thread = new Thread(r, "typing-indicator-fixture-");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Bean
    public TypingIndicatorService typingIndicatorService(
            ObjectProvider<TelegramBot> telegramBotProvider,
            ScheduledExecutorService typingIndicatorScheduledExecutor) {
        return new TypingIndicatorService(telegramBotProvider, typingIndicatorScheduledExecutor);
    }

    @Bean
    public RecordingTelegramBot telegramBot(
            TelegramProperties telegramProperties,
            CommandSyncService commandSyncService,
            TelegramUserService telegramUserService) {
        return new RecordingTelegramBot(telegramProperties, commandSyncService, telegramUserService);
    }

    @Bean
    public UserModelPreferenceService userModelPreferenceService(
            TelegramUserRepository telegramUserRepository) {
        return new UserModelPreferenceService(telegramUserRepository);
    }

    @Bean
    public PersistentKeyboardService persistentKeyboardService(
            UserModelPreferenceService userModelPreferenceService,
            CoreCommonProperties coreCommonProperties,
            ObjectProvider<TelegramBot> telegramBotProvider,
            TelegramProperties telegramProperties,
            MessageLocalizationService messageLocalizationService,
            TelegramUserRepository telegramUserRepository) {
        return new PersistentKeyboardService(
                userModelPreferenceService, coreCommonProperties, telegramBotProvider,
                telegramProperties, messageLocalizationService, telegramUserRepository);
    }

    @Bean
    public ReplyImageAttachmentService replyImageAttachmentService(
            OpenDaimonMessageRepository messageRepository,
            ObjectProvider<FileStorageService> fileStorageServiceProvider,
            ObjectProvider<TelegramFileService> telegramFileServiceProvider) {
        return new ReplyImageAttachmentService(
                messageRepository, fileStorageServiceProvider, telegramFileServiceProvider);
    }

    @Bean
    public MessageTelegramCommandHandler messageTelegramCommandHandler(
            ObjectProvider<TelegramBot> telegramBotProvider,
            TypingIndicatorService typingIndicatorService,
            MessageLocalizationService messageLocalizationService,
            TelegramUserService telegramUserService,
            TelegramUserSessionService telegramUserSessionService,
            TelegramMessageService telegramMessageService,
            AIGatewayRegistry aiGatewayRegistry,
            OpenDaimonMessageService messageService,
            AIRequestPipeline aiRequestPipeline,
            TelegramProperties telegramProperties,
            UserModelPreferenceService userModelPreferenceService,
            PersistentKeyboardService persistentKeyboardService,
            ReplyImageAttachmentService replyImageAttachmentService) {
        TelegramMessageSender messageSender = new TelegramMessageSender(
                telegramBotProvider, messageLocalizationService, persistentKeyboardService);
        TelegramMessageHandlerActions actions = new TelegramMessageHandlerActions(
                telegramUserService, telegramUserSessionService, telegramMessageService,
                aiGatewayRegistry, messageService, aiRequestPipeline, telegramProperties,
                userModelPreferenceService, persistentKeyboardService, replyImageAttachmentService,
                messageSender);
        ExDomainFsm<MessageHandlerContext, MessageHandlerState, MessageHandlerEvent> handlerFsm =
                MessageHandlerFsmFactory.create(actions);
        return new MessageTelegramCommandHandler(
                telegramBotProvider,
                typingIndicatorService,
                messageLocalizationService,
                handlerFsm,
                telegramMessageService,
                telegramProperties,
                persistentKeyboardService);
    }

    /**
     * Test bot that captures sent messages for assertions.
     */
    public static class RecordingTelegramBot extends TelegramBot {

        private final List<String> sentMessages = new ArrayList<>();

        public RecordingTelegramBot(
                TelegramProperties config,
                CommandSyncService commandSyncService,
                TelegramUserService userService) {
            super(config, commandSyncService, userService);
        }

        @Override
        public void sendMessage(Long chatId, String text, Integer replyToMessageId) {
            sentMessages.add(text);
        }

        @Override
        public void sendErrorMessage(Long chatId, String errorMessage, Integer replyToMessageId) {
            sentMessages.add(errorMessage);
        }

        @Override
        public void showTyping(Long chatId) {
            // no-op
        }

        public List<String> sentMessages() {
            return sentMessages;
        }

        public void clearMessages() {
            sentMessages.clear();
        }
    }
}
