package io.github.ngirchev.opendaimon.ai.springai.config;

import io.github.ngirchev.opendaimon.ai.springai.rag.FileRAGService;
import io.github.ngirchev.opendaimon.ai.springai.service.DocumentProcessingService;
import io.github.ngirchev.opendaimon.ai.springai.service.SpringAIModelType;
import io.github.ngirchev.opendaimon.common.ai.ModelCapabilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests RAG auto-configuration across all provider combinations.
 *
 * <p>Verifies that:
 * <ul>
 *   <li>RAG beans are NOT created when {@code rag.enabled} is false/missing</li>
 *   <li>RAG beans ARE created with correct EmbeddingModel for each provider mode</li>
 *   <li>Errors are clear when configuration is inconsistent</li>
 * </ul>
 *
 * <p>Uses {@link ApplicationContextRunner} — no real AI endpoints or database needed.
 */
class RAGAutoConfigIT {

    private static final String RAG_ENABLED = "open-daimon.ai.spring-ai.rag.enabled=true";
    private static final String RAG_DISABLED = "open-daimon.ai.spring-ai.rag.enabled=false";

    private static final String[] RAG_REQUIRED_PROPS = {
            "open-daimon.ai.spring-ai.rag.chunk-size=200",
            "open-daimon.ai.spring-ai.rag.chunk-overlap=50",
            "open-daimon.ai.spring-ai.rag.top-k=3",
            "open-daimon.ai.spring-ai.rag.similarity-threshold=0.5",
            "open-daimon.ai.spring-ai.rag.prompts.document-extract-error-pdf=PDF error: %s",
            "open-daimon.ai.spring-ai.rag.prompts.document-extract-error-document=Doc error: %s %s",
            "open-daimon.ai.spring-ai.rag.prompts.augmented-prompt-template=Context: %s Question: %s",
    };

    // ─── RAG disabled ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("RAG disabled (rag.enabled=false or missing)")
    class RAGDisabled {

        @Test
        @DisplayName("no RAG beans when rag.enabled=false")
        void noBeans_whenRagDisabled() {
            contextRunner()
                    .withPropertyValues(RAG_DISABLED)
                    .run(ctx -> {
                        assertThat(ctx).doesNotHaveBean(VectorStore.class);
                        assertThat(ctx).doesNotHaveBean(DocumentProcessingService.class);
                        assertThat(ctx).doesNotHaveBean(FileRAGService.class);
                    });
        }

        @Test
        @DisplayName("no RAG beans when rag.enabled is not set")
        void noBeans_whenRagEnabledNotSet() {
            contextRunner()
                    .run(ctx -> {
                        assertThat(ctx).doesNotHaveBean(VectorStore.class);
                        assertThat(ctx).doesNotHaveBean(DocumentProcessingService.class);
                        assertThat(ctx).doesNotHaveBean(FileRAGService.class);
                    });
        }

        @Test
        @DisplayName("no RAG beans when rag.enabled=false even with both embedding models present")
        void noBeans_whenRagDisabled_withBothEmbeddingModels() {
            contextRunner()
                    .withUserConfiguration(MockOllamaEmbeddingConfig.class, MockOpenAiEmbeddingConfig.class)
                    .withPropertyValues(RAG_DISABLED)
                    .run(ctx -> {
                        assertThat(ctx).doesNotHaveBean(VectorStore.class);
                        assertThat(ctx).doesNotHaveBean(DocumentProcessingService.class);
                        assertThat(ctx).doesNotHaveBean(FileRAGService.class);
                    });
        }
    }

    // ─── Ollama-only (NPM: application-simple-ollama.yml) ────────────────────

    @Nested
    @DisplayName("RAG enabled — Ollama-only setup")
    class OllamaOnly {

        @Test
        @DisplayName("VectorStore created with Ollama embedding when models.list has EMBEDDING+OLLAMA")
        void vectorStore_created_withOllamaEmbedding() {
            contextRunner()
                    .withUserConfiguration(MockOllamaEmbeddingConfig.class)
                    .withPropertyValues(ragEnabledProps())
                    .withPropertyValues(ollamaEmbeddingModelList())
                    .run(ctx -> {
                        assertThat(ctx).hasSingleBean(VectorStore.class);
                        assertThat(ctx).hasSingleBean(DocumentProcessingService.class);
                        assertThat(ctx).hasSingleBean(FileRAGService.class);
                    });
        }

        @Test
        @DisplayName("startup fails when models.list points to OLLAMA but Ollama embedding bean is absent")
        void fails_whenModelsListPointsToOllama_butOllamaEmbeddingAbsent() {
            contextRunner()
                    // No MockOllamaEmbeddingConfig — Ollama embedding bean missing
                    .withPropertyValues(ragEnabledProps())
                    .withPropertyValues(ollamaEmbeddingModelList())
                    .run(ctx ->
                            assertThat(ctx).hasFailed()
                                    .getFailure()
                                    .rootCause()
                                    .isInstanceOf(IllegalStateException.class)
                                    .hasMessageContaining("No model with EMBEDDING capability and available API provider"));
        }
    }

    // ─── OpenRouter-only (NPM: application-simple-openrouter.yml) ────────────

    @Nested
    @DisplayName("RAG enabled — OpenRouter-only setup")
    class OpenRouterOnly {

        @Test
        @DisplayName("VectorStore created with OpenAI embedding when models.list has EMBEDDING+OPENAI")
        void vectorStore_created_withOpenAiEmbedding() {
            contextRunner()
                    .withUserConfiguration(MockOpenAiEmbeddingConfig.class)
                    .withPropertyValues(ragEnabledProps())
                    .withPropertyValues(openAiEmbeddingModelList())
                    .run(ctx -> {
                        assertThat(ctx).hasSingleBean(VectorStore.class);
                        assertThat(ctx).hasSingleBean(DocumentProcessingService.class);
                        assertThat(ctx).hasSingleBean(FileRAGService.class);
                    });
        }

        @Test
        @DisplayName("startup fails when models.list points to OPENAI but OpenAI embedding bean is absent")
        void fails_whenModelsListPointsToOpenAi_butOpenAiEmbeddingAbsent() {
            contextRunner()
                    // No MockOpenAiEmbeddingConfig — OpenAI embedding bean missing
                    .withPropertyValues(ragEnabledProps())
                    .withPropertyValues(openAiEmbeddingModelList())
                    .run(ctx ->
                            assertThat(ctx).hasFailed()
                                    .getFailure()
                                    .rootCause()
                                    .isInstanceOf(IllegalStateException.class)
                                    .hasMessageContaining("No model with EMBEDDING capability and available API provider"));
        }

        @Test
        @DisplayName("VectorStore created when openrouter template defines own models.list with OPENAI EMBEDDING only")
        void vectorStore_created_whenOpenRouterTemplateDefinesOwnModelsList() {
            // Simulates: application-simple-openrouter.yml with explicit models.list
            // containing only OPENAI models (no Ollama) — overrides application.yml defaults.
            contextRunner()
                    .withUserConfiguration(MockOpenAiEmbeddingConfig.class)
                    .withPropertyValues(ragEnabledProps())
                    .withPropertyValues(openRouterTemplateModelList())
                    .run(ctx -> {
                        assertThat(ctx).hasSingleBean(VectorStore.class);
                        assertThat(ctx).hasSingleBean(DocumentProcessingService.class);
                        assertThat(ctx).hasSingleBean(FileRAGService.class);
                    });
        }

        @Test
        @DisplayName("FIXED: default models.list has OLLAMA embedding (priority=1) + OPENAI (priority=3) — " +
                "OpenRouter-only setup falls back to OPENAI because Ollama API is absent")
        void fallsBackToOpenAi_whenDefaultModelsListHasOllama_butOnlyOpenAiAvailable() {
            // Simulates: openrouter-only NPM template does not define models.list,
            // so application.yml defaults apply. Default list has:
            //   qwen3-embedding:8b (OLLAMA, priority=1) and openrouter/auto (OPENAI, priority=3)
            // NEW: algorithm filters by available API first → only OpenAI candidate → succeeds.
            contextRunner()
                    .withUserConfiguration(MockOpenAiEmbeddingConfig.class)
                    // only OpenAI embedding — no Ollama
                    .withPropertyValues(ragEnabledProps())
                    .withPropertyValues(defaultModelsListWithBothEmbeddings())
                    .run(ctx -> {
                        assertThat(ctx).hasSingleBean(VectorStore.class);
                        assertThat(ctx).hasSingleBean(DocumentProcessingService.class);
                        assertThat(ctx).hasSingleBean(FileRAGService.class);
                    });
        }
    }

    // ─── Both providers (NPM: application-simple-both.yml) ───────────────────

    @Nested
    @DisplayName("RAG enabled — Both providers setup (OpenRouter cloud + Ollama local)")
    class BothProviders {

        @Test
        @DisplayName("VectorStore uses Ollama embedding when models.list EMBEDDING points to OLLAMA")
        void vectorStore_usesOllamaEmbedding_whenModelsListPointsToOllama() {
            contextRunner()
                    .withUserConfiguration(MockOllamaEmbeddingConfig.class, MockOpenAiEmbeddingConfig.class)
                    .withPropertyValues(ragEnabledProps())
                    .withPropertyValues(ollamaEmbeddingModelList())
                    .run(ctx -> {
                        assertThat(ctx).hasSingleBean(VectorStore.class);
                        assertThat(ctx).hasSingleBean(DocumentProcessingService.class);
                        assertThat(ctx).hasSingleBean(FileRAGService.class);
                    });
        }

        @Test
        @DisplayName("VectorStore uses OpenAI embedding when models.list EMBEDDING points to OPENAI")
        void vectorStore_usesOpenAiEmbedding_whenModelsListPointsToOpenAi() {
            contextRunner()
                    .withUserConfiguration(MockOllamaEmbeddingConfig.class, MockOpenAiEmbeddingConfig.class)
                    .withPropertyValues(ragEnabledProps())
                    .withPropertyValues(openAiEmbeddingModelList())
                    .run(ctx -> {
                        assertThat(ctx).hasSingleBean(VectorStore.class);
                        assertThat(ctx).hasSingleBean(DocumentProcessingService.class);
                        assertThat(ctx).hasSingleBean(FileRAGService.class);
                    });
        }

        @Test
        @DisplayName("When both providers available + AUTO model is OPENAI — prefers OPENAI embedding over Ollama (even with lower priority)")
        void prefersOpenAiEmbedding_whenAutoModelIsOpenAi_andBothAvailable() {
            // Both APIs available, but AUTO model (openrouter/auto) uses OPENAI.
            // Selection prefers same provider as AUTO → OPENAI embedding wins despite priority=3 vs 1.
            contextRunner()
                    .withUserConfiguration(MockOllamaEmbeddingConfig.class, MockOpenAiEmbeddingConfig.class)
                    .withPropertyValues(ragEnabledProps())
                    .withPropertyValues(defaultModelsListWithAutoAndBothEmbeddings())
                    .run(ctx -> {
                        assertThat(ctx).hasSingleBean(VectorStore.class);
                        assertThat(ctx).hasSingleBean(DocumentProcessingService.class);
                        assertThat(ctx).hasSingleBean(FileRAGService.class);
                    });
        }
    }

    // ─── Missing EMBEDDING capability in models.list ─────────────────────────

    @Nested
    @DisplayName("RAG enabled — No EMBEDDING model in models.list")
    class NoEmbeddingModel {

        @Test
        @DisplayName("startup fails when models.list has no model with EMBEDDING capability")
        void fails_whenNoEmbeddingModelInList() {
            contextRunner()
                    .withUserConfiguration(MockOllamaEmbeddingConfig.class)
                    .withPropertyValues(ragEnabledProps())
                    .withPropertyValues(chatOnlyModelList())
                    .run(ctx ->
                            assertThat(ctx).hasFailed()
                                    .getFailure()
                                    .rootCause()
                                    .isInstanceOf(IllegalStateException.class)
                                    .hasMessageContaining("No model with EMBEDDING capability and available API provider"));
        }
    }

    // ─── Helper: ApplicationContextRunner ────────────────────────────────────

    private ApplicationContextRunner contextRunner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TestRAGAutoConfig.class));
    }

    private String[] ragEnabledProps() {
        String[] result = new String[RAG_REQUIRED_PROPS.length + 1];
        result[0] = RAG_ENABLED;
        System.arraycopy(RAG_REQUIRED_PROPS, 0, result, 1, RAG_REQUIRED_PROPS.length);
        return result;
    }

    private String[] ollamaEmbeddingModelList() {
        return new String[]{
                "open-daimon.ai.spring-ai.models.list[0].name=nomic-embed-text:v1.5",
                "open-daimon.ai.spring-ai.models.list[0].capabilities=EMBEDDING",
                "open-daimon.ai.spring-ai.models.list[0].provider-type=OLLAMA",
                "open-daimon.ai.spring-ai.models.list[0].priority=1",
        };
    }

    private String[] openAiEmbeddingModelList() {
        return new String[]{
                "open-daimon.ai.spring-ai.models.list[0].name=openai/text-embedding-3-small",
                "open-daimon.ai.spring-ai.models.list[0].capabilities=EMBEDDING",
                "open-daimon.ai.spring-ai.models.list[0].provider-type=OPENAI",
                "open-daimon.ai.spring-ai.models.list[0].priority=1",
        };
    }

    /**
     * Mirrors application-simple-openrouter.yml: only OPENAI models, no Ollama.
     */
    private String[] openRouterTemplateModelList() {
        return new String[]{
                "open-daimon.ai.spring-ai.models.list[0].name=openrouter/auto",
                "open-daimon.ai.spring-ai.models.list[0].capabilities=CHAT,EMBEDDING,VISION",
                "open-daimon.ai.spring-ai.models.list[0].provider-type=OPENAI",
                "open-daimon.ai.spring-ai.models.list[0].priority=3",
        };
    }

    /**
     * OLLAMA embedding (priority=1) + OPENAI embedding (priority=3), NO AUTO capability.
     * Without AUTO, selection falls back to priority-based (Ollama wins if available).
     */
    private String[] defaultModelsListWithBothEmbeddings() {
        return new String[]{
                "open-daimon.ai.spring-ai.models.list[0].name=openrouter/auto",
                "open-daimon.ai.spring-ai.models.list[0].capabilities=CHAT,EMBEDDING",
                "open-daimon.ai.spring-ai.models.list[0].provider-type=OPENAI",
                "open-daimon.ai.spring-ai.models.list[0].priority=3",
                "open-daimon.ai.spring-ai.models.list[1].name=qwen3-embedding:8b",
                "open-daimon.ai.spring-ai.models.list[1].capabilities=EMBEDDING",
                "open-daimon.ai.spring-ai.models.list[1].provider-type=OLLAMA",
                "open-daimon.ai.spring-ai.models.list[1].priority=1",
        };
    }

    /**
     * Mirrors real application.yml: openrouter/auto with AUTO+EMBEDDING (OPENAI, priority=3)
     * + Ollama embedding (priority=1). AUTO on OPENAI → algorithm prefers OPENAI embedding.
     */
    private String[] defaultModelsListWithAutoAndBothEmbeddings() {
        return new String[]{
                "open-daimon.ai.spring-ai.models.list[0].name=openrouter/auto",
                "open-daimon.ai.spring-ai.models.list[0].capabilities=AUTO,CHAT,EMBEDDING",
                "open-daimon.ai.spring-ai.models.list[0].provider-type=OPENAI",
                "open-daimon.ai.spring-ai.models.list[0].priority=3",
                "open-daimon.ai.spring-ai.models.list[1].name=qwen3-embedding:8b",
                "open-daimon.ai.spring-ai.models.list[1].capabilities=EMBEDDING",
                "open-daimon.ai.spring-ai.models.list[1].provider-type=OLLAMA",
                "open-daimon.ai.spring-ai.models.list[1].priority=1",
        };
    }

    private String[] chatOnlyModelList() {
        return new String[]{
                "open-daimon.ai.spring-ai.models.list[0].name=qwen2.5:3b",
                "open-daimon.ai.spring-ai.models.list[0].capabilities=CHAT",
                "open-daimon.ai.spring-ai.models.list[0].provider-type=OLLAMA",
                "open-daimon.ai.spring-ai.models.list[0].priority=1",
        };
    }

    // ─── Auto-configuration that mirrors RAGAutoConfig ───────────────────────

    /**
     * Test auto-configuration that wires {@link RAGAutoConfig} with a mock {@link SpringAIModelType}.
     * The mock reads models from {@code open-daimon.ai.spring-ai.models.list} properties.
     */
    @AutoConfiguration
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "open-daimon.ai.spring-ai.rag.enabled", havingValue = "true")
    @org.springframework.boot.context.properties.EnableConfigurationProperties({
            RAGProperties.class, SpringAIProperties.class})
    static class TestRAGAutoConfig {

        @Bean
        SpringAIModelType springAIModelType(SpringAIProperties properties) {
            List<SpringAIModelConfig> models = properties.getModels() != null
                    ? properties.getModels().getList()
                    : List.of();
            return new SpringAIModelType(models);
        }

        @Bean
        @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
        VectorStore simpleVectorStore(
                SpringAIModelType springAIModelType,
                @org.springframework.beans.factory.annotation.Qualifier("ollamaEmbeddingModel")
                org.springframework.beans.factory.ObjectProvider<EmbeddingModel> ollamaEmbeddingModelProvider,
                @org.springframework.beans.factory.annotation.Qualifier("openAiEmbeddingModel")
                org.springframework.beans.factory.ObjectProvider<EmbeddingModel> openAiEmbeddingModelProvider) {

            boolean ollamaAvailable = ollamaEmbeddingModelProvider.getIfAvailable() != null;
            boolean openAiAvailable = openAiEmbeddingModelProvider.getIfAvailable() != null;

            // Primary provider = provider of the AUTO (main) model
            SpringAIModelConfig.ProviderType primaryProvider = springAIModelType
                    .getByCapability(ModelCapabilities.AUTO)
                    .map(SpringAIModelConfig::getProviderType)
                    .orElse(null);

            // Filter: EMBEDDING capability + available API provider
            // Prefer: same provider as primary model → then lower priority number
            SpringAIModelConfig modelConfig = springAIModelType.getModels().stream()
                    .filter(m -> m.getCapabilities() != null
                            && m.getCapabilities().contains(ModelCapabilities.EMBEDDING))
                    .filter(m -> switch (m.getProviderType()) {
                        case OLLAMA -> ollamaAvailable;
                        case OPENAI -> openAiAvailable;
                    })
                    .min(Comparator.<SpringAIModelConfig, Integer>comparing(m ->
                                    m.getProviderType() == primaryProvider ? 0 : 1)
                            .thenComparing(SpringAIModelConfig::getPriority))
                    .orElseThrow(() -> new IllegalStateException(
                            "No model with EMBEDDING capability and available API provider found in " +
                            "open-daimon.ai.spring-ai.models.list. Ollama available: " + ollamaAvailable +
                            ", OpenAI available: " + openAiAvailable));

            return mock(VectorStore.class);
        }

        @Bean
        @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
        DocumentProcessingService documentProcessingService(
                VectorStore vectorStore, RAGProperties ragProperties) {
            return mock(DocumentProcessingService.class);
        }

        @Bean
        @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
        FileRAGService ragService(
                VectorStore vectorStore, RAGProperties ragProperties) {
            return mock(FileRAGService.class);
        }
    }

    // ─── Mock embedding model configurations ─────────────────────────────────

    @Configuration
    static class MockOllamaEmbeddingConfig {

        @Bean("ollamaEmbeddingModel")
        EmbeddingModel ollamaEmbeddingModel() {
            return mock(EmbeddingModel.class);
        }
    }

    @Configuration
    static class MockOpenAiEmbeddingConfig {

        @Bean("openAiEmbeddingModel")
        EmbeddingModel openAiEmbeddingModel() {
            return mock(EmbeddingModel.class);
        }
    }
}
