package ru.girchev.aibot.ai.springai.config;

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
import ru.girchev.aibot.ai.springai.service.DocumentProcessingService;
import ru.girchev.aibot.ai.springai.service.RAGService;
import ru.girchev.aibot.ai.springai.service.SpringAIModelType;
import ru.girchev.aibot.common.ai.ModelType;

/**
 * Авто-конфигурация для RAG (Retrieval-Augmented Generation).
 * 
 * <p>Активируется при {@code ai-bot.ai.spring-ai.rag.enabled=true}
 * 
 * <p>Использует SimpleVectorStore (in-memory), что имеет следующие особенности:
 * <ul>
 *   <li><b>Преимущества:</b> Не требует внешних зависимостей (PostgreSQL pgvector, Elasticsearch)</li>
 *   <li><b>Ограничения:</b> Данные не персистентны - теряются при перезапуске</li>
 *   <li><b>Рекомендация:</b> Для production используйте PGVector или Elasticsearch</li>
 * </ul>
 * 
 * <p>Для работы требуется EmbeddingModel (например, от Ollama или OpenAI).
 * 
 * @see DocumentProcessingService для обработки PDF документов
 * @see RAGService для поиска релевантных чанков
 */
@Slf4j
@AutoConfiguration
@ConditionalOnProperty(name = "ai-bot.ai.spring-ai.rag.enabled", havingValue = "true")
@EnableConfigurationProperties(RAGProperties.class)
public class RAGAutoConfig {

    /**
     * Создает SimpleVectorStore для хранения embeddings.
     * 
     * <p>SimpleVectorStore работает in-memory, что означает:
     * <ul>
     *   <li>Быстрый старт без внешних зависимостей</li>
     *   <li>Данные теряются при перезапуске приложения</li>
     *   <li>Подходит для тестирования и небольших объемов данных</li>
     * </ul>
     * 
     * <p>EmbeddingModel выбирается динамически через SpringAIModelType по capability EMBEDDING.
     * Модель настраивается в ai-bot.ai.spring-ai.models.list с capability EMBEDDING.
     * 
     * @param springAIModelType сервис для выбора модели по capabilities
     * @param ollamaEmbeddingModelProvider провайдер Ollama EmbeddingModel
     * @param openAiEmbeddingModelProvider провайдер OpenAI EmbeddingModel
     * @return VectorStore инстанс
     */
    @Bean
    @ConditionalOnMissingBean
    public VectorStore simpleVectorStore(
            SpringAIModelType springAIModelType,
            @Qualifier("ollamaEmbeddingModel") ObjectProvider<EmbeddingModel> ollamaEmbeddingModelProvider,
            @Qualifier("openAiEmbeddingModel") ObjectProvider<EmbeddingModel> openAiEmbeddingModelProvider) {
        
        // Динамически выбираем модель по capability EMBEDDING
        SpringAIModelConfig modelConfig = springAIModelType.getByCapability(ModelType.EMBEDDING)
                .orElseThrow(() -> new IllegalStateException(
                        "No model with EMBEDDING capability found in ai-bot.ai.spring-ai.models.list"));
        
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
     * Создает сервис для обработки PDF документов.
     * 
     * @param vectorStore хранилище для embeddings
     * @param ragProperties конфигурация RAG
     * @return DocumentProcessingService инстанс
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
     * Создает сервис для RAG поиска.
     * 
     * @param vectorStore хранилище для embeddings
     * @param ragProperties конфигурация RAG
     * @return RAGService инстанс
     */
    @Bean
    @ConditionalOnMissingBean
    public RAGService ragService(
            VectorStore vectorStore,
            RAGProperties ragProperties) {
        log.info("Creating RAGService with topK={}, similarityThreshold={}", 
                ragProperties.getTopK(), ragProperties.getSimilarityThreshold());
        return new RAGService(vectorStore, ragProperties);
    }
}
