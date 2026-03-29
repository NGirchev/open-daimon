package io.github.ngirchev.opendaimon.ai.springai.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import io.github.ngirchev.opendaimon.common.config.CoreCommonProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.util.StringUtils;
import org.springframework.boot.web.reactive.function.client.WebClientCustomizer;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;
import io.netty.resolver.DefaultAddressResolverGroup;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.netty.http.client.HttpClient;
import io.github.ngirchev.opendaimon.ai.springai.memory.SummarizingChatMemory;
import io.github.ngirchev.opendaimon.ai.springai.rest.OpenRouterSseNormalizingCustomizer;
import io.github.ngirchev.opendaimon.ai.springai.rest.RestClientLogCustomizer;
import io.github.ngirchev.opendaimon.ai.springai.rest.WebClientLogCustomizer;
import io.github.ngirchev.opendaimon.ai.springai.service.DocumentProcessingService;
import io.github.ngirchev.opendaimon.ai.springai.rag.FileRAGService;
import io.github.ngirchev.opendaimon.ai.springai.service.ModelListAIGateway;
import io.github.ngirchev.opendaimon.ai.springai.service.SpringAIGateway;
import io.github.ngirchev.opendaimon.ai.springai.retry.OpenRouterRotationRegistry;
import io.github.ngirchev.opendaimon.ai.springai.retry.SpringAIModelRegistry;
import io.github.ngirchev.opendaimon.ai.springai.service.SpringAIModelType;
import io.github.ngirchev.opendaimon.ai.springai.service.SpringAIPromptFactory;
import io.github.ngirchev.opendaimon.ai.springai.service.SpringAIChatService;
import io.github.ngirchev.opendaimon.ai.springai.retry.OpenRouterModelRotationAspect;
import io.github.ngirchev.opendaimon.ai.springai.tool.UnknownToolFallbackResolver;
import io.github.ngirchev.opendaimon.ai.springai.tool.WebTools;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.resolution.DelegatingToolCallbackResolver;
import org.springframework.ai.tool.resolution.SpringBeanToolCallbackResolver;
import org.springframework.ai.tool.resolution.StaticToolCallbackResolver;
import org.springframework.context.support.GenericApplicationContext;

import java.util.List;
import io.github.ngirchev.opendaimon.common.repository.OpenDaimonMessageRepository;
import io.github.ngirchev.opendaimon.common.repository.ConversationThreadRepository;
import io.github.ngirchev.opendaimon.ai.springai.retry.OpenRouterFreeModelResolver;
import io.github.ngirchev.opendaimon.ai.springai.retry.OpenRouterModelsApiClient;
import io.github.ngirchev.opendaimon.ai.springai.retry.OpenRouterModelsProperties;
import io.github.ngirchev.opendaimon.ai.springai.retry.OpenRouterModelStatsRecorder;
import io.github.ngirchev.opendaimon.ai.springai.retry.metrics.OpenRouterStreamMetricsTracker;
import io.github.ngirchev.opendaimon.common.ai.ModelDescriptionCache;
import io.github.ngirchev.opendaimon.common.service.AIGatewayRegistry;
import io.github.ngirchev.opendaimon.common.service.SummarizationService;

@Slf4j
@AutoConfiguration
@AutoConfigureAfter(name = {
    "io.github.ngirchev.opendaimon.common.config.CoreAutoConfig",
    "org.springframework.ai.model.chat.memory.autoconfigure.ChatMemoryAutoConfiguration"
})
@AutoConfigureBefore(name = "org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration")
@EnableConfigurationProperties({SpringAIProperties.class, OpenRouterModelsProperties.class})
@Import(SpringAIFlywayConfig.class)
@ConditionalOnProperty(name = "open-daimon.ai.spring-ai.enabled", havingValue = "true")
public class SpringAIAutoConfig {

    @Bean
    @ConditionalOnMissingBean
    public SpringAIModelType springAIModelType(SpringAIProperties properties) {
        return new SpringAIModelType(properties.getModels().getList());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "open-daimon.ai.spring-ai.openrouter-auto-rotation.models", name = "enabled", havingValue = "true")
    public OpenRouterModelsApiClient openRouterModelsApiClient(
            RestTemplate restTemplate,
            ObjectMapper objectMapper
    ) {
        return new OpenRouterModelsApiClient(restTemplate, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(Flux.class)
    @ConditionalOnProperty(prefix = "open-daimon.ai.spring-ai.openrouter-auto-rotation.models", name = "enabled", havingValue = "true")
    public OpenRouterStreamMetricsTracker openRouterStreamMetricsTracker(
            ObjectProvider<OpenRouterModelStatsRecorder> openRouterModelStatsRecorderProvider
    ) {
        return new OpenRouterStreamMetricsTracker(openRouterModelStatsRecorderProvider);
    }

    @Bean
    @ConditionalOnMissingBean
    public SpringAIModelRegistry springAIModelRegistry(
            SpringAIProperties properties,
            ObjectProvider<OpenRouterModelsApiClient> openRouterModelsApiClientProvider,
            ObjectProvider<OpenRouterModelsProperties> openRouterModelsPropertiesProvider
    ) {
        return new SpringAIModelRegistry(
                properties.getModels().getList(),
                openRouterModelsApiClientProvider.getIfAvailable(),
                openRouterModelsPropertiesProvider.getIfAvailable()
        );
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "open-daimon.ai.spring-ai.openrouter-auto-rotation.models", name = "enabled", havingValue = "true")
    public SpringAIModelRegistryRefreshScheduler springAIModelRegistryRefreshScheduler(SpringAIModelRegistry registry) {
        return new SpringAIModelRegistryRefreshScheduler(registry);
    }

    @Bean
    @ConditionalOnMissingBean
    public OpenRouterModelStatsRecorder openRouterModelStatsRecorder(SpringAIModelRegistry registry) {
        return registry;
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "open-daimon.ai.spring-ai.openrouter-auto-rotation.models", name = "enabled", havingValue = "true")
    public OpenRouterFreeModelResolver openRouterFreeModelResolver(
            RestTemplate restTemplate,
            ObjectMapper objectMapper,
            OpenRouterModelsProperties openRouterModelsProperties
    ) {
        return new OpenRouterFreeModelResolver(restTemplate, objectMapper, openRouterModelsProperties);
    }

    @Bean
    public SpringAIPromptFactory springAIPromptFactory(
            ObjectProvider<OllamaChatModel> ollamaChatModelProvider,
            ObjectProvider<OpenAiChatModel> openAiChatModelProvider,
            WebTools webTools,
            ChatMemory chatMemory,
            SpringAIModelType springAIModelType
    ) {
        // Providers are stored and resolved lazily on first request — ordering relative to
        // OllamaChatAutoConfiguration / OpenAiChatAutoConfiguration does not matter.
        return new SpringAIPromptFactory(ollamaChatModelProvider, openAiChatModelProvider, webTools, chatMemory, springAIModelType);
    }

    @Bean
    public SpringAIChatService springAIChatService(
            SpringAIPromptFactory promptFactory,
            ObjectProvider<OpenRouterStreamMetricsTracker> openRouterStreamMetricsTrackerProvider
    ) {
        return new SpringAIChatService(
                promptFactory,
                openRouterStreamMetricsTrackerProvider
        );
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "open-daimon.ai.spring-ai.openrouter-auto-rotation.models", name = "enabled", havingValue = "true")
    public OpenRouterModelRotationAspect openRouterModelRotationAspect(
            OpenRouterRotationRegistry openRouterRotationRegistry,
            SpringAIProperties springAIProperties
    ) {
        int maxAttempts = springAIProperties.getOpenrouterAutoRotation() != null
                ? springAIProperties.getOpenrouterAutoRotation().getMaxAttempts()
                : 1;
        int safeMaxAttempts = Math.max(maxAttempts, 1);
        return new OpenRouterModelRotationAspect(openRouterRotationRegistry, safeMaxAttempts);
    }

    @Bean
    @ConditionalOnMissingBean
    public ModelDescriptionCache modelDescriptionCache(SpringAIModelRegistry registry) {
        return registry::getCapabilities;
    }

    @Bean
    @ConditionalOnMissingBean
    public ModelListAIGateway modelListAIGateway(SpringAIModelRegistry registry,
                                                  AIGatewayRegistry aiGatewayRegistry) {
        return new ModelListAIGateway(registry, aiGatewayRegistry);
    }

    @Bean
    public SpringAIGateway springAiGateway(
            SpringAIProperties props,
            AIGatewayRegistry aiGatewayRegistry,
            SpringAIModelRegistry springAIModelRegistry,
            SpringAIChatService chatService,
            ObjectProvider<ChatMemory> chatMemoryProvider,
            ObjectProvider<RAGProperties> ragPropertiesProvider,
            ObjectProvider<DocumentProcessingService> documentProcessingServiceProvider,
            ObjectProvider<FileRAGService> ragServiceProvider
    ) {
        return new SpringAIGateway(
                props,
                aiGatewayRegistry,
                springAIModelRegistry,
                chatService,
                chatMemoryProvider,
                ragPropertiesProvider.getIfAvailable(),
                documentProcessingServiceProvider,
                ragServiceProvider
        );
    }

    @Bean
    public WebClient webClient(WebClient.Builder builder) {
        return builder.build();
    }

    /**
     * Creates WebClient.Builder for Ollama with proper DNS resolver.
     * Spring AI uses WebClient.Builder to create its WebClient.
     * This bean may be used by Spring AI auto-configuration.
     */
    @Profile({"dev"})
    @Primary
    @Bean("ollamaWebClientBuilder")
    @ConditionalOnMissingBean(name = "ollamaWebClientBuilder")
    public WebClient.Builder ollamaWebClientBuilder(
            @Value("${spring.ai.ollama.base-url}") String baseUrl,
            SpringAIProperties properties) {
        log.info("Creating custom Ollama WebClient.Builder with system DNS resolver for: {}", baseUrl);
        
        int timeoutSeconds = properties.getTimeouts() != null && properties.getTimeouts().getResponseTimeoutSeconds() != null
                ? properties.getTimeouts().getResponseTimeoutSeconds()
                : 600; // Default: 10 minutes

        // Configure HttpClient with system DNS resolver for .local domain support
        HttpClient httpClient = HttpClient.create()
                .resolver(DefaultAddressResolverGroup.INSTANCE) // Uses system DNS (including /etc/hosts and mDNS)
                .responseTimeout(java.time.Duration.ofSeconds(timeoutSeconds));
        
        log.info("Ollama WebClient response timeout: {} seconds", timeoutSeconds);
        
        return WebClient.builder()
                .baseUrl(baseUrl)
//                .defaultHeader("HTTP-Referer", "https://github.com/NGirchev/open-daimon")
//                .defaultHeader("X-Title", "OpenDaimon")
                .clientConnector(new ReactorClientHttpConnector(httpClient));
    }
    
    /**
     * WebClientCustomizer for OpenAI/OpenRouter WebClient timeouts.
     * Spring AI uses WebClientCustomizer for WebClient autoconfiguration.
     */
    @Bean
    @ConditionalOnMissingBean
    public OpenRouterSseNormalizingCustomizer openRouterSseNormalizingCustomizer() {
        return new OpenRouterSseNormalizingCustomizer();
    }

    /**
     * Custom {@link ToolCallingManager} that appends {@link UnknownToolFallbackResolver}
     * as the last resolver, silently ignoring tool calls from models that invoke
     * built-in provider-side tools not registered in Spring AI (e.g. Gemini {@code run}).
     * Declared via {@code @AutoConfigureBefore(ToolCallingAutoConfiguration)} so
     * {@code @ConditionalOnMissingBean} in that autoconfig skips creating a default bean.
     */
    @Bean
    @ConditionalOnMissingBean(ToolCallingManager.class)
    public ToolCallingManager toolCallingManager(GenericApplicationContext applicationContext) {
        var resolver = new DelegatingToolCallbackResolver(List.of(
                new StaticToolCallbackResolver(List.of()),
                SpringBeanToolCallbackResolver.builder().applicationContext(applicationContext).build(),
                new UnknownToolFallbackResolver()
        ));
        return DefaultToolCallingManager.builder()
                .toolCallbackResolver(resolver)
                .build();
    }

    @Bean
    @ConditionalOnMissingBean
    public WebClientCustomizer aiWebClientTimeoutCustomizer(SpringAIProperties properties) {
        return builder -> {
            int timeoutSeconds = properties.getTimeouts() != null && properties.getTimeouts().getResponseTimeoutSeconds() != null
                    ? properties.getTimeouts().getResponseTimeoutSeconds()
                    : 600; // Default: 10 minutes
            
            log.info("Configuring AI WebClient response timeout: {} seconds", timeoutSeconds);
            
            HttpClient httpClient = HttpClient.create()
                    .responseTimeout(java.time.Duration.ofSeconds(timeoutSeconds));
            
            builder.clientConnector(new ReactorClientHttpConnector(httpClient));

            // OpenRouter app attribution (dashboard: App column)
            if (properties.getOpenrouterApp() != null) {
                if (StringUtils.hasText(properties.getOpenrouterApp().getSiteUrl())) {
                    builder.defaultHeader("HTTP-Referer", properties.getOpenrouterApp().getSiteUrl());
                }
                if (StringUtils.hasText(properties.getOpenrouterApp().getTitle())) {
                    builder.defaultHeader("X-Title", properties.getOpenrouterApp().getTitle());
                }
            }
        };
    }


    /**
     * RestClientCustomizer for Ollama RestClient timeouts.
     * Spring AI Ollama uses RestClient internally; socket read timeout must be set explicitly.
     */
    @Bean
    @ConditionalOnMissingBean(name = "aiRestClientTimeoutCustomizer")
    public RestClientCustomizer aiRestClientTimeoutCustomizer(SpringAIProperties properties) {
        return builder -> {
            int timeoutSeconds = properties.getTimeouts() != null && properties.getTimeouts().getResponseTimeoutSeconds() != null
                    ? properties.getTimeouts().getResponseTimeoutSeconds()
                    : 600;

            log.info("Configuring AI RestClient read timeout: {} seconds", timeoutSeconds);

            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setReadTimeout(java.time.Duration.ofSeconds(timeoutSeconds));
            factory.setConnectTimeout(java.time.Duration.ofSeconds(30));
            builder.requestFactory(factory);
        };
    }

    @Bean
    @ConditionalOnMissingBean
    public WebTools webTools(WebClient webClient, SpringAIProperties properties) {
        return new WebTools(
            webClient,
            properties.getSerper().getApi().getKey(),
            properties.getSerper().getApi().getUrl()
        );
    }

    @Primary
    @Bean
    @DependsOn("springAiFlyway")
    public ChatMemory chatMemoryOnPostgresDb(
            ChatMemoryRepository chatMemoryRepository,
            ConversationThreadRepository conversationThreadRepository,
            OpenDaimonMessageRepository messageRepository,
            SummarizationService summarizationService,
            org.springframework.context.ApplicationEventPublisher eventPublisher,
            CoreCommonProperties coreCommonProperties) {

        return new SummarizingChatMemory(
                chatMemoryRepository,
                conversationThreadRepository,
                messageRepository,
                summarizationService,
                eventPublisher,
                coreCommonProperties.getSummarization().getMessageWindowSize(),
                coreCommonProperties.getSummarization().getMaxWindowTokens()
        );
    }

    @Bean
    @Profile({"dev", "local"})
    public RestClientCustomizer restClientWithAdditionalLogs(ObjectMapper objectMapper) {
        return new RestClientLogCustomizer(objectMapper);
    }

    @Bean
    @Profile({"dev", "local"})
    public WebClientCustomizer webClientWithAdditionalLogs(ObjectProvider<ObjectMapper> objectMapperProvider) {
        return new WebClientLogCustomizer(objectMapperProvider.getIfAvailable(ObjectMapper::new));
    }
}
