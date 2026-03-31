package io.github.ngirchev.opendaimon.ai.springai.config;

import io.github.ngirchev.opendaimon.ai.springai.service.SpringAIModelType;
import io.github.ngirchev.opendaimon.ai.springai.service.SpringAIPromptFactory;
import io.github.ngirchev.opendaimon.ai.springai.tool.WebTools;
import io.github.ngirchev.opendaimon.common.ai.ModelCapabilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Integration-style tests that verify Spring AI provider client wiring for three deployment modes:
 * <ul>
 *   <li><b>Ollama-only</b> — no OpenRouter API key, {@code spring.ai.model.chat=ollama}</li>
 *   <li><b>OpenRouter-only</b> — no Ollama running, {@code spring.ai.model.chat=openai}</li>
 *   <li><b>Both</b> — OpenRouter cloud + Ollama local, both clients present</li>
 * </ul>
 *
 * <p>Uses {@link ApplicationContextRunner} — no real AI endpoints, no database needed.
 * Conditional bean logic mirrors {@link SpringAIAutoConfig}.
 */
class ProviderConfigIT {

    // ─── ApplicationContextRunner instances ──────────────────────────────────

    private final ApplicationContextRunner ollamaOnlyCtx =
            new ApplicationContextRunner()
                    .withUserConfiguration(MockOllamaModelConfig.class)
                    .withConfiguration(AutoConfigurations.of(ProviderClientAutoConfig.class));

    private final ApplicationContextRunner openRouterOnlyCtx =
            new ApplicationContextRunner()
                    .withUserConfiguration(MockOpenAiModelConfig.class)
                    .withConfiguration(AutoConfigurations.of(ProviderClientAutoConfig.class));

    private final ApplicationContextRunner bothProvidersCtx =
            new ApplicationContextRunner()
                    .withUserConfiguration(MockOllamaModelConfig.class, MockOpenAiModelConfig.class)
                    .withConfiguration(AutoConfigurations.of(ProviderClientAutoConfig.class));

    // ─── Nested test groups ───────────────────────────────────────────────────

    @Nested
    @DisplayName("Ollama-only setup (spring.ai.model.chat=ollama, no OpenRouter key)")
    class OllamaOnlySetup {

        @Test
        @DisplayName("ollamaChatClient is created when OllamaChatModel is present")
        void ollamaChatClient_created_whenOllamaChatModelPresent() {
            ollamaOnlyCtx.run(ctx ->
                    assertThat(ctx).hasBean("ollamaChatClient"));
        }

        @Test
        @DisplayName("openAiChatClient is NOT created when OpenAiChatModel is absent")
        void openAiChatClient_notCreated_whenOpenAiChatModelAbsent() {
            ollamaOnlyCtx.run(ctx ->
                    assertThat(ctx).doesNotHaveBean("openAiChatClient"));
        }

        @Test
        @DisplayName("SpringAIPromptFactory throws when OPENAI model is requested but client is null")
        void promptFactory_throwsIllegalState_whenOpenAiClientIsNull() {
            var factory = promptFactory(/* ollamaClient */ mock(ChatClient.class), /* openAiClient */ null);
            var openAiConfig = openAiModelConfig("openrouter/auto");

            assertThatThrownBy(() ->
                    factory.preparePrompt(openAiConfig, "openrouter/auto", Map.of(), null, false, List.of(), null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("OpenAI client is not configured")
                    .hasMessageContaining("openrouter/auto");
        }

        @Test
        @DisplayName("SpringAIPromptFactory works correctly when OLLAMA model is requested and ollamaClient is present")
        void promptFactory_works_whenOllamaModelRequestedAndOllamaClientPresent() {
            var ollamaClient = mock(ChatClient.class);
            var promptBuilder = mock(ChatClient.ChatClientRequestSpec.class);
            when(ollamaClient.prompt()).thenReturn(promptBuilder);
            when(promptBuilder.options(org.mockito.ArgumentMatchers.any())).thenReturn(promptBuilder);

            var factory = promptFactory(ollamaClient, /* openAiClient */ null);
            var ollamaConfig = ollamaModelConfig("qwen2.5:3b");

            var result = factory.preparePrompt(ollamaConfig, "qwen2.5:3b", Map.of(), null, false, List.of(), null);
            assertThat(result).isNotNull();
        }
    }

    @Nested
    @DisplayName("OpenRouter-only setup (spring.ai.model.chat=openai, no Ollama)")
    class OpenRouterOnlySetup {

        @Test
        @DisplayName("openAiChatClient is created when OpenAiChatModel is present")
        void openAiChatClient_created_whenOpenAiChatModelPresent() {
            openRouterOnlyCtx.run(ctx ->
                    assertThat(ctx).hasBean("openAiChatClient"));
        }

        @Test
        @DisplayName("ollamaChatClient is NOT created when OllamaChatModel is absent")
        void ollamaChatClient_notCreated_whenOllamaChatModelAbsent() {
            openRouterOnlyCtx.run(ctx ->
                    assertThat(ctx).doesNotHaveBean("ollamaChatClient"));
        }

        @Test
        @DisplayName("SpringAIPromptFactory returns openAiChatClient for OPENAI model when ollamaClient is null")
        void promptFactory_returnsOpenAiClient_forOpenAiModelWhenOllamaAbsent() {
            var openAiClient = mock(ChatClient.class);
            var promptBuilder = mock(ChatClient.ChatClientRequestSpec.class);
            when(openAiClient.prompt()).thenReturn(promptBuilder);
            when(promptBuilder.options(org.mockito.ArgumentMatchers.any())).thenReturn(promptBuilder);

            var factory = promptFactory(/* ollamaClient */ null, openAiClient);
            var openAiConfig = openAiModelConfig("openrouter/auto");

            var result = factory.preparePrompt(openAiConfig, "openrouter/auto", Map.of(), null, false, List.of(), null);
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("SpringAIPromptFactory throws when OLLAMA model is requested but ollamaClient is null")
        void promptFactory_throwsIllegalState_whenOllamaClientIsNull() {
            var factory = promptFactory(/* ollamaClient */ null, /* openAiClient */ mock(ChatClient.class));
            var ollamaConfig = ollamaModelConfig("qwen2.5:3b");

            assertThatThrownBy(() ->
                    factory.preparePrompt(ollamaConfig, "qwen2.5:3b", Map.of(), null, false, List.of(), null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Ollama client is not configured")
                    .hasMessageContaining("qwen2.5:3b");
        }
    }

    @Nested
    @DisplayName("Both providers setup (OpenRouter cloud + Ollama local)")
    class BothProvidersSetup {

        @Test
        @DisplayName("both ollamaChatClient and openAiChatClient are created")
        void bothChatClients_created_whenBothModelsPresent() {
            bothProvidersCtx.run(ctx -> {
                assertThat(ctx).hasBean("ollamaChatClient");
                assertThat(ctx).hasBean("openAiChatClient");
            });
        }

        @Test
        @DisplayName("ollamaChatClient and openAiChatClient are both ChatClient instances")
        void bothChatClients_areChatClientInstances() {
            bothProvidersCtx.run(ctx -> {
                assertThat(ctx.getBean("ollamaChatClient")).isInstanceOf(ChatClient.class);
                assertThat(ctx.getBean("openAiChatClient")).isInstanceOf(ChatClient.class);
            });
        }

        @Test
        @DisplayName("OLLAMA model (qwen2.5:3b) is routed to ollamaClient when both providers configured")
        void promptFactory_routesOllamaModel_toOllamaClient_whenBothClientsPresent() {
            var ollamaClient = mock(ChatClient.class);
            var openAiClient = mock(ChatClient.class);
            var promptBuilder = mock(ChatClient.ChatClientRequestSpec.class);
            when(ollamaClient.prompt()).thenReturn(promptBuilder);
            when(promptBuilder.options(org.mockito.ArgumentMatchers.any())).thenReturn(promptBuilder);

            var factory = promptFactory(ollamaClient, openAiClient);

            var result = factory.preparePrompt(ollamaModelConfig("qwen2.5:3b"), "qwen2.5:3b", Map.of(), null, false, List.of(), null);

            assertThat(result).isNotNull();
            org.mockito.Mockito.verify(ollamaClient).prompt();
            org.mockito.Mockito.verifyNoInteractions(openAiClient);
        }

        @Test
        @DisplayName("regression: spring.ai.model.chat=openai in 'both' template disabled OllamaChatAutoConfiguration — OLLAMA model fails with null client")
        void promptFactory_throwsForOllamaModel_whenOllamaClientNullDueToMisconfiguredBothTemplate() {
            // Root cause: application-simple-both.yml had spring.ai.model.chat=openai which
            // caused @ConditionalOnProperty(havingValue="ollama") to be false for OllamaChatAutoConfiguration
            // → OllamaChatModel bean not created → ollamaChatClient=null in SpringAIPromptFactory
            // Fix: removed spring.ai.model.chat from both template (matchIfMissing=true handles both providers)
            var factory = promptFactory(/* ollamaClient= */ null, mock(ChatClient.class));

            assertThatThrownBy(() ->
                    factory.preparePrompt(ollamaModelConfig("qwen2.5:3b"), "qwen2.5:3b", Map.of(), null, false, List.of(), null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Ollama client is not configured")
                    .hasMessageContaining("qwen2.5:3b");
        }
    }

    // ─── Helper builders ──────────────────────────────────────────────────────

    private SpringAIPromptFactory promptFactory(ChatClient ollamaClient, ChatClient openAiClient) {
        var modelType = mock(SpringAIModelType.class);
        when(modelType.getByModelName(org.mockito.ArgumentMatchers.any())).thenReturn(Optional.empty());
        when(modelType.isOllamaModel(org.mockito.ArgumentMatchers.any())).thenReturn(false);
        when(modelType.isOpenAIModel(org.mockito.ArgumentMatchers.any())).thenReturn(false);
        when(modelType.getFirstModel()).thenReturn(Optional.empty());
        return new SpringAIPromptFactory(ollamaClient, openAiClient, mock(WebTools.class), null, modelType);
    }

    private SpringAIModelConfig openAiModelConfig(String name) {
        var config = new SpringAIModelConfig();
        config.setName(name);
        config.setProviderType(SpringAIModelConfig.ProviderType.OPENAI);
        config.setCapabilities(Set.of(ModelCapabilities.CHAT, ModelCapabilities.AUTO));
        return config;
    }

    private SpringAIModelConfig ollamaModelConfig(String name) {
        var config = new SpringAIModelConfig();
        config.setName(name);
        config.setProviderType(SpringAIModelConfig.ProviderType.OLLAMA);
        config.setCapabilities(Set.of(ModelCapabilities.CHAT));
        config.setMaxOutputTokens(4000);
        return config;
    }

    // ─── Embedded auto-configuration (mirrors SpringAIAutoConfig) ────────────

    /**
     * Minimal auto-configuration replicating the conditional client beans from
     * {@link SpringAIAutoConfig}. Declared as {@link AutoConfiguration} so that
     * {@link ConditionalOnBean} is evaluated after user-defined beans.
     */
    @AutoConfiguration
    static class ProviderClientAutoConfig {

        @Bean("ollamaChatClient")
        @ConditionalOnBean(OllamaChatModel.class)
        ChatClient ollamaChatClient(OllamaChatModel ollamaChatModel) {
            return mock(ChatClient.class);
        }

        @Bean("openAiChatClient")
        @ConditionalOnBean(OpenAiChatModel.class)
        ChatClient openAiChatClient(OpenAiChatModel openAiChatModel) {
            return mock(ChatClient.class);
        }
    }

    @Configuration
    static class MockOllamaModelConfig {

        @Bean
        OllamaChatModel ollamaChatModel() {
            return mock(OllamaChatModel.class);
        }
    }

    @Configuration
    static class MockOpenAiModelConfig {

        @Bean
        OpenAiChatModel openAiChatModel() {
            return mock(OpenAiChatModel.class);
        }
    }
}
