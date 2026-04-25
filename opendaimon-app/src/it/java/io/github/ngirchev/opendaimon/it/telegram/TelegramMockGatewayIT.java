package io.github.ngirchev.opendaimon.it.telegram;

import io.github.ngirchev.opendaimon.common.command.ICommand;
import io.github.ngirchev.opendaimon.common.command.ICommandType;
import io.github.ngirchev.opendaimon.telegram.service.PersistentKeyboardService;
import io.github.ngirchev.opendaimon.telegram.service.ReplyImageAttachmentService;
import io.github.ngirchev.opendaimon.telegram.service.TelegramAgentStreamView;
import io.github.ngirchev.opendaimon.telegram.service.TelegramChatPacerImpl;
import io.github.ngirchev.opendaimon.telegram.service.TelegramFileService;
import io.github.ngirchev.opendaimon.common.service.ChatOwnerLookup;
import io.github.ngirchev.opendaimon.common.repository.UserRepository;
import io.github.ngirchev.opendaimon.telegram.service.ChatSettingsOwnerResolver;
import io.github.ngirchev.opendaimon.telegram.service.ChatSettingsService;
import io.github.ngirchev.opendaimon.telegram.service.TelegramChatOwnerLookup;
import io.github.ngirchev.opendaimon.telegram.service.TelegramGroupService;
import io.github.ngirchev.opendaimon.telegram.repository.TelegramGroupRepository;
import io.github.ngirchev.opendaimon.common.storage.service.FileStorageService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.springframework.test.context.ActiveProfiles;
import org.telegram.telegrambots.meta.api.objects.Chat;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
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
import io.github.ngirchev.opendaimon.common.command.ICommandHandler;
import io.github.ngirchev.opendaimon.common.config.CoreCommonProperties;
import io.github.ngirchev.opendaimon.common.config.CoreFlywayConfig;
import io.github.ngirchev.opendaimon.common.config.CoreJpaConfig;
import io.github.ngirchev.opendaimon.common.meter.OpenDaimonMeterRegistry;
import io.github.ngirchev.opendaimon.common.repository.OpenDaimonMessageRepository;
import io.github.ngirchev.opendaimon.common.repository.AssistantRoleRepository;
import io.github.ngirchev.opendaimon.common.repository.ConversationThreadRepository;
import io.github.ngirchev.opendaimon.common.service.*;
import io.github.ngirchev.opendaimon.common.service.impl.AssistantRoleServiceImpl;
import io.github.ngirchev.opendaimon.it.ITTestConfiguration;
import io.github.ngirchev.opendaimon.telegram.TelegramBot;
import io.github.ngirchev.opendaimon.telegram.command.TelegramCommand;
import io.github.ngirchev.opendaimon.telegram.command.TelegramCommandType;
import io.github.ngirchev.opendaimon.telegram.command.handler.impl.MessageTelegramCommandHandler;
import io.github.ngirchev.opendaimon.telegram.command.handler.impl.fsm.MessageHandlerContext;
import io.github.ngirchev.opendaimon.telegram.command.handler.impl.fsm.MessageHandlerEvent;
import io.github.ngirchev.opendaimon.telegram.command.handler.impl.fsm.MessageHandlerFsmFactory;
import io.github.ngirchev.opendaimon.telegram.command.handler.impl.fsm.MessageHandlerState;
import io.github.ngirchev.opendaimon.telegram.command.handler.impl.fsm.TelegramMessageHandlerActions;
import io.github.ngirchev.opendaimon.telegram.command.handler.impl.fsm.TelegramMessageSender;
import io.github.ngirchev.fsm.impl.extended.ExDomainFsm;
import io.github.ngirchev.opendaimon.telegram.config.TelegramFlywayConfig;
import io.github.ngirchev.opendaimon.telegram.config.TelegramJpaConfig;
import io.github.ngirchev.opendaimon.common.storage.config.StorageProperties;
import io.github.ngirchev.opendaimon.telegram.config.TelegramProperties;
import io.github.ngirchev.opendaimon.telegram.repository.TelegramUserRepository;
import io.github.ngirchev.opendaimon.telegram.repository.TelegramUserSessionRepository;
import io.github.ngirchev.opendaimon.telegram.service.TelegramMessageService;
import io.github.ngirchev.opendaimon.telegram.service.TelegramUserService;
import io.github.ngirchev.opendaimon.telegram.service.TelegramUserSessionService;
import io.github.ngirchev.opendaimon.telegram.service.TypingIndicatorService;
import io.github.ngirchev.opendaimon.test.AbstractContainerIT;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest(
        classes = ITTestConfiguration.class,
        properties = {
                "spring.main.banner-mode=off",
                "spring.autoconfigure.exclude=" +
                        "io.github.ngirchev.opendaimon.common.config.CoreAutoConfig," +
                        "io.github.ngirchev.opendaimon.bulkhead.config.BulkHeadAutoConfig," +
                        "io.github.ngirchev.opendaimon.telegram.config.TelegramAutoConfig," +
                        "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration," +
                        "org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration," +
                        "org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration," +
                        "org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionAutoConfiguration," +
                        "org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration," +
                        "org.springframework.ai.model.openai.autoconfigure.OpenAiImageAutoConfiguration," +
                        "org.springframework.ai.model.openai.autoconfigure.OpenAiModerationAutoConfiguration"
        }
)
@ActiveProfiles("integration-test")
@EnableConfigurationProperties(CoreCommonProperties.class)
@Import({
        CoreFlywayConfig.class,
        CoreJpaConfig.class,
        TelegramFlywayConfig.class,
        TelegramJpaConfig.class,
        TelegramMockGatewayIT.TestOverrides.class
})
class TelegramMockGatewayIT extends AbstractContainerIT {

    @TestConfiguration
    static class TestOverrides {

        @Bean
        public IUserPriorityService userPriorityService() {
            return userId -> UserPriority.REGULAR;
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
                BulkHeadProperties bulkHeadProperties
        ) {
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
                IUserPriorityService userPriorityService
        ) {
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
                OpenDaimonMessageRepository messageRepository
        ) {
            return new ConversationThreadService(threadRepository, messageRepository);
        }

        @Bean
        public OpenDaimonMessageService openDaimonMessageService(
                OpenDaimonMessageRepository messageRepository,
                ConversationThreadService conversationThreadService,
                AssistantRoleService assistantRoleService,
                CoreCommonProperties coreCommonProperties,
                MessageLocalizationService messageLocalizationService,
                ObjectProvider<OpenDaimonMessageService> messageServiceSelfProvider
        ) {
            return new OpenDaimonMessageService(
                    messageRepository,
                    conversationThreadService,
                    assistantRoleService,
                    coreCommonProperties,
                    messageLocalizationService,
                    new TokenCounter(),
                    messageServiceSelfProvider
            );
        }

        @Bean
        public MessageSource messageSource() {
            ReloadableResourceBundleMessageSource source = new ReloadableResourceBundleMessageSource();
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
                TelegramUserRepository telegramUserRepository
        ) {
            return new TelegramUserSessionService(telegramUserSessionRepository, telegramUserRepository);
        }

        @Bean
        public TelegramUserService telegramUserService(
                TelegramUserRepository telegramUserRepository,
                TelegramUserSessionService telegramUserSessionService,
                AssistantRoleService assistantRoleService
        ) {
            return new TelegramUserService(telegramUserRepository, telegramUserSessionService, assistantRoleService, false);
        }

        @Bean
        public ObjectProvider<StorageProperties> storagePropertiesProvider() {
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
                ObjectProvider<TelegramMessageService> telegramMessageServiceSelfProvider,
                ChatOwnerLookup chatOwnerLookup,
                ChatSettingsService chatSettingsService
        ) {
            return new TelegramMessageService(
                    messageService,
                    telegramUserService,
                    coreCommonProperties,
                    messageLocalizationService,
                    storagePropertiesProvider,
                    conversationThreadService,
                    telegramMessageServiceSelfProvider,
                    chatOwnerLookup,
                    chatSettingsService
            );
        }

        @Bean
        public ScheduledExecutorService typingIndicatorScheduledExecutor() {
            return Executors.newScheduledThreadPool(1, r -> {
                Thread thread = new Thread(r, "typing-indicator-test-");
                thread.setDaemon(true);
                return thread;
            });
        }

        @Bean
        public TypingIndicatorService typingIndicatorService(
                ObjectProvider<TelegramBot> telegramBotProvider,
                ScheduledExecutorService typingIndicatorScheduledExecutor
        ) {
            return new TypingIndicatorService(telegramBotProvider, typingIndicatorScheduledExecutor);
        }

        @Bean
        public RecordingTelegramBot telegramBot(
                TelegramProperties telegramProperties,
                CommandSyncService commandSyncService,
                TelegramUserService telegramUserService
        ) {
            return new RecordingTelegramBot(
                    telegramProperties,
                    commandSyncService,
                    telegramUserService
            );
        }

        @Bean
        public TelegramGroupService telegramGroupService(
                TelegramGroupRepository telegramGroupRepository,
                io.github.ngirchev.opendaimon.common.service.AssistantRoleService assistantRoleService) {
            return new TelegramGroupService(telegramGroupRepository, assistantRoleService, false);
        }

        @Bean
        public ChatSettingsService chatSettingsService(
                TelegramUserService telegramUserService,
                TelegramGroupService telegramGroupService) {
            return new ChatSettingsService(telegramUserService, telegramGroupService);
        }

        @Bean
        public ChatSettingsOwnerResolver chatSettingsOwnerResolver(
                TelegramUserService telegramUserService,
                TelegramGroupService telegramGroupService) {
            return new ChatSettingsOwnerResolver(telegramUserService, telegramGroupService);
        }

        @Bean
        public ChatOwnerLookup chatOwnerLookup(ChatSettingsOwnerResolver resolver) {
            return new TelegramChatOwnerLookup(resolver);
        }

        @Bean
        public PersistentKeyboardService persistentKeyboardService(
                CoreCommonProperties coreCommonProperties,
                ObjectProvider<TelegramBot> telegramBotProvider,
                TelegramProperties telegramProperties,
                MessageLocalizationService messageLocalizationService,
                UserRepository userRepository
        ) {
            return new PersistentKeyboardService(
                    coreCommonProperties, telegramBotProvider, telegramProperties,
                    messageLocalizationService, userRepository,
                    new TelegramChatPacerImpl(telegramProperties));
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
                ChatSettingsService chatSettingsService,
                PersistentKeyboardService persistentKeyboardService,
                ReplyImageAttachmentService replyImageAttachmentService
        ) {
            var telegramChatPacer = new TelegramChatPacerImpl(telegramProperties);
            TelegramMessageSender messageSender = new TelegramMessageSender(
                    telegramBotProvider, messageLocalizationService, persistentKeyboardService, telegramChatPacer);
            TelegramAgentStreamView agentStreamView = new TelegramAgentStreamView(
                    messageSender, telegramChatPacer, telegramProperties);
            TelegramMessageHandlerActions actions = new TelegramMessageHandlerActions(
                    telegramUserService, telegramUserSessionService, telegramMessageService,
                    aiGatewayRegistry, messageService, aiRequestPipeline, telegramProperties,
                    chatSettingsService, persistentKeyboardService, replyImageAttachmentService,
                    messageSender, null, null, agentStreamView, 10, false);
            ExDomainFsm<MessageHandlerContext, MessageHandlerState, MessageHandlerEvent> handlerFsm =
                    MessageHandlerFsmFactory.create(actions);
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
    }

    @Autowired
    RecordingTelegramBot telegramBot;

    @Autowired
    MessageTelegramCommandHandler messageHandler;

    @Autowired
    AIGatewayRegistry aiGatewayRegistry;

    @Autowired
    OpenDaimonMessageRepository messageRepository;

    @Test
    void messageCommand_usesMockGateway_andSendsTelegramReply() {
        var update = new Update();

        var from = new User();
        from.setId(350001752L);
        from.setUserName("integration-user");
        from.setFirstName("Integration");
        from.setLastName("Test");
        from.setLanguageCode("ru");

        var msg = new Message();
        msg.setMessageId(1);
        var chat = new Chat();
        chat.setId(350001752L);
        msg.setChat(chat);
        msg.setText("Hi");
        msg.setFrom(from);
        update.setMessage(msg);

        var command = new TelegramCommand(
                null,
                msg.getChatId(),
                new TelegramCommandType(TelegramCommand.MESSAGE),
                update,
                msg.getText()
        );
        command.stream(false);

        messageHandler.handle(command);

        assertThat(aiGatewayRegistry.getAiGateway(MockGateway.class)).isNotNull();
        assertThat(telegramBot.sentMessages()).isNotEmpty();
        assertThat(telegramBot.sentMessages().getLast()).isNotBlank();
        assertThat(messageRepository.count()).isGreaterThanOrEqualTo(2);
    }

    static class RecordingTelegramBot extends TelegramBot {

        private final List<String> sentMessages = new ArrayList<>();

        RecordingTelegramBot(
                TelegramProperties config,
                CommandSyncService commandSyncService,
                TelegramUserService userService
        ) {
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
            // no-op for test
        }

        public List<String> sentMessages() {
            return sentMessages;
        }
    }
}
