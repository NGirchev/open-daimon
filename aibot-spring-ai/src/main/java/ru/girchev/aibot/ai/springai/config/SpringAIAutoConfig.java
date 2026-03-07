package ru.girchev.aibot.ai.springai.config;

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
import ru.girchev.aibot.common.config.CoreCommonProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.reactive.function.client.WebClientCustomizer;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.*;
import io.netty.resolver.DefaultAddressResolverGroup;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import ru.girchev.aibot.ai.springai.memory.SummarizingChatMemory;
import ru.girchev.aibot.ai.springai.rest.RestClientLogCustomizer;
import ru.girchev.aibot.ai.springai.rest.WebClientLogCustomizer;
import ru.girchev.aibot.ai.springai.service.DocumentProcessingService;
import ru.girchev.aibot.ai.springai.service.RAGService;
import ru.girchev.aibot.ai.springai.service.SpringAIGateway;
import ru.girchev.aibot.ai.springai.service.SpringAIModelType;
import ru.girchev.aibot.ai.springai.service.SpringAIPromptFactory;
import ru.girchev.aibot.ai.springai.service.SpringAIChatService;
import ru.girchev.aibot.ai.springai.retry.OpenRouterModelRotationAspect;
import ru.girchev.aibot.ai.springai.tool.WebTools;
import ru.girchev.aibot.common.repository.AIBotMessageRepository;
import ru.girchev.aibot.common.repository.ConversationThreadRepository;
import ru.girchev.aibot.common.openrouter.OpenRouterFreeModelResolver;
import ru.girchev.aibot.common.openrouter.metrics.OpenRouterStreamMetricsTracker;
import ru.girchev.aibot.common.service.AIGatewayRegistry;
import ru.girchev.aibot.common.service.SummarizationService;

@Slf4j
@AutoConfiguration
@AutoConfigureAfter(name = {
    "ru.girchev.aibot.common.config.CoreAutoConfig",
    "org.springframework.ai.ollama.OllamaAutoConfiguration",
    "org.springframework.ai.openai.OpenAiAutoConfiguration",
    "org.springframework.ai.model.chat.memory.autoconfigure.ChatMemoryAutoConfiguration"
})
@EnableConfigurationProperties({SpringAIProperties.class})
@Import(SpringAIFlywayConfig.class)
@ConditionalOnProperty(name = "ai-bot.ai.spring-ai.enabled", havingValue = "true")
public class SpringAIAutoConfig {

    @Bean
    @ConditionalOnMissingBean
    public SpringAIModelType springAIModelType(SpringAIProperties properties) {
        return new SpringAIModelType(properties.getModels().getList());
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
                coreCommonProperties.getConversationContext().getEnabled()
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
    @ConditionalOnClass(org.aspectj.lang.annotation.Aspect.class)
    public OpenRouterModelRotationAspect openRouterModelRotationAspect(
            ObjectProvider<OpenRouterFreeModelResolver> openRouterFreeModelResolverProvider,
            SpringAIProperties springAIProperties
    ) {
        Integer maxAttempts = springAIProperties.getOpenrouterAutoRotation() != null
                ? springAIProperties.getOpenrouterAutoRotation().getMaxAttempts()
                : null;
        int safeMaxAttempts = (maxAttempts != null && maxAttempts >= 1) ? maxAttempts : 1;
        return new OpenRouterModelRotationAspect(openRouterFreeModelResolverProvider, safeMaxAttempts);
    }

    @Bean
    public SpringAIGateway springAiGateway(
            SpringAIProperties props,
            AIGatewayRegistry aiGatewayRegistry,
            SpringAIModelType springAIModelType,
            SpringAIChatService chatService,
            ObjectProvider<RAGProperties> ragPropertiesProvider,
            ObjectProvider<DocumentProcessingService> documentProcessingServiceProvider,
            ObjectProvider<RAGService> ragServiceProvider
    ) {
        return new SpringAIGateway(
                props,
                aiGatewayRegistry,
                springAIModelType,
                chatService,
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
     * Создает WebClient.Builder для Ollama с правильным DNS резолвером.
     * Spring AI использует WebClient.Builder для создания своего WebClient.
     * Этот bean может быть использован Spring AI автоконфигурацией.
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
                : 600; // Дефолт: 10 минут
        
        // Настраиваем HttpClient с системным DNS резолвером для поддержки .local доменов
        HttpClient httpClient = HttpClient.create()
                .resolver(DefaultAddressResolverGroup.INSTANCE) // Использует системный DNS (включая /etc/hosts и mDNS)
                .responseTimeout(java.time.Duration.ofSeconds(timeoutSeconds));
        
        log.info("Ollama WebClient response timeout: {} seconds", timeoutSeconds);
        
        return WebClient.builder()
                .baseUrl(baseUrl)
//                .defaultHeader("HTTP-Referer", "https://github.com/NGirchev/ai-bot")
//                .defaultHeader("X-Title", "AI Bot")
                .clientConnector(new ReactorClientHttpConnector(httpClient));
    }
    
    /**
     * WebClientCustomizer для настройки таймаутов для OpenAI/OpenRouter WebClient.
     * Spring AI использует WebClientCustomizer для настройки WebClient через автоконфигурацию.
     */
    @Bean
    @ConditionalOnMissingBean
    public WebClientCustomizer aiWebClientTimeoutCustomizer(SpringAIProperties properties) {
        return builder -> {
            int timeoutSeconds = properties.getTimeouts() != null && properties.getTimeouts().getResponseTimeoutSeconds() != null
                    ? properties.getTimeouts().getResponseTimeoutSeconds()
                    : 600; // Дефолт: 10 минут
            
            log.info("Configuring AI WebClient response timeout: {} seconds", timeoutSeconds);
            
            HttpClient httpClient = HttpClient.create()
                    .responseTimeout(java.time.Duration.ofSeconds(timeoutSeconds));
            
            builder.clientConnector(new ReactorClientHttpConnector(httpClient));
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
    @ConditionalOnProperty(value = "ai-bot.common.conversation-context.enabled", havingValue = "false")
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
    @ConditionalOnProperty(value = "ai-bot.common.conversation-context.enabled", havingValue = "false")
    public ChatClient ollamaChatClientWithHistory(OllamaChatModel ollamaChatModel) {
        return ChatClient.builder(ollamaChatModel).build();
    }

    @Bean("openAiChatClient")
    @ConditionalOnProperty(value = "ai-bot.common.conversation-context.enabled", havingValue = "false")
    public ChatClient openAiChatClientWithHistory(OpenAiChatModel openAiChatModel) {
        return ChatClient.builder(openAiChatModel).build();
    }

    @Bean("ollamaChatClient")
    @ConditionalOnProperty(value = "ai-bot.common.conversation-context.enabled", havingValue = "true")
    public ChatClient ollamaChatClient(OllamaChatModel ollamaChatModel) {
        return ChatClient.builder(ollamaChatModel).build();
    }

    @Bean("openAiChatClient")
    @ConditionalOnProperty(value = "ai-bot.common.conversation-context.enabled", havingValue = "true")
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
    public WebClientCustomizer webClientWithAdditionalLogs(
            SpringAIProperties properties,
            ObjectProvider<ObjectMapper> objectMapperProvider
    ) {
        return new WebClientLogCustomizer(
                objectMapperProvider.getIfAvailable(ObjectMapper::new),
                Boolean.TRUE.equals(properties.getHttpLogs().getCallsiteStacktraceEnabled())
        );
    }
}
