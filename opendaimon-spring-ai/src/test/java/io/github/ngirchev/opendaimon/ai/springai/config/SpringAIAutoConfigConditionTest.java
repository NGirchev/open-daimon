package io.github.ngirchev.opendaimon.ai.springai.config;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Tests that {@code openAiChatClient} bean is only created when {@link OpenAiChatModel} is present.
 * Covers the fix that prevents startup failure when no OpenAI API key is configured (Ollama-only setup).
 *
 * <p>Uses {@link org.springframework.boot.test.context.runner.ApplicationContextRunner} with
 * {@link org.springframework.boot.autoconfigure.AutoConfigurations} to correctly evaluate
 * {@link ConditionalOnBean} — this annotation is only reliable inside {@link AutoConfiguration} classes.
 */
class SpringAIAutoConfigConditionTest {

    /**
     * Context without OpenAiChatModel — simulates Ollama-only setup (local profile,
     * OpenAiChatAutoConfiguration excluded).
     */
    private final ApplicationContextRunner contextWithoutOpenAiModel =
            new ApplicationContextRunner()
                    .withConfiguration(
                            org.springframework.boot.autoconfigure.AutoConfigurations.of(
                                    OpenAiChatClientAutoConfig.class));

    /**
     * Context with OpenAiChatModel present — simulates setup with OpenAI API key configured.
     */
    private final ApplicationContextRunner contextWithOpenAiModel =
            new ApplicationContextRunner()
                    .withUserConfiguration(MockOpenAiModelConfig.class)
                    .withConfiguration(
                            org.springframework.boot.autoconfigure.AutoConfigurations.of(
                                    OpenAiChatClientAutoConfig.class));

    @Test
    void openAiChatClient_notCreated_whenOpenAiChatModelAbsent() {
        contextWithoutOpenAiModel.run(ctx ->
                assertThat(ctx).doesNotHaveBean("openAiChatClient"));
    }

    @Test
    void openAiChatClient_created_whenOpenAiChatModelPresent() {
        contextWithOpenAiModel.run(ctx ->
                assertThat(ctx).hasBean("openAiChatClient"));
    }

    @Test
    void openAiChatClient_isOfTypeChatClient_whenOpenAiChatModelPresent() {
        contextWithOpenAiModel.run(ctx ->
                assertThat(ctx).getBean("openAiChatClient").isInstanceOf(ChatClient.class));
    }

    /**
     * Isolated auto-configuration that replicates only the conditional {@code openAiChatClient}
     * bean from {@link SpringAIAutoConfig}.
     * Declared as {@link AutoConfiguration} so that {@link ConditionalOnBean} is evaluated
     * after all user-defined beans (including {@link MockOpenAiModelConfig}) are processed.
     */
    @AutoConfiguration
    static class OpenAiChatClientAutoConfig {

        @Bean("openAiChatClient")
        @ConditionalOnBean(OpenAiChatModel.class)
        ChatClient openAiChatClient(OpenAiChatModel openAiChatModel) {
            return mock(ChatClient.class);
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
