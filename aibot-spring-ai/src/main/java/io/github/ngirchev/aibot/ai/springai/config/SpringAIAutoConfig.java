package io.github.ngirchev.aibot.ai.springai.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import io.github.ngirchev.aibot.common.config.CoreCommonProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.util.StringUtils;
import org.springframework.boot.web.reactive.function.client.WebClientCustomizer;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.*;
import org.springframework.web.client.RestTemplate;
import io.netty.resolver.DefaultAddressResolverGroup;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.netty.http.client.HttpClient;
import io.github.ngirchev.aibot.ai.springai.memory.SummarizingChatMemory;
import io.github.ngirchev.aibot.ai.springai.rest.RestClientLogCustomizer;
import io.github.ngirchev.aibot.ai.springai.rest.WebClientLogCustomizer;
import io.github.ngirchev.aibot.ai.springai.service.DocumentProcessingService;
import io.github.ngirchev.aibot.ai.springai.rag.FileRAGService;
import io.github.ngirchev.aibot.ai.springai.service.SpringAIGateway;
import io.github.ngirchev.aibot.ai.springai.retry.OpenRouterRotationRegistry;
import io.github.ngirchev.aibot.ai.springai.retry.SpringAIModelRegistry;
import io.github.ngirchev.aibot.ai.springai.service.SpringAIModelType;
import io.github.ngirchev.aibot.ai.springai.service.SpringAIPromptFactory;
import io.github.ngirchev.aibot.ai.springai.service.SpringAIChatService;
import io.github.ngirchev.aibot.ai.springai.retry.OpenRouterModelRotationAspect;
import io.github.ngirchev.aibot.ai.springai.tool.WebTools;
import io.github.ngirchev.aibot.common.repository.AIBotMessageRepository;
import io.github.ngirchev.aibot.common.repository.ConversationThreadRepository;
import io.github.ngirchev.aibot.ai.springai.retry.OpenRouterFreeModelResolver;
import io.github.ngirchev.aibot.ai.springai.retry.OpenRouterModelsApiClient;
import io.github.ngirchev.aibot.ai.springai.retry.OpenRouterModelsProperties;
import io.github.ngirchev.aibot.ai.springai.retry.OpenRouterModelStatsRecorder;
import io.github.ngirchev.aibot.ai.springai.retry.metrics.OpenRouterStreamMetricsTracker;
import io.github.ngirchev.aibot.common.service.AIGatewayRegistry;
import io.github.ngirchev.aibot.common.service.SummarizationService;

@Slf4j
@AutoConfiguration
@AutoConfigureAfter(name = {
    "io.github.ngirchev.aibot.common.config.CoreAutoConfig",
    "org.springframework.ai.model.ollama.autoconfigure.OllamaChatAutoConfiguration",
    "org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration",
    "org.springframework.ai.model.chat.memory.autoconfigure.ChatMemoryAutoConfiguration"
})
@EnableConfigurationProperties({SpringAIProperties.class, OpenRouterModelsProperties.class})
@Import(SpringAIFlywayConfig.class)
@ConditionalOnProperty(name = "ai-bot.ai.spring-ai.enabled", havingValue = "true")
public class SpringAIAutoConfig {

    @Bean
    @ConditionalOnMissingBean
    public SpringAIModelType springAIModelType(SpringAIProperties properties) {
        return new SpringAIModelType(properties.getModels().getList());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "ai-bot.ai.spring-ai.openrouter-auto-rotation.models", name = "enabled", havingValue = "true")
    public OpenRouterModelsApiClient openRouterModelsApiClient(
            RestTemplate restTemplate,
            ObjectMapper objectMapper
    ) {
        return new OpenRouterModelsApiClient(restTemplate, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(Flux.class)
    @ConditionalOnProperty(prefix = "ai-bot.ai.spring-ai.openrouter-auto-rotation.models", name = "enabled", havingValue = "true")
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
    @ConditionalOnProperty(prefix = "ai-bot.ai.spring-ai.openrouter-auto-rotation.models", name = "enabled", havingValue = "true")
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
    @ConditionalOnProperty(prefix = "ai-bot.ai.spring-ai.openrouter-auto-rotation.models", name = "enabled", havingValue = "true")
    public OpenRouterFreeModelResolver openRouterFreeModelResolver(
            RestTemplate restTemplate,
            ObjectMapper objectMapper,
            OpenRouterModelsProperties openRouterModelsProperties
    ) {
        return new OpenRouterFreeModelResolver(restTemplate, objectMapper, openRouterModelsProperties);
    }

    @Bean
    public SpringAIPromptFactory springAIPromptFactory(
            @Qualifier("ollamaChatClient") ChatClient ollamaChatClient,
            @Qualifier("openAiChatClient") ChatClient openAiChatClient,
            WebTools webTools,
            ChatMemory chatMemory,
            SpringAIModelType springAIModelType,
            CoreCommonProperties coreCommonProperties
    ) {
        boolean manualConversationContextEnabled = Boolean.TRUE.equals(
                coreCommonProperties.getManualConversationHistory().getEnabled()
        );
        return new SpringAIPromptFactory(
                ollamaChatClient,
                openAiChatClient,
                webTools,
                chatMemory,
                springAIModelType,
                !manualConversationContextEnabled
        );
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
//                .defaultHeader("HTTP-Referer", "https://github.com/NGirchev/ai-bot")
//                .defaultHeader("X-Title", "AI Bot")
                .clientConnector(new ReactorClientHttpConnector(httpClient));
    }
    
    /**
     * WebClientCustomizer for OpenAI/OpenRouter WebClient timeouts.
     * Spring AI uses WebClientCustomizer for WebClient autoconfiguration.
     */
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
    @ConditionalOnProperty(value = "ai-bot.common.manual-conversation-history.enabled", havingValue = "false")
    public ChatMemory chatMemoryOnPostgresDb(
            ChatMemoryRepository chatMemoryRepository,
            ConversationThreadRepository conversationThreadRepository,
            AIBotMessageRepository messageRepository,
            SummarizationService summarizationService,
            SpringAIProperties springAIProperties) {
        
        return new SummarizingChatMemory(
                chatMemoryRepository,
                conversationThreadRepository,
                messageRepository,
                summarizationService,
                springAIProperties.getHistoryWindowSize()
        );
    }

    @Bean("ollamaChatClient")
    @ConditionalOnProperty(value = "ai-bot.common.manual-conversation-history.enabled", havingValue = "false")
    public ChatClient ollamaChatClientWithHistory(OllamaChatModel ollamaChatModel) {
        return ChatClient.builder(ollamaChatModel).build();
    }

    @Bean("openAiChatClient")
    @ConditionalOnProperty(value = "ai-bot.common.manual-conversation-history.enabled", havingValue = "false")
    public ChatClient openAiChatClientWithHistory(OpenAiChatModel openAiChatModel) {
        return ChatClient.builder(openAiChatModel).build();
    }

    @Bean("ollamaChatClient")
    @ConditionalOnProperty(value = "ai-bot.common.manual-conversation-history.enabled", havingValue = "true")
    public ChatClient ollamaChatClient(OllamaChatModel ollamaChatModel) {
        return ChatClient.builder(ollamaChatModel).build();
    }

    @Bean("openAiChatClient")
    @ConditionalOnProperty(value = "ai-bot.common.manual-conversation-history.enabled", havingValue = "true")
    public ChatClient openAiChatClient(OpenAiChatModel openAiChatModel) {
        return ChatClient.builder(openAiChatModel).build();
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
