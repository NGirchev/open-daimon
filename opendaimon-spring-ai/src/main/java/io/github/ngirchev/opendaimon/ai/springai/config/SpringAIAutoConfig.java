package io.github.ngirchev.opendaimon.ai.springai.config;

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
import io.github.ngirchev.opendaimon.common.config.CoreCommonProperties;
import io.github.ngirchev.opendaimon.common.config.FeatureToggle;
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
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.resolver.DefaultAddressResolverGroup;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.netty.http.client.HttpClient;

import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.Provider;
import java.security.Security;
import java.util.Collections;
import java.util.Enumeration;
import io.github.ngirchev.opendaimon.ai.springai.memory.SummarizingChatMemory;
import io.github.ngirchev.opendaimon.ai.springai.rest.OpenRouterSseNormalizingCustomizer;
import io.github.ngirchev.opendaimon.ai.springai.rest.RestClientLogCustomizer;
import io.github.ngirchev.opendaimon.ai.springai.rest.WebClientLogCustomizer;
import io.github.ngirchev.opendaimon.ai.springai.service.ModelListAIGateway;
import io.github.ngirchev.opendaimon.ai.springai.service.SpringAIGateway;
import io.github.ngirchev.opendaimon.ai.springai.retry.OpenRouterRotationRegistry;
import io.github.ngirchev.opendaimon.ai.springai.retry.SpringAIModelRegistry;
import io.github.ngirchev.opendaimon.ai.springai.service.SpringAIModelType;
import io.github.ngirchev.opendaimon.ai.springai.service.SpringAIPromptFactory;
import io.github.ngirchev.opendaimon.ai.springai.service.SpringAIChatService;
import io.github.ngirchev.opendaimon.ai.springai.retry.OpenRouterModelRotationAspect;
import io.github.ngirchev.opendaimon.ai.springai.tool.UnknownToolFallbackResolver;
import io.github.ngirchev.opendaimon.ai.springai.tool.UrlLivenessChecker;
import io.github.ngirchev.opendaimon.ai.springai.tool.UrlLivenessCheckerImpl;
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
@ConditionalOnProperty(name = FeatureToggle.Module.SPRING_AI_ENABLED, havingValue = "true")
public class SpringAIAutoConfig {

    @Bean
    @ConditionalOnMissingBean
    public SpringAIModelType springAIModelType(SpringAIProperties properties) {
        return new SpringAIModelType(properties.getModels().getList());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = FeatureToggle.OpenRouterModels.PREFIX, name = FeatureToggle.OpenRouterModels.ENABLED, havingValue = "true")
    public OpenRouterModelsApiClient openRouterModelsApiClient(
            RestTemplate restTemplate,
            ObjectMapper objectMapper
    ) {
        return new OpenRouterModelsApiClient(restTemplate, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(Flux.class)
    @ConditionalOnProperty(prefix = FeatureToggle.OpenRouterModels.PREFIX, name = FeatureToggle.OpenRouterModels.ENABLED, havingValue = "true")
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
    @ConditionalOnProperty(prefix = FeatureToggle.OpenRouterModels.PREFIX, name = FeatureToggle.OpenRouterModels.ENABLED, havingValue = "true")
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
    @ConditionalOnProperty(prefix = FeatureToggle.OpenRouterModels.PREFIX, name = FeatureToggle.OpenRouterModels.ENABLED, havingValue = "true")
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
    @ConditionalOnProperty(prefix = FeatureToggle.OpenRouterModels.PREFIX, name = FeatureToggle.OpenRouterModels.ENABLED, havingValue = "true")
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
            ObjectProvider<ChatMemory> chatMemoryProvider
    ) {
        return new SpringAIGateway(
                props,
                aiGatewayRegistry,
                springAIModelRegistry,
                chatService,
                chatMemoryProvider
        );
    }

    @Bean
    public WebClient webClient(WebClient.Builder builder) {
        return builder.build();
    }

    /**
     * WebClient dedicated to built-in agent tools ({@link WebTools}, HttpApiTool) that
     * fetch arbitrary third-party pages/APIs. Raises {@code maxInMemorySize} to 2 MB
     * (default is 256 KB) so large articles (e.g. Hacker Noon, long JSON payloads) do
     * not raise a {@link org.springframework.web.reactive.function.client.WebClientResponseException}
     * with a 2xx status — which the textual-failure heuristic in
     * {@code SpringAgentLoopActions.observe()} would classify as FAILED and trigger the
     * model to retry the same URL in a loop.
     *
     * <p>Kept separate from the default {@code webClient} so SSE streaming for
     * OpenRouter/Ollama LLM calls uses the platform-standard codec limits. With the
     * agent running at most {@code 10/5/1} concurrent calls via PriorityRequestExecutor,
     * worst-case extra heap pressure is ~20 MB.
     */
    @Bean("webToolsWebClient")
    public WebClient webToolsWebClient(WebClient.Builder builder, SpringAIProperties properties) {
        boolean mergeKeychain = Boolean.TRUE.equals(properties.getSsl().getMergeSystemKeychain())
                && isAppleProviderAvailable();
        SslContext sslContext = buildWebToolsSslContext(mergeKeychain);
        HttpClient httpClient = HttpClient.create()
                .secure(spec -> spec.sslContext(sslContext));
        return builder
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(configurer -> configurer
                        .defaultCodecs()
                        .maxInMemorySize(2 * 1024 * 1024))
                .build();
    }

    /**
     * Builds a Netty {@link SslContext} for the {@code webToolsWebClient} that uses a merged trust
     * store: JDK {@code cacerts} plus — on macOS — the system/login Keychain. Designed so JVM-level
     * {@code -Djavax.net.ssl.trustStoreType=KeychainStore} flags are no longer required to avoid
     * PKIX failures when the agent fetches Cloudflare-fronted pages (e.g. {@code itnext.io}) whose
     * chain lags behind Corretto's bundled cacerts.
     *
     * <p>This method must <b>never</b> throw: the agent has to boot even when trust-store discovery
     * hits an unexpected environment. Failure modes degrade silently:
     * <ul>
     *   <li>Apple provider absent or Keychain load fails → JDK cacerts only (WARN).</li>
     *   <li>JDK cacerts load fails → Netty default trust manager (ERROR).</li>
     * </ul>
     *
     * @param includeKeychain whether to attempt merging the macOS Keychain (gated by the caller so
     *                        the test suite can exercise both branches deterministically).
     */
    static SslContext buildWebToolsSslContext(boolean includeKeychain) {
        KeyStore merged;
        try {
            merged = loadJdkTrustStore();
        } catch (Exception e) {
            log.error("Failed to load JDK cacerts for webToolsWebClient; falling back to Netty default trust manager", e);
            try {
                return SslContextBuilder.forClient().build();
            } catch (Exception fallbackEx) {
                throw new IllegalStateException("Failed to build default Netty SslContext", fallbackEx);
            }
        }

        if (includeKeychain) {
            mergeMacKeychainInto(merged);
        }

        try {
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(merged);
            return SslContextBuilder.forClient().trustManager(tmf).build();
        } catch (Exception e) {
            log.error("Failed to build merged TrustManagerFactory for webToolsWebClient; falling back to Netty default trust manager", e);
            try {
                return SslContextBuilder.forClient().build();
            } catch (Exception fallbackEx) {
                throw new IllegalStateException("Failed to build default Netty SslContext", fallbackEx);
            }
        }
    }

    /**
     * Loads the JDK-shipped {@code cacerts} from {@code ${java.home}/lib/security/cacerts}.
     * Uses the default keystore type and the standard {@code "changeit"} password — matches
     * what the default JDK SSLContext would do at startup.
     */
    static KeyStore loadJdkTrustStore() throws Exception {
        String javaHome = System.getProperty("java.home");
        if (javaHome == null || javaHome.isBlank()) {
            throw new IllegalStateException("System property 'java.home' is not set");
        }
        Path cacertsPath = Path.of(javaHome, "lib", "security", "cacerts");
        KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
        try (InputStream in = Files.newInputStream(cacertsPath)) {
            keyStore.load(in, "changeit".toCharArray());
        }
        return keyStore;
    }

    /**
     * Imports every trusted certificate entry from the macOS {@code KeychainStore} provider
     * (System + Login keychains, aggregated by the Apple JSSE provider) into {@code target}.
     * Any failure is logged at WARN and swallowed — callers rely on the silent degradation
     * contract declared by {@link #buildWebToolsSslContext(boolean)}.
     */
    static void mergeMacKeychainInto(KeyStore target) {
        try {
            KeyStore keychain = KeyStore.getInstance("KeychainStore");
            keychain.load(null, null);
            Enumeration<String> aliases = keychain.aliases();
            int imported = 0;
            for (String alias : Collections.list(aliases)) {
                if (keychain.isCertificateEntry(alias)) {
                    try {
                        target.setCertificateEntry("keychain-" + alias, keychain.getCertificate(alias));
                        imported++;
                    } catch (Exception entryEx) {
                        // A single bad entry must not abort the whole merge.
                        log.debug("Skipping keychain entry '{}' during trust-store merge: {}", alias, entryEx.getMessage());
                    }
                }
            }
            log.info("Merged {} macOS Keychain certificate entries into webToolsWebClient trust store", imported);
        } catch (Exception e) {
            log.warn("Could not merge macOS Keychain into webToolsWebClient trust store; using JDK cacerts only: {}", e.getMessage());
        }
    }

    /**
     * Returns {@code true} when the Apple JSSE provider (source of {@code KeychainStore}) is
     * registered. Used to gate the keychain merge on non-macOS hosts.
     */
    static boolean isAppleProviderAvailable() {
        Provider apple = Security.getProvider("Apple");
        return apple != null;
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
    public WebTools webTools(
            @Qualifier("webToolsWebClient") WebClient webClient,
            SpringAIProperties properties) {
        return new WebTools(
            webClient,
            properties.getSerper().getApi().getKey(),
            properties.getSerper().getApi().getUrl()
        );
    }

    /**
     * Last-mile sanitizer that strips LLM-hallucinated dead URLs from the final
     * answer. Disabled by setting {@code open-daimon.ai.spring-ai.url-check.enabled=false}.
     */
    @Bean
    @ConditionalOnMissingBean(UrlLivenessChecker.class)
    @ConditionalOnProperty(
            name = "open-daimon.ai.spring-ai.url-check.enabled",
            havingValue = "true",
            matchIfMissing = true)
    public UrlLivenessChecker urlLivenessChecker(
            @Qualifier("webToolsWebClient") WebClient webClient,
            SpringAIProperties properties) {
        SpringAIProperties.UrlCheck cfg = properties.getUrlCheck();
        return new UrlLivenessCheckerImpl(
                webClient,
                java.time.Duration.ofMillis(cfg.getTimeoutMs()),
                cfg.getMaxUrlsPerAnswer(),
                java.time.Duration.ofMinutes(cfg.getCacheTtlMinutes()));
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
