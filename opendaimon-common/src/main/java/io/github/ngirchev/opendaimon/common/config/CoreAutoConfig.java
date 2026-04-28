package io.github.ngirchev.opendaimon.common.config;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.springframework.web.client.RestTemplate;
import io.github.ngirchev.opendaimon.bulkhead.service.IUserPriorityService;
import io.github.ngirchev.opendaimon.common.config.FeatureToggle;
import io.github.ngirchev.opendaimon.bulkhead.service.NoOpPriorityRequestExecutor;
import io.github.ngirchev.opendaimon.bulkhead.service.PriorityRequestExecutor;
import io.github.ngirchev.opendaimon.bulkhead.service.impl.NoOpUserPriorityService;
import io.github.ngirchev.opendaimon.common.ai.ModelDescriptionCache;
import io.github.ngirchev.opendaimon.common.ai.document.IDocumentContentAnalyzer;
import io.github.ngirchev.opendaimon.common.ai.factory.AICommandFactoryRegistry;
import io.github.ngirchev.opendaimon.common.ai.pipeline.AIRequestPipeline;
import io.github.ngirchev.opendaimon.common.ai.pipeline.DefaultAIRequestPipelineActions;
import io.github.ngirchev.opendaimon.common.ai.pipeline.IRagQueryAugmenter;
import io.github.ngirchev.opendaimon.common.ai.pipeline.fsm.AIRequestContext;
import io.github.ngirchev.opendaimon.common.ai.pipeline.fsm.AIRequestEvent;
import io.github.ngirchev.opendaimon.common.ai.pipeline.fsm.AIRequestPipelineActions;
import io.github.ngirchev.opendaimon.common.ai.pipeline.fsm.AIRequestPipelineFsmFactory;
import io.github.ngirchev.opendaimon.common.ai.pipeline.fsm.AIRequestState;
import io.github.ngirchev.opendaimon.common.ai.pipeline.fsm.AttachmentEvent;
import io.github.ngirchev.opendaimon.common.ai.pipeline.fsm.AttachmentProcessingContext;
import io.github.ngirchev.opendaimon.common.ai.pipeline.fsm.AttachmentState;
import io.github.ngirchev.fsm.impl.extended.ExDomainFsm;
import io.github.ngirchev.opendaimon.common.ai.command.AICommand;
import io.github.ngirchev.opendaimon.common.ai.factory.AICommandFactory;
import io.github.ngirchev.opendaimon.common.ai.factory.DefaultAICommandFactory;
import io.github.ngirchev.opendaimon.common.command.CommandHandlerRegistry;
import io.github.ngirchev.opendaimon.common.command.ICommand;
import io.github.ngirchev.opendaimon.common.command.ICommandHandler;
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
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
@AutoConfiguration
@AutoConfigureAfter(name = "io.github.ngirchev.opendaimon.ai.springai.config.RAGAutoConfig")
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
    public ChatOwnerLookup chatOwnerLookup() {
        return ChatOwnerLookup.NOOP;
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
    @ConditionalOnProperty(name = FeatureToggle.Feature.BULKHEAD_ENABLED, havingValue = "false", matchIfMissing = true)
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
    @ConditionalOnProperty(name = FeatureToggle.Feature.BULKHEAD_ENABLED, havingValue = "false", matchIfMissing = true)
    public PriorityRequestExecutor noOpPriorityRequestExecutor() {
        return new NoOpPriorityRequestExecutor();
    }

    // ==================== Core beans ====================

    @Bean
    @ConditionalOnMissingBean(DefaultAICommandFactory.class)
    public AICommandFactory<AICommand, ICommand<?>> defaultAiCommandFactory(
            IUserPriorityService userPriorityService,
            CoreCommonProperties coreCommonProperties,
            ObjectProvider<ModelDescriptionCache> modelDescriptionCacheProvider,
            ObjectProvider<IDocumentContentAnalyzer> documentContentAnalyzerProvider) {
        return new DefaultAICommandFactory(
                userPriorityService,
                modelDescriptionCacheProvider.getIfAvailable(),
                documentContentAnalyzerProvider.getIfAvailable(),
                coreCommonProperties);
    }

    /**
     * AI request pipeline actions — default implementation using document FSM and RAG augmenter.
     * Only created when document FSM is available (RAG enabled).
     * Ordering guaranteed by @AutoConfigureAfter(RAGAutoConfig).
     */
    @Bean
    @ConditionalOnMissingBean(AIRequestPipelineActions.class)
    @ConditionalOnBean(name = "documentPipelineFsm")
    public DefaultAIRequestPipelineActions aiRequestPipelineActions(
            ExDomainFsm<AttachmentProcessingContext, AttachmentState, AttachmentEvent> documentPipelineFsm,
            ObjectProvider<IRagQueryAugmenter> ragQueryAugmenterProvider,
            AICommandFactoryRegistry aiCommandFactoryRegistry) {
        return new DefaultAIRequestPipelineActions(
                documentPipelineFsm,
                ragQueryAugmenterProvider.getIfAvailable(),
                aiCommandFactoryRegistry);
    }

    /**
     * AI request pipeline FSM — processes incoming commands through validate/classify/process states.
     * Only created when pipeline actions are available (RAG enabled).
     */
    @Bean
    @ConditionalOnMissingBean(name = "aiRequestPipelineFsm")
    @ConditionalOnBean(AIRequestPipelineActions.class)
    public ExDomainFsm<AIRequestContext, AIRequestState, AIRequestEvent> aiRequestPipelineFsm(
            AIRequestPipelineActions actions) {
        log.info("Creating AI request pipeline FSM");
        return AIRequestPipelineFsmFactory.create(actions);
    }

    @Bean
    @ConditionalOnMissingBean
    public AIRequestPipeline aiRequestPipeline(
            ObjectProvider<ExDomainFsm<AIRequestContext, AIRequestState, AIRequestEvent>> requestFsmProvider,
            AICommandFactoryRegistry aiCommandFactoryRegistry) {
        return new AIRequestPipeline(
                requestFsmProvider.getIfAvailable(),
                aiCommandFactoryRegistry);
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
    @ConditionalOnMissingBean
    public SummarizationService summarizationService(
            ConversationThreadService threadService,
            AIGatewayRegistry aiGatewayRegistry,
            CoreCommonProperties coreCommonProperties,
            ObjectMapper objectMapper,
            ChatOwnerLookup chatOwnerLookup) {
        return new SummarizationService(
                threadService,
                aiGatewayRegistry,
                coreCommonProperties,
                objectMapper,
                chatOwnerLookup
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
