package io.github.ngirchev.opendaimon.ai.springai.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import io.github.ngirchev.opendaimon.ai.springai.service.DocumentProcessingService;
import io.github.ngirchev.opendaimon.ai.springai.rag.FileRAGService;
import io.github.ngirchev.opendaimon.ai.springai.service.SpringAIModelType;
import io.github.ngirchev.opendaimon.common.ai.ModelCapabilities;

/**
 * Auto-configuration for RAG (Retrieval-Augmented Generation).
 *
 * <p>Enabled when {@code open-daimon.ai.spring-ai.rag.enabled=true}
 *
 * <p>Uses SimpleVectorStore (in-memory):
 * <ul>
 *   <li><b>Pros:</b> No external deps (PostgreSQL pgvector, Elasticsearch)</li>
 *   <li><b>Cons:</b> Data not persistent — lost on restart</li>
 *   <li><b>Recommendation:</b> For production use PGVector or Elasticsearch</li>
 * </ul>
 *
 * <p>Requires EmbeddingModel (e.g. Ollama or OpenAI).
 *
 * @see DocumentProcessingService for PDF processing
 * @see FileRAGService for relevant chunk search
 */
@Slf4j
@AutoConfiguration
@ConditionalOnProperty(name = "open-daimon.ai.spring-ai.rag.enabled", havingValue = "true")
@EnableConfigurationProperties(RAGProperties.class)
public class RAGAutoConfig {

    /**
     * Creates SimpleVectorStore for embeddings.
     *
     * <p>SimpleVectorStore is in-memory:
     * <ul>
     *   <li>Fast startup, no external deps</li>
     *   <li>Data lost on app restart</li>
     *   <li>Suited for testing and small data</li>
     * </ul>
     *
     * <p>EmbeddingModel is chosen by SpringAIModelType via capability EMBEDDING (open-daimon.ai.spring-ai.models.list).
     *
     * @param springAIModelType service to select model by capabilities
     * @param ollamaEmbeddingModelProvider Ollama EmbeddingModel provider
     * @param openAiEmbeddingModelProvider OpenAI EmbeddingModel provider
     * @return VectorStore instance
     */
    @Bean
    @ConditionalOnMissingBean
    public VectorStore simpleVectorStore(
            SpringAIModelType springAIModelType,
            @Qualifier("ollamaEmbeddingModel") ObjectProvider<EmbeddingModel> ollamaEmbeddingModelProvider,
            @Qualifier("openAiEmbeddingModel") ObjectProvider<EmbeddingModel> openAiEmbeddingModelProvider) {
        
        // Select model by capability EMBEDDING
        SpringAIModelConfig modelConfig = springAIModelType.getByCapability(ModelCapabilities.EMBEDDING)
                .orElseThrow(() -> new IllegalStateException(
                        "No model with EMBEDDING capability found in open-daimon.ai.spring-ai.models.list"));
        
        EmbeddingModel embeddingModel = switch (modelConfig.getProviderType()) {
            case OLLAMA -> {
                EmbeddingModel model = ollamaEmbeddingModelProvider.getIfAvailable();
                if (model == null) {
                    throw new IllegalStateException(
                            "Ollama EmbeddingModel not available. Check that Ollama is configured.");
                }
                yield model;
            }
            case OPENAI -> {
                EmbeddingModel model = openAiEmbeddingModelProvider.getIfAvailable();
                if (model == null) {
                    throw new IllegalStateException(
                            "OpenAI EmbeddingModel not available. Check that OpenAI is configured.");
                }
                yield model;
            }
        };
        
        log.info("Creating SimpleVectorStore (in-memory) for RAG with model '{}' (provider: {})", 
                modelConfig.getName(), modelConfig.getProviderType());
        return SimpleVectorStore.builder(embeddingModel).build();
    }

    /**
     * Creates PDF document processing service.
     *
     * @param vectorStore store for embeddings
     * @param ragProperties RAG config
     * @return DocumentProcessingService instance
     */
    @Bean
    @ConditionalOnMissingBean
    public DocumentProcessingService documentProcessingService(
            VectorStore vectorStore,
            RAGProperties ragProperties) {
        log.info("Creating DocumentProcessingService with chunkSize={}, chunkOverlap={}", 
                ragProperties.getChunkSize(), ragProperties.getChunkOverlap());
        return new DocumentProcessingService(vectorStore, ragProperties);
    }

    /**
     * Creates RAG search service.
     *
     * @param vectorStore store for embeddings
     * @param ragProperties RAG config
     * @return RAGService instance
     */
    @Bean
    @ConditionalOnMissingBean
    public FileRAGService ragService(
            VectorStore vectorStore,
            RAGProperties ragProperties) {
        log.info("Creating RAGService with topK={}, similarityThreshold={}", 
                ragProperties.getTopK(), ragProperties.getSimilarityThreshold());
        return new FileRAGService(vectorStore, ragProperties);
    }
}
