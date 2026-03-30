package io.github.ngirchev.opendaimon.ai.springai.config;

import io.github.ngirchev.opendaimon.ai.springai.embedding.DelegatingEmbeddingModel;
import io.github.ngirchev.opendaimon.ai.springai.retry.SpringAIModelRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import io.github.ngirchev.opendaimon.ai.springai.rag.FileRAGService;
import io.github.ngirchev.opendaimon.ai.springai.service.DocumentProcessingService;
import io.github.ngirchev.opendaimon.ai.springai.service.PdfTextDetector;
import io.github.ngirchev.opendaimon.ai.springai.service.SpringAIChatService;
import io.github.ngirchev.opendaimon.ai.springai.service.SpringDocumentContentAnalyzer;
import io.github.ngirchev.opendaimon.ai.springai.service.SpringDocumentOrchestrator;
import io.github.ngirchev.opendaimon.ai.springai.service.SpringDocumentPreprocessor;
import io.github.ngirchev.opendaimon.common.ai.document.IDocumentContentAnalyzer;
import io.github.ngirchev.opendaimon.common.ai.document.IDocumentOrchestrator;
import io.github.ngirchev.opendaimon.common.ai.document.IDocumentPreprocessor;

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
 * <p><b>Embedding model selection:</b> filters models with EMBEDDING capability
 * to only those whose API provider is actually available, then prefers the same
 * provider as the primary (AUTO) model. This ensures that if the user runs on
 * OpenRouter, embedding also goes through OpenRouter (not Ollama).
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
     * Creates SimpleVectorStore with a {@link DelegatingEmbeddingModel} that dynamically
     * resolves the best available embedding model from the registry on each call.
     *
     * <p>This allows VectorStore to be created at startup while the actual embedding model
     * can change as OpenRouter models are refreshed by the scheduler.
     *
     * @param registry model registry with dynamically loaded models (including OpenRouter embedding models)
     * @param ollamaApiProvider Ollama API client (available when Ollama is configured)
     * @param openAiApiProvider OpenAI API client (available when OpenAI is configured)
     * @return VectorStore instance
     */
    @Bean
    @ConditionalOnMissingBean
    public VectorStore simpleVectorStore(
            SpringAIModelRegistry registry,
            ObjectProvider<OllamaApi> ollamaApiProvider,
            ObjectProvider<OpenAiApi> openAiApiProvider) {

        DelegatingEmbeddingModel embeddingModel = new DelegatingEmbeddingModel(
                registry, ollamaApiProvider, openAiApiProvider);

        log.info("Creating SimpleVectorStore (in-memory) for RAG with DelegatingEmbeddingModel");
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

    /**
     * Lightweight PDF text detector — checks if PDFBox can extract text without VectorStore writes.
     */
    @Bean
    @ConditionalOnMissingBean
    public PdfTextDetector pdfTextDetector() {
        return new PdfTextDetector();
    }

    /**
     * Document content analyzer — determines required model capabilities for document attachments.
     * Used by {@code DefaultAICommandFactory} to detect VISION requirement before the gateway call.
     */
    @Bean
    @ConditionalOnMissingBean(IDocumentContentAnalyzer.class)
    public SpringDocumentContentAnalyzer springDocumentContentAnalyzer(PdfTextDetector pdfTextDetector) {
        return new SpringDocumentContentAnalyzer(pdfTextDetector);
    }

    /**
     * Document preprocessor — handles document ETL (extract, transform, load to RAG).
     * Extracted from SpringAIGateway for separation of concerns.
     */
    @Bean
    @ConditionalOnMissingBean(IDocumentPreprocessor.class)
    public SpringDocumentPreprocessor springDocumentPreprocessor(
            DocumentProcessingService documentProcessingService,
            FileRAGService fileRAGService,
            SpringAIModelRegistry springAIModelRegistry,
            SpringAIChatService chatService,
            RAGProperties ragProperties) {
        return new SpringDocumentPreprocessor(
                documentProcessingService, fileRAGService,
                springAIModelRegistry, chatService, ragProperties);
    }

    /**
     * Document orchestrator — coordinates document preprocessing + RAG query building.
     * Used by gateway to delegate all document/RAG logic.
     */
    @Bean
    @ConditionalOnMissingBean(IDocumentOrchestrator.class)
    public SpringDocumentOrchestrator springDocumentOrchestrator(
            IDocumentPreprocessor documentPreprocessor,
            FileRAGService fileRAGService,
            RAGProperties ragProperties) {
        return new SpringDocumentOrchestrator(documentPreprocessor, fileRAGService, ragProperties);
    }
}
