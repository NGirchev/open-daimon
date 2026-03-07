package ru.girchev.aibot.common.config;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.web.client.RestTemplate;
import ru.girchev.aibot.common.storage.service.FileStorageService;
import ru.girchev.aibot.bulkhead.service.IUserPriorityService;
import ru.girchev.aibot.bulkhead.service.NoOpPriorityRequestExecutor;
import ru.girchev.aibot.bulkhead.service.PriorityRequestExecutor;
import ru.girchev.aibot.bulkhead.service.impl.NoOpUserPriorityService;
import ru.girchev.aibot.common.ai.command.AICommand;
import ru.girchev.aibot.common.ai.factory.AICommandFactory;
import ru.girchev.aibot.common.ai.factory.AICommandFactoryRegistry;
import ru.girchev.aibot.common.ai.factory.ConversationHistoryAICommandFactory;
import ru.girchev.aibot.common.ai.factory.DefaultAICommandFactory;
import ru.girchev.aibot.common.command.CommandHandlerRegistry;
import ru.girchev.aibot.common.command.ICommand;
import ru.girchev.aibot.common.command.ICommandHandler;
import ru.girchev.aibot.common.command.IChatCommand;
import ru.girchev.aibot.common.meter.AIBotMeterRegistry;
import ru.girchev.aibot.common.repository.AssistantRoleRepository;
import ru.girchev.aibot.common.repository.BugreportRepository;
import ru.girchev.aibot.common.repository.ConversationThreadRepository;
import ru.girchev.aibot.common.repository.AIBotMessageRepository;
import ru.girchev.aibot.common.repository.UserRepository;
import ru.girchev.aibot.common.service.*;
import ru.girchev.aibot.common.service.impl.AssistantRoleServiceImpl;
import ru.girchev.aibot.bulkhead.service.IUserService;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

@AutoConfiguration
@EnableConfigurationProperties(CoreCommonProperties.class)
@Import({
        CoreJpaConfig.class,
        CoreFlywayConfig.class,
        AsyncConfig.class
})
public class CoreAutoConfig {

    @Bean
    @ConditionalOnMissingBean(RestTemplate.class)
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder.build();
    }

    @Bean
    @ConditionalOnMissingBean
    public AIBotMessageService messageService(
            AIBotMessageRepository messageRepository,
            ConversationThreadService conversationThreadService,
            AssistantRoleService assistantRoleService,
            CoreCommonProperties coreCommonProperties,
            TokenCounter tokenCounter) {
        return new AIBotMessageService(
                messageRepository, 
                conversationThreadService,
                assistantRoleService,
                coreCommonProperties,
                tokenCounter);
    }

    @Bean
    @ConditionalOnMissingBean
    public AIGatewayRegistry aiGatewayRegister() {
        return new AIGatewayRegistry();
    }

    @Bean
    @ConditionalOnMissingBean
    public AICommandFactoryRegistry aiCommandFactoryRegistry(List<AICommandFactory<?, ?>> factories) {
        return new AICommandFactoryRegistry(factories);
    }

    @Bean
    @ConditionalOnMissingBean
    public CommandHandlerRegistry telegramCommandHandlerRegistry(
            List<ICommandHandler<?, ?, ?>> handlers) {
        return new CommandHandlerRegistry(handlers);
    }

    @Bean
    @ConditionalOnMissingBean
    public BugreportService bugreportService(BugreportRepository bugreportRepository) {
        return new BugreportService(bugreportRepository);
    }

    @Bean
    @ConditionalOnMissingBean
    public AssistantRoleService assistantRoleService(AssistantRoleRepository assistantRoleRepository) {
        return new AssistantRoleServiceImpl(assistantRoleRepository);
    }

    @Bean
    @ConditionalOnMissingBean
    public TokenCounter tokenCounter(CoreCommonProperties coreCommonProperties) {
        return new TokenCounter(coreCommonProperties);
    }

    @Bean
    @ConditionalOnMissingBean
    public ConversationThreadService conversationThreadService(
            ConversationThreadRepository threadRepository,
            AIBotMessageRepository messageRepository) {
        return new ConversationThreadService(
            threadRepository,
            messageRepository
        );
    }

    @Bean
    @ConditionalOnMissingBean(ObjectMapper.class)
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    // ==================== NoOp реализации (когда bulkhead выключен) ====================

    /**
     * NoOp реализация IUserPriorityService.
     * Создается когда bulkhead выключен или не настроен.
     * Всегда возвращает приоритет REGULAR.
     */
    @Bean
    @ConditionalOnMissingBean(IUserPriorityService.class)
    @ConditionalOnProperty(name = "ai-bot.common.bulkhead.enabled", havingValue = "false", matchIfMissing = true)
    public IUserPriorityService noOpUserPriorityService() {
        return new NoOpUserPriorityService();
    }

    /**
     * NoOp реализация PriorityRequestExecutor.
     * Создается когда bulkhead выключен или не настроен.
     * Просто выполняет задачи напрямую без ограничений.
     */
    @Bean
    @ConditionalOnMissingBean(PriorityRequestExecutor.class)
    @ConditionalOnProperty(name = "ai-bot.common.bulkhead.enabled", havingValue = "false", matchIfMissing = true)
    public PriorityRequestExecutor noOpPriorityRequestExecutor() {
        return new NoOpPriorityRequestExecutor();
    }

    // ==================== Основные бины ====================

    @Bean
    @ConditionalOnMissingBean(DefaultAICommandFactory.class)
    public AICommandFactory<AICommand, ICommand<?>> defaultAiCommandFactory(
            IUserPriorityService userPriorityService,
            CoreCommonProperties coreCommonProperties) {
        return new DefaultAICommandFactory(
                userPriorityService,
                coreCommonProperties.getMaxOutputTokens(),
                coreCommonProperties.getMaxReasoningTokens());
    }

    @Bean
    @ConditionalOnMissingBean
    public CommandSyncService commandSyncService(
            AIBotMeterRegistry meterRegistry,
            CommandHandlerRegistry registry,
            PriorityRequestExecutor priorityRequestExecutor,
            IUserPriorityService priorityService) {
        return new CommandSyncService(meterRegistry, registry, priorityRequestExecutor, priorityService);
    }

    @Bean
    @ConditionalOnMissingBean(ConversationHistoryAICommandFactory.class)
    @ConditionalOnProperty(value = "ai-bot.common.manual-conversation-history.enabled", havingValue = "true")
    public AICommandFactory<AICommand, IChatCommand<?>> conversationHistoryAiCommandFactory(
            CoreCommonProperties coreCommonProperties,
            ConversationContextBuilderService conversationContextBuilderService,
            ConversationThreadService conversationThreadService,
            AssistantRoleService assistantRoleService,
            SummarizationService summarizationService
    ) {
        return new ConversationHistoryAICommandFactory(
                coreCommonProperties.getMaxOutputTokens(),
                coreCommonProperties.getMaxReasoningTokens(),
                conversationContextBuilderService,
                conversationThreadService,
                assistantRoleService,
                summarizationService);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(value = "ai-bot.common.manual-conversation-history.enabled", havingValue = "true")
    public ConversationContextBuilderService contextBuilderService(
            AIBotMessageRepository messageRepository,
            TokenCounter tokenCounter,
            CoreCommonProperties coreCommonProperties,
            ObjectProvider<FileStorageService> fileStorageServiceProvider) {
        return new ConversationContextBuilderService(
                messageRepository,
                tokenCounter,
                coreCommonProperties,
                fileStorageServiceProvider
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public SummarizationService summarizationService(
            AIBotMessageRepository messageRepository,
            ConversationThreadService threadService,
            AIGatewayRegistry aiGatewayRegistry,
            CoreCommonProperties coreCommonProperties,
            ObjectMapper objectMapper) {
        return new SummarizationService(
                messageRepository,
                threadService,
                aiGatewayRegistry,
                coreCommonProperties,
                objectMapper
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public AIBotMeterRegistry aiBotMeterRegistry(MeterRegistry meterRegistry) {
        return new AIBotMeterRegistry(meterRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    public IUserService userService(UserRepository userRepository) {
        return new UserService(userRepository);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "ai-bot.common.admin.enabled", havingValue = "true", matchIfMissing = false)
    public AdminInitializer adminInitializer(
            CoreCommonProperties coreCommonProperties,
            ApplicationContext applicationContext) {
        return new AdminInitializer(coreCommonProperties, applicationContext);
    }
}
