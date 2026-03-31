package io.github.ngirchev.opendaimon.ai.springai.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for RAG (Retrieval-Augmented Generation) with SimpleVectorStore.
 *
 * <p>Feature flag: {@code open-daimon.ai.spring-ai.rag.enabled}
 *
 * <p>SimpleVectorStore is in-memory: data is lost on restart; for production consider PGVector or Elasticsearch.
 */
@ConfigurationProperties(prefix = "open-daimon.ai.spring-ai.rag")
@Validated
@Getter
@Setter
public class RAGProperties {

    /** Feature flag: when true, RAG auto-configuration and beans are enabled. */
    @NotNull(message = "enabled is required")
    private Boolean enabled;

    @NotNull(message = "chunkSize is required")
    @Min(value = 100, message = "chunkSize must be >= 100")
    private Integer chunkSize;

    @NotNull(message = "chunkOverlap is required")
    @Min(value = 0, message = "chunkOverlap must be >= 0")
    private Integer chunkOverlap;

    @NotNull(message = "topK is required")
    @Min(value = 1, message = "topK must be >= 1")
    private Integer topK;

    @NotNull(message = "similarityThreshold is required")
    private Double similarityThreshold;

    @Valid
    @NestedConfigurationProperty
    private RAGPrompts prompts = new RAGPrompts();

    @Getter
    @Setter
    @Validated
    public static class RAGPrompts {
        /** Message when PDF text cannot be extracted. Format: %s = file name. */
        @NotBlank(message = "documentExtractErrorPdf is required")
        private String documentExtractErrorPdf;

        /** Message when document text cannot be extracted. Format: %s = file name, %s = document type. */
        @NotBlank(message = "documentExtractErrorDocument is required")
        private String documentExtractErrorDocument;

        /** RAG augmented prompt template. Format: %s = context text, %s = user question. */
        @NotBlank(message = "augmentedPromptTemplate is required")
        private String augmentedPromptTemplate;

        /** Prompt for extracting text from image-only PDF via vision model. */
        @NotBlank(message = "visionExtractionPrompt is required")
        private String visionExtractionPrompt;
    }
}
