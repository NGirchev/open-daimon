package io.github.ngirchev.opendaimon.ai.springai.embedding;

import io.github.ngirchev.opendaimon.ai.springai.config.SpringAIModelConfig;
import io.github.ngirchev.opendaimon.ai.springai.config.SpringAIModelConfig.ProviderType;
import io.github.ngirchev.opendaimon.ai.springai.retry.SpringAIModelRegistry;
import io.github.ngirchev.opendaimon.common.ai.ModelCapabilities;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.ObjectProvider;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DelegatingEmbeddingModelTest {

    private static final Set<ModelCapabilities> MULTILINGUAL_EMBEDDING =
            Set.of(ModelCapabilities.EMBEDDING, ModelCapabilities.MULTILINGUAL);
    private static final Set<ModelCapabilities> EMBEDDING_ONLY =
            Set.of(ModelCapabilities.EMBEDDING);

    @Mock
    private SpringAIModelRegistry registry;

    @Mock
    private ObjectProvider<OllamaApi> ollamaApiProvider;

    @Mock
    private ObjectProvider<OpenAiApi> openAiApiProvider;

    @Mock
    private OpenAiApi openAiApi;

    private DelegatingEmbeddingModel model;

    @BeforeEach
    void setUp() {
        model = new DelegatingEmbeddingModel(registry, ollamaApiProvider, openAiApiProvider);
    }

    private static SpringAIModelConfig embeddingConfig(String name, ProviderType provider) {
        SpringAIModelConfig config = new SpringAIModelConfig();
        config.setName(name);
        config.setProviderType(provider);
        config.setPriority(10);
        config.setCapabilities(EnumSet.of(ModelCapabilities.EMBEDDING));
        return config;
    }

    private void stubMultilingualEmpty() {
        when(registry.getCandidatesByCapabilities(eq(MULTILINGUAL_EMBEDDING), eq(null)))
                .thenReturn(List.of());
    }

    private void stubMultilingualReturns(SpringAIModelConfig config) {
        when(registry.getCandidatesByCapabilities(eq(MULTILINGUAL_EMBEDDING), eq(null)))
                .thenReturn(List.of(config));
    }

    private void stubFallbackReturns(SpringAIModelConfig config) {
        when(registry.getCandidatesByCapabilities(eq(EMBEDDING_ONLY), eq(null)))
                .thenReturn(List.of(config));
    }

    private void stubFallbackEmpty() {
        when(registry.getCandidatesByCapabilities(eq(EMBEDDING_ONLY), eq(null)))
                .thenReturn(List.of());
    }

    @Test
    @DisplayName("Prefers multilingual model when available")
    void prefersMultilingualModel() {
        SpringAIModelConfig config = embeddingConfig("intfloat/multilingual-e5-large", ProviderType.OPENAI);
        stubMultilingualReturns(config);
        when(openAiApiProvider.getIfAvailable()).thenReturn(openAiApi);

        EmbeddingRequest request = new EmbeddingRequest(List.of("hello"), null);
        try { model.call(request); } catch (Exception ignored) {}

        verify(registry).getCandidatesByCapabilities(eq(MULTILINGUAL_EMBEDDING), eq(null));
        // Fallback should NOT be called
        verify(registry, never()).getCandidatesByCapabilities(eq(EMBEDDING_ONLY), eq(null));
        verify(openAiApiProvider, times(1)).getIfAvailable();
    }

    @Test
    @DisplayName("Falls back to any embedding model when no multilingual available")
    void fallsBackToAnyEmbedding() {
        stubMultilingualEmpty();
        SpringAIModelConfig config = embeddingConfig("baai/bge-base-en-v1.5", ProviderType.OPENAI);
        stubFallbackReturns(config);
        when(openAiApiProvider.getIfAvailable()).thenReturn(openAiApi);

        EmbeddingRequest request = new EmbeddingRequest(List.of("hello"), null);
        try { model.call(request); } catch (Exception ignored) {}

        verify(registry).getCandidatesByCapabilities(eq(MULTILINGUAL_EMBEDDING), eq(null));
        verify(registry).getCandidatesByCapabilities(eq(EMBEDDING_ONLY), eq(null));
        verify(openAiApiProvider, times(1)).getIfAvailable();
    }

    @Test
    @DisplayName("Resolves OpenAI embedding model")
    void resolvesOpenAiEmbeddingModel() {
        SpringAIModelConfig config = embeddingConfig("openai/text-embedding-3-small", ProviderType.OPENAI);
        stubMultilingualEmpty();
        stubFallbackReturns(config);
        when(openAiApiProvider.getIfAvailable()).thenReturn(openAiApi);

        EmbeddingRequest request = new EmbeddingRequest(List.of("hello"), null);
        try { model.call(request); } catch (Exception ex) {
            assertThat(ex.getMessage()).doesNotContain("OpenAiApi not available");
        }

        verify(openAiApiProvider, times(1)).getIfAvailable();
    }

    @Test
    @DisplayName("Resolves Ollama embedding model")
    void resolvesOllamaEmbeddingModel() {
        SpringAIModelConfig config = embeddingConfig("nomic-embed-text", ProviderType.OLLAMA);
        stubMultilingualEmpty();
        stubFallbackReturns(config);
        OllamaApi realOllamaApi = OllamaApi.builder().build();
        when(ollamaApiProvider.getIfAvailable()).thenReturn(realOllamaApi);

        EmbeddingRequest request = new EmbeddingRequest(List.of("hello"), null);
        try { model.call(request); } catch (Exception ex) {
            assertThat(ex.getMessage()).doesNotContain("OllamaApi not available");
        }

        verify(ollamaApiProvider, times(1)).getIfAvailable();
    }

    @Test
    @DisplayName("Throws when no embedding model in registry — call()")
    void throwsWhenNoEmbeddingModelInRegistry() {
        stubMultilingualEmpty();
        stubFallbackEmpty();

        EmbeddingRequest request = new EmbeddingRequest(List.of("hello"), null);
        assertThatThrownBy(() -> model.call(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No model with EMBEDDING capability found in registry");
    }

    @Test
    @DisplayName("Throws when no embedding model in registry — embed(Document)")
    void throwsWhenNoEmbeddingModelInRegistry_embedDocument() {
        stubMultilingualEmpty();
        stubFallbackEmpty();

        Document doc = new Document("Test content");
        assertThatThrownBy(() -> model.embed(doc))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No model with EMBEDDING capability found in registry");
    }

    @Test
    @DisplayName("Caches delegate — same model name does not re-query provider")
    void cachesDelegate_sameModelName() {
        SpringAIModelConfig config = embeddingConfig("openai/text-embedding-3-small", ProviderType.OPENAI);
        stubMultilingualEmpty();
        stubFallbackReturns(config);
        when(openAiApiProvider.getIfAvailable()).thenReturn(openAiApi);

        EmbeddingRequest request = new EmbeddingRequest(List.of("hello"), null);
        try { model.call(request); } catch (Exception ignored) {}
        try { model.call(request); } catch (Exception ignored) {}

        verify(openAiApiProvider, times(1)).getIfAvailable();
    }

    @Test
    @DisplayName("Switches delegate when model changes in registry")
    void switchesDelegate_whenModelChanges() {
        SpringAIModelConfig configA = embeddingConfig("openai/embed-v1", ProviderType.OPENAI);
        SpringAIModelConfig configB = embeddingConfig("openai/embed-v2", ProviderType.OPENAI);

        when(registry.getCandidatesByCapabilities(eq(MULTILINGUAL_EMBEDDING), eq(null)))
                .thenReturn(List.of())
                .thenReturn(List.of());
        when(registry.getCandidatesByCapabilities(eq(EMBEDDING_ONLY), eq(null)))
                .thenReturn(List.of(configA))
                .thenReturn(List.of(configB));
        when(openAiApiProvider.getIfAvailable()).thenReturn(openAiApi);

        EmbeddingRequest request = new EmbeddingRequest(List.of("hello"), null);
        try { model.call(request); } catch (Exception ignored) {}
        try { model.call(request); } catch (Exception ignored) {}

        verify(openAiApiProvider, times(2)).getIfAvailable();
    }

    @Test
    @DisplayName("Throws when OllamaApi not available")
    void throwsWhenOllamaApiNotAvailable() {
        SpringAIModelConfig config = embeddingConfig("nomic-embed-text", ProviderType.OLLAMA);
        stubMultilingualEmpty();
        stubFallbackReturns(config);
        when(ollamaApiProvider.getIfAvailable()).thenReturn(null);

        EmbeddingRequest request = new EmbeddingRequest(List.of("hello"), null);
        assertThatThrownBy(() -> model.call(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OllamaApi not available")
                .hasMessageContaining("nomic-embed-text");
    }

    @Test
    @DisplayName("Throws when OpenAiApi not available")
    void throwsWhenOpenAiApiNotAvailable() {
        SpringAIModelConfig config = embeddingConfig("openai/text-embedding-3-small", ProviderType.OPENAI);
        stubMultilingualEmpty();
        stubFallbackReturns(config);
        when(openAiApiProvider.getIfAvailable()).thenReturn(null);

        EmbeddingRequest request = new EmbeddingRequest(List.of("hello"), null);
        assertThatThrownBy(() -> model.call(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OpenAiApi not available")
                .hasMessageContaining("openai/text-embedding-3-small");
    }

    @Test
    @DisplayName("Ollama provider not consulted for OpenAI model")
    void ollamaProviderNotConsulted_forOpenAi() {
        SpringAIModelConfig config = embeddingConfig("openai/text-embedding-3-small", ProviderType.OPENAI);
        stubMultilingualEmpty();
        stubFallbackReturns(config);
        when(openAiApiProvider.getIfAvailable()).thenReturn(openAiApi);

        EmbeddingRequest request = new EmbeddingRequest(List.of("hello"), null);
        try { model.call(request); } catch (Exception ignored) {}

        verify(ollamaApiProvider, never()).getIfAvailable();
    }

    @Test
    @DisplayName("OpenAI provider not consulted for Ollama model")
    void openAiProviderNotConsulted_forOllama() {
        SpringAIModelConfig config = embeddingConfig("nomic-embed-text", ProviderType.OLLAMA);
        stubMultilingualEmpty();
        stubFallbackReturns(config);
        OllamaApi realOllamaApi = OllamaApi.builder().build();
        when(ollamaApiProvider.getIfAvailable()).thenReturn(realOllamaApi);

        EmbeddingRequest request = new EmbeddingRequest(List.of("hello"), null);
        try { model.call(request); } catch (Exception ignored) {}

        verify(openAiApiProvider, never()).getIfAvailable();
    }
}
