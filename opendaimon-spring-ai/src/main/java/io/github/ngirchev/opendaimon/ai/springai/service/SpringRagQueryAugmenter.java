package io.github.ngirchev.opendaimon.ai.springai.service;

import io.github.ngirchev.opendaimon.ai.springai.config.RAGProperties;
import io.github.ngirchev.opendaimon.ai.springai.rag.FileRAGService;
import io.github.ngirchev.opendaimon.common.ai.pipeline.IRagQueryAugmenter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Spring AI implementation of {@link IRagQueryAugmenter}.
 *
 * <p>Uses {@link FileRAGService} for VectorStore chunk retrieval
 * and {@link RAGProperties} for prompt templates.
 */
@Slf4j
@RequiredArgsConstructor
public class SpringRagQueryAugmenter implements IRagQueryAugmenter {

    private final FileRAGService fileRagService;
    private final RAGProperties ragProperties;

    @Override
    public String augment(String userQuery, List<String> chunkTexts, List<String> documentFilenames) {
        if (chunkTexts == null || chunkTexts.isEmpty()) {
            return userQuery;
        }

        String contextText = String.join("\n\n---\n\n", chunkTexts);
        String ragQuery = String.format(
                ragProperties.getPrompts().getAugmentedPromptTemplate(), contextText, userQuery);
        String placeholder = buildRagPlaceholder(documentFilenames);
        String augmentedQuery = ragQuery + "\n" + placeholder;

        log.info("Created RAG augmented query ({} chars) with {} chunks from {} document(s)",
                augmentedQuery.length(), chunkTexts.size(), documentFilenames.size());

        return augmentedQuery;
    }

    @Override
    public String augmentFromStoredDocuments(String userQuery, List<String> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) {
            return userQuery;
        }

        log.info("RAG follow-up: fetching chunks for {} stored documentId(s)", documentIds.size());

        List<Document> allChunks = new ArrayList<>();
        for (String docId : documentIds) {
            try {
                List<Document> chunks = fileRagService.findAllByDocumentId(docId);
                allChunks.addAll(chunks);
            } catch (Exception e) {
                log.warn("RAG follow-up: failed to fetch chunks for documentId={}: {}", docId, e.getMessage());
            }
        }

        if (allChunks.isEmpty()) {
            log.info("RAG follow-up: VectorStore returned no chunks (may be lost after restart)");
            return userQuery;
        }

        String contextText = allChunks.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n\n---\n\n"));
        String ragQuery = String.format(
                ragProperties.getPrompts().getAugmentedPromptTemplate(), contextText, userQuery);
        log.info("RAG follow-up: augmented query with {} chunks ({} chars)", allChunks.size(), ragQuery.length());
        return ragQuery;
    }

    private String buildRagPlaceholder(List<String> documentFilenames) {
        StringBuilder sb = new StringBuilder("[Documents loaded for context: ");
        for (int i = 0; i < documentFilenames.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(documentFilenames.get(i));
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * @deprecated Use {@link IRagQueryAugmenter#parseDocumentIds(String)} instead.
     */
    @Deprecated(forRemoval = true)
    public static List<String> parseDocumentIds(String rawDocumentIds) {
        return IRagQueryAugmenter.parseDocumentIds(rawDocumentIds);
    }
}
