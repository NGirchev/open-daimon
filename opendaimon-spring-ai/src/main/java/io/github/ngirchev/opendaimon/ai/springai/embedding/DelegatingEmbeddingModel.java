package io.github.ngirchev.opendaimon.ai.springai.embedding;

import io.github.ngirchev.opendaimon.ai.springai.config.SpringAIModelConfig;
import io.github.ngirchev.opendaimon.ai.springai.retry.SpringAIModelRegistry;
import io.github.ngirchev.opendaimon.common.ai.ModelCapabilities;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaEmbeddingOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Set;

/**
 * Proxy EmbeddingModel that dynamically resolves the best available embedding model
 * from the {@link SpringAIModelRegistry} on each call. This allows the VectorStore
 * to be created at startup while the actual embedding model can change as OpenRouter
 * models are refreshed by the scheduler.
 *
 * <p>Caches the underlying model and recreates it only when the resolved model name changes.
 */
@Slf4j
public class DelegatingEmbeddingModel implements EmbeddingModel {

    private final SpringAIModelRegistry registry;
    private final ObjectProvider<OllamaApi> ollamaApiProvider;
    private final ObjectProvider<OpenAiApi> openAiApiProvider;

    private volatile EmbeddingModel delegate;
    private volatile String currentModelName;

    public DelegatingEmbeddingModel(
            SpringAIModelRegistry registry,
            ObjectProvider<OllamaApi> ollamaApiProvider,
            ObjectProvider<OpenAiApi> openAiApiProvider) {
        this.registry = registry;
        this.ollamaApiProvider = ollamaApiProvider;
        this.openAiApiProvider = openAiApiProvider;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        return resolveDelegate().call(request);
    }

    @Override
    public float[] embed(Document document) {
        return resolveDelegate().embed(document);
    }

    private EmbeddingModel resolveDelegate() {
        // Prefer multilingual models — they work for any language including English
        List<SpringAIModelConfig> candidates = registry.getCandidatesByCapabilities(
                Set.of(ModelCapabilities.EMBEDDING, ModelCapabilities.MULTILINGUAL), null);
        if (candidates.isEmpty()) {
            // Fallback to any embedding model
            candidates = registry.getCandidatesByCapabilities(
                    Set.of(ModelCapabilities.EMBEDDING), null);
        }
        if (candidates.isEmpty()) {
            throw new IllegalStateException(
                    "No model with EMBEDDING capability found in registry");
        }
        SpringAIModelConfig modelConfig = candidates.getFirst();

        String modelName = modelConfig.getName();
        if (delegate != null && modelName.equals(currentModelName)) {
            return delegate;
        }

        synchronized (this) {
            if (delegate != null && modelName.equals(currentModelName)) {
                return delegate;
            }
            log.info("DelegatingEmbeddingModel: switching to '{}' (provider={})",
                    modelName, modelConfig.getProviderType());
            delegate = createEmbeddingModel(modelConfig);
            currentModelName = modelName;
            return delegate;
        }
    }

    private EmbeddingModel createEmbeddingModel(SpringAIModelConfig modelConfig) {
        return switch (modelConfig.getProviderType()) {
            case OLLAMA -> {
                OllamaApi api = ollamaApiProvider.getIfAvailable();
                if (api == null) {
                    throw new IllegalStateException("OllamaApi not available for embedding model: " + modelConfig.getName());
                }
                yield OllamaEmbeddingModel.builder()
                        .ollamaApi(api)
                        .defaultOptions(OllamaEmbeddingOptions.builder().model(modelConfig.getName()).build())
                        .build();
            }
            case OPENAI -> {
                OpenAiApi api = openAiApiProvider.getIfAvailable();
                if (api == null) {
                    throw new IllegalStateException("OpenAiApi not available for embedding model: " + modelConfig.getName());
                }
                yield new OpenAiEmbeddingModel(api, MetadataMode.EMBED,
                        OpenAiEmbeddingOptions.builder().model(modelConfig.getName()).build());
            }
        };
    }
}
