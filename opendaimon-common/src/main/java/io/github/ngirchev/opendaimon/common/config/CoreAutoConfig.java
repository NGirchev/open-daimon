package io.github.ngirchev.opendaimon.common.config;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.springframework.web.client.RestTemplate;
import io.github.ngirchev.opendaimon.common.storage.service.FileStorageService;
import io.github.ngirchev.opendaimon.bulkhead.service.IUserPriorityService;
import io.github.ngirchev.opendaimon.bulkhead.service.NoOpPriorityRequestExecutor;
import io.github.ngirchev.opendaimon.bulkhead.service.PriorityRequestExecutor;
import io.github.ngirchev.opendaimon.bulkhead.service.impl.NoOpUserPriorityService;
import io.github.ngirchev.opendaimon.common.ai.command.AICommand;
import io.github.ngirchev.opendaimon.common.ai.factory.AICommandFactory;
import io.github.ngirchev.opendaimon.common.ai.factory.AICommandFactoryRegistry;
import io.github.ngirchev.opendaimon.common.ai.factory.ConversationHistoryAICommandFactory;
import io.github.ngirchev.opendaimon.common.ai.factory.DefaultAICommandFactory;
import io.github.ngirchev.opendaimon.common.command.CommandHandlerRegistry;
import io.github.ngirchev.opendaimon.common.command.ICommand;
import io.github.ngirchev.opendaimon.common.command.ICommandHandler;
import io.github.ngirchev.opendaimon.common.command.IChatCommand;
import io.github.ngirchev.opendaimon.common.meter.OpenDaimonMeterRegistry;
import io.github.ngirchev.opendaimon.common.repository.AssistantRoleRepository;
import io.github.ngirchev.opendaimon.common.repository.BugreportRepository;
import io.github.ngirchev.opendaimon.common.repository.ConversationThreadRepository;
import io.github.ngirchev.opendaimon.common.repository.OpenDaimonMessageRepository;
import io.github.ngirchev.opendaimon.common.repository.UserRepository;
import io.github.ngirchev.opendaimon.common.service.*;
import io.github.ngirchev.opendaimon.common.service.impl.AssistantRoleServiceImpl;
import io.github.ngirchev.opendaimon.bulkhead.service.IUserService;
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
    public OpenDaimonMessageService messageService(
            OpenDaimonMessageRepository messageRepository,
            ConversationThreadService conversationThreadService,
            AssistantRoleService assistantRoleService,
            CoreCommonProperties coreCommonProperties,
            TokenCounter tokenCounter,
            ObjectProvider<OpenDaimonMessageService> messageServiceSelfProvider) {
        return new OpenDaimonMessageService(
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
            OpenDaimonMessageRepository messageRepository) {
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
    @ConditionalOnProperty(name = "open-daimon.common.bulkhead.enabled", havingValue = "false", matchIfMissing = true)
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
    @ConditionalOnProperty(name = "open-daimon.common.bulkhead.enabled", havingValue = "false", matchIfMissing = true)
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
            OpenDaimonMeterRegistry meterRegistry,
            CommandHandlerRegistry registry,
            PriorityRequestExecutor priorityRequestExecutor) {
        return new CommandSyncService(meterRegistry, registry, priorityRequestExecutor);
    }

    @Bean
    @ConditionalOnMissingBean(ConversationHistoryAICommandFactory.class)
    @ConditionalOnProperty(value = "open-daimon.common.manual-conversation-history.enabled", havingValue = "true")
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
    @ConditionalOnProperty(value = "open-daimon.common.manual-conversation-history.enabled", havingValue = "true")
    public ConversationContextBuilderService contextBuilderService(
            OpenDaimonMessageRepository messageRepository,
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
            OpenDaimonMessageRepository messageRepository,
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
    public OpenDaimonMeterRegistry openDaimonMeterRegistry(MeterRegistry meterRegistry) {
        return new OpenDaimonMeterRegistry(meterRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    public IUserService userService(UserRepository userRepository) {
        return new UserService(userRepository);
    }

}
