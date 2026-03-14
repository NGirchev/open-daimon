package io.github.ngirchev.aibot.common.config;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.ApplicationContext;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.springframework.web.client.RestTemplate;
import io.github.ngirchev.aibot.common.storage.service.FileStorageService;
import io.github.ngirchev.aibot.bulkhead.service.IUserPriorityService;
import io.github.ngirchev.aibot.bulkhead.service.NoOpPriorityRequestExecutor;
import io.github.ngirchev.aibot.bulkhead.service.PriorityRequestExecutor;
import io.github.ngirchev.aibot.bulkhead.service.impl.NoOpUserPriorityService;
import io.github.ngirchev.aibot.common.ai.command.AICommand;
import io.github.ngirchev.aibot.common.ai.factory.AICommandFactory;
import io.github.ngirchev.aibot.common.ai.factory.AICommandFactoryRegistry;
import io.github.ngirchev.aibot.common.ai.factory.ConversationHistoryAICommandFactory;
import io.github.ngirchev.aibot.common.ai.factory.DefaultAICommandFactory;
import io.github.ngirchev.aibot.common.command.CommandHandlerRegistry;
import io.github.ngirchev.aibot.common.command.ICommand;
import io.github.ngirchev.aibot.common.command.ICommandHandler;
import io.github.ngirchev.aibot.common.command.IChatCommand;
import io.github.ngirchev.aibot.common.meter.AIBotMeterRegistry;
import io.github.ngirchev.aibot.common.repository.AssistantRoleRepository;
import io.github.ngirchev.aibot.common.repository.BugreportRepository;
import io.github.ngirchev.aibot.common.repository.ConversationThreadRepository;
import io.github.ngirchev.aibot.common.repository.AIBotMessageRepository;
import io.github.ngirchev.aibot.common.repository.UserRepository;
import io.github.ngirchev.aibot.common.service.*;
import io.github.ngirchev.aibot.common.service.impl.AssistantRoleServiceImpl;
import io.github.ngirchev.aibot.bulkhead.service.IUserService;
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
    @ConditionalOnMissingBean(MessageSource.class)
    public MessageSource messageSource() {
        ReloadableResourceBundleMessageSource source = new ReloadableResourceBundleMessageSource();
        source.setBasenames(
                "classpath:messages/common",
                "classpath:messages/telegram",
                "classpath:messages/rest",
                "classpath:messages/ui"
        );
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        return source;
    }

    @Bean
    @ConditionalOnMissingBean
    public MessageLocalizationService messageLocalizationService(MessageSource messageSource) {
        return new MessageLocalizationService(messageSource);
    }

    @Bean
    @ConditionalOnMissingBean
    public AIBotMessageService messageService(
            AIBotMessageRepository messageRepository,
            ConversationThreadService conversationThreadService,
            AssistantRoleService assistantRoleService,
            CoreCommonProperties coreCommonProperties,
            TokenCounter tokenCounter,
            ObjectProvider<AIBotMessageService> messageServiceSelfProvider) {
        return new AIBotMessageService(
                messageRepository,
                conversationThreadService,
                assistantRoleService,
                coreCommonProperties,
                tokenCounter,
                messageServiceSelfProvider);
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
    public AssistantRoleService assistantRoleService(
            AssistantRoleRepository assistantRoleRepository,
            ObjectProvider<AssistantRoleService> assistantRoleServiceSelfProvider) {
        return new AssistantRoleServiceImpl(assistantRoleRepository, assistantRoleServiceSelfProvider);
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

    // ==================== NoOp implementations (when bulkhead is disabled) ====================

    /**
     * NoOp implementation of IUserPriorityService.
     * Created when bulkhead is disabled or not configured.
     * Always returns REGULAR priority.
     */
    @Bean
    @ConditionalOnMissingBean(IUserPriorityService.class)
    @ConditionalOnProperty(name = "ai-bot.common.bulkhead.enabled", havingValue = "false", matchIfMissing = true)
    public IUserPriorityService noOpUserPriorityService() {
        return new NoOpUserPriorityService();
    }

    /**
     * NoOp implementation of PriorityRequestExecutor.
     * Created when bulkhead is disabled or not configured.
     * Executes tasks directly without limits.
     */
    @Bean
    @ConditionalOnMissingBean(PriorityRequestExecutor.class)
    @ConditionalOnProperty(name = "ai-bot.common.bulkhead.enabled", havingValue = "false", matchIfMissing = true)
    public PriorityRequestExecutor noOpPriorityRequestExecutor() {
        return new NoOpPriorityRequestExecutor();
    }

    // ==================== Core beans ====================

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
            PriorityRequestExecutor priorityRequestExecutor) {
        return new CommandSyncService(meterRegistry, registry, priorityRequestExecutor);
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

}
