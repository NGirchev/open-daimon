package io.github.ngirchev.aibot.it.telegram;

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
import io.github.ngirchev.aibot.ai.mock.service.MockGateway;
import io.github.ngirchev.aibot.bulkhead.config.BulkHeadProperties;
import io.github.ngirchev.aibot.bulkhead.model.UserPriority;
import io.github.ngirchev.aibot.bulkhead.service.IUserPriorityService;
import io.github.ngirchev.aibot.bulkhead.service.PriorityRequestExecutor;
import io.github.ngirchev.aibot.common.ai.factory.AICommandFactory;
import io.github.ngirchev.aibot.common.ai.factory.AICommandFactoryRegistry;
import io.github.ngirchev.aibot.common.ai.factory.DefaultAICommandFactory;
import io.github.ngirchev.aibot.common.command.CommandHandlerRegistry;
import io.github.ngirchev.aibot.common.command.ICommandHandler;
import io.github.ngirchev.aibot.common.config.CoreCommonProperties;
import io.github.ngirchev.aibot.common.config.CoreFlywayConfig;
import io.github.ngirchev.aibot.common.config.CoreJpaConfig;
import io.github.ngirchev.aibot.common.meter.AIBotMeterRegistry;
import io.github.ngirchev.aibot.common.repository.AIBotMessageRepository;
import io.github.ngirchev.aibot.common.repository.AssistantRoleRepository;
import io.github.ngirchev.aibot.common.repository.ConversationThreadRepository;
import io.github.ngirchev.aibot.common.service.*;
import io.github.ngirchev.aibot.common.service.impl.AssistantRoleServiceImpl;
import io.github.ngirchev.aibot.it.ITTestConfiguration;
import io.github.ngirchev.aibot.telegram.TelegramBot;
import io.github.ngirchev.aibot.telegram.command.TelegramCommand;
import io.github.ngirchev.aibot.telegram.command.TelegramCommandType;
import io.github.ngirchev.aibot.telegram.command.handler.impl.MessageTelegramCommandHandler;
import io.github.ngirchev.aibot.telegram.config.TelegramFlywayConfig;
import io.github.ngirchev.aibot.telegram.config.TelegramJpaConfig;
import io.github.ngirchev.aibot.common.storage.config.StorageProperties;
import io.github.ngirchev.aibot.telegram.config.TelegramProperties;
import io.github.ngirchev.aibot.telegram.repository.TelegramUserRepository;
import io.github.ngirchev.aibot.telegram.repository.TelegramUserSessionRepository;
import io.github.ngirchev.aibot.telegram.service.TelegramMessageService;
import io.github.ngirchev.aibot.telegram.service.TelegramUserService;
import io.github.ngirchev.aibot.telegram.service.TelegramUserSessionService;
import io.github.ngirchev.aibot.telegram.service.TypingIndicatorService;
import io.github.ngirchev.aibot.test.TestDatabaseConfiguration;

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
                        "io.github.ngirchev.aibot.common.config.CoreAutoConfig," +
                        "io.github.ngirchev.aibot.bulkhead.config.BulkHeadAutoConfig," +
                        "io.github.ngirchev.aibot.telegram.config.TelegramAutoConfig," +
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
        TestDatabaseConfiguration.class,
        CoreFlywayConfig.class,
        CoreJpaConfig.class,
        TelegramFlywayConfig.class,
        TelegramJpaConfig.class,
        TelegramMockGatewayIT.TestOverrides.class
})
class TelegramMockGatewayIT {

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
                    coreCommonProperties.getMaxOutputTokens(),
                    coreCommonProperties.getMaxReasoningTokens());
        }

        @Bean
        public AICommandFactoryRegistry aiCommandFactoryRegistry(List<AICommandFactory<?, ?>> factories) {
            return new AICommandFactoryRegistry(factories);
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
        public AIBotMeterRegistry aiBotMeterRegistry(MeterRegistry meterRegistry) {
            return new AIBotMeterRegistry(meterRegistry);
        }

        @Bean
        public CommandHandlerRegistry commandHandlerRegistry(List<ICommandHandler<?, ?, ?>> handlers) {
            return new CommandHandlerRegistry(handlers);
        }

        @Bean
        public CommandSyncService commandSyncService(
                AIBotMeterRegistry meterRegistry,
                CommandHandlerRegistry registry,
                PriorityRequestExecutor priorityRequestExecutor,
                IUserPriorityService userPriorityService
        ) {
            return new CommandSyncService(meterRegistry, registry, priorityRequestExecutor, userPriorityService);
        }

        @Bean
        public AssistantRoleService assistantRoleService(AssistantRoleRepository assistantRoleRepository) {
            return new AssistantRoleServiceImpl(assistantRoleRepository);
        }

        @Bean
        public TokenCounter tokenCounter(CoreCommonProperties coreCommonProperties) {
            return new TokenCounter(coreCommonProperties);
        }

        @Bean
        public ConversationThreadService conversationThreadService(
                ConversationThreadRepository threadRepository,
                AIBotMessageRepository messageRepository
        ) {
            return new ConversationThreadService(threadRepository, messageRepository);
        }

        @Bean
        public AIBotMessageService aiBotMessageService(
                AIBotMessageRepository messageRepository,
                ConversationThreadService conversationThreadService,
                AssistantRoleService assistantRoleService,
                CoreCommonProperties coreCommonProperties,
                TokenCounter tokenCounter
        ) {
            return new AIBotMessageService(
                    messageRepository,
                    conversationThreadService,
                    assistantRoleService,
                    coreCommonProperties,
                    tokenCounter
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
            props.setWhitelistExceptions("");
            props.setWhitelistChannelIdExceptions("");
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
            return new TelegramUserService(telegramUserRepository, telegramUserSessionService, assistantRoleService);
        }

        @Bean
        public ObjectProvider<StorageProperties> storagePropertiesProvider() {
            ObjectProvider<StorageProperties> provider = mock(ObjectProvider.class);
            when(provider.getIfAvailable()).thenReturn(null);
            return provider;
        }

        @Bean
        public TelegramMessageService telegramMessageService(
                AIBotMessageService messageService,
                TelegramUserService telegramUserService,
                CoreCommonProperties coreCommonProperties,
                ObjectProvider<StorageProperties> storagePropertiesProvider
        ) {
            return new TelegramMessageService(
                    messageService,
                    telegramUserService,
                    coreCommonProperties,
                    storagePropertiesProvider
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
        public MessageTelegramCommandHandler messageTelegramCommandHandler(
                ObjectProvider<TelegramBot> telegramBotProvider,
                TypingIndicatorService typingIndicatorService,
                MessageLocalizationService messageLocalizationService,
                TelegramUserService telegramUserService,
                TelegramUserSessionService telegramUserSessionService,
                TelegramMessageService telegramMessageService,
                AIGatewayRegistry aiGatewayRegistry,
                AIBotMessageService messageService,
                AICommandFactoryRegistry aiCommandFactoryRegistry,
                TelegramProperties telegramProperties
        ) {
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

    @Autowired
    RecordingTelegramBot telegramBot;

    @Autowired
    MessageTelegramCommandHandler messageHandler;

    @Autowired
    AIGatewayRegistry aiGatewayRegistry;

    @Autowired
    AIBotMessageRepository messageRepository;

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
