package io.github.ngirchev.opendaimon.ai.springai.service;

import io.github.ngirchev.opendaimon.ai.springai.config.RAGProperties;
import io.github.ngirchev.opendaimon.ai.springai.rag.FileRAGService;
import io.github.ngirchev.opendaimon.common.ai.command.AICommand;
import io.github.ngirchev.opendaimon.common.ai.document.DocumentAnalysisResult;
import io.github.ngirchev.opendaimon.common.ai.document.DocumentOrchestrationResult;
import io.github.ngirchev.opendaimon.common.ai.document.DocumentPreprocessingResult;
import io.github.ngirchev.opendaimon.common.ai.document.IDocumentOrchestrator;
import io.github.ngirchev.opendaimon.common.ai.document.IDocumentPreprocessor;
import io.github.ngirchev.opendaimon.common.model.Attachment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Spring AI implementation of {@link IDocumentOrchestrator}.
 *
 * <p>Extracted from {@code SpringAIGateway} to decouple document/RAG processing
 * from the gateway. This allows any AI provider module to reuse the same
 * document processing pipeline.
 *
 * <p>Handles:
 * <ul>
 *   <li>New documents: filter → preprocess via {@link IDocumentPreprocessor} → build RAG query</li>
 *   <li>Follow-up messages: fetch stored RAG chunks → augment query</li>
 *   <li>Metadata management: store document IDs for persistence</li>
 * </ul>
 */
@Slf4j
@RequiredArgsConstructor
public class SpringDocumentOrchestrator implements IDocumentOrchestrator {

    private final IDocumentPreprocessor documentPreprocessor;
    private final FileRAGService fileRagService;
    private final RAGProperties ragProperties;

    @Override
    public DocumentOrchestrationResult orchestrate(String userQuery, List<Attachment> attachments,
                                                    AICommand command) {
        List<Attachment> documentAttachments = attachments != null
                ? attachments.stream().filter(Attachment::isDocument).toList()
                : List.of();

        if (documentAttachments.isEmpty()) {
            // No new documents — check for follow-up RAG
            String augmented = processFollowUpRagIfAvailable(userQuery, command);
            return DocumentOrchestrationResult.unchanged(augmented, attachments != null ? attachments : List.of());
        }

        if (userQuery == null || userQuery.isBlank()) {
            userQuery = "Summarize this document and provide key points.";
            log.info("Empty user query with attachments, using default summarization prompt");
        }

        log.info("Processing {} document attachment(s) for RAG", documentAttachments.size());
        List<String> allRelevantChunkTexts = new ArrayList<>();
        List<String> processedDocumentIds = new ArrayList<>();
        List<String> processedFilenames = new ArrayList<>();
        List<Attachment> mutableAttachments = new ArrayList<>(attachments);
        List<String> pdfAsImageFilenames = new ArrayList<>();

        for (Attachment documentAttachment : documentAttachments) {
            try {
                String documentType = SpringDocumentContentAnalyzer.extractDocumentType(
                        documentAttachment.mimeType(), documentAttachment.filename());
                if (documentType == null) {
                    log.warn("Unsupported document type for RAG: {}", documentAttachment.mimeType());
                    continue;
                }

                // Always start with textExtractable — the preprocessor handles the fallback
                // to vision OCR internally if text extraction fails.
                DocumentAnalysisResult analysisResult = DocumentAnalysisResult.textExtractable();

                DocumentPreprocessingResult result = documentPreprocessor.preprocess(
                        documentAttachment, userQuery, analysisResult);

                if (result.hasDocumentId()) {
                    processedDocumentIds.add(result.documentId());
                    processedFilenames.add(documentAttachment.filename());
                }
                allRelevantChunkTexts.addAll(result.relevantChunkTexts());

                if (!result.visionExtractionSucceeded() && !result.imageAttachments().isEmpty()) {
                    mutableAttachments.addAll(result.imageAttachments());
                    pdfAsImageFilenames.add(documentAttachment.filename());
                    log.info("Added {} fallback image(s) from PDF '{}'",
                            result.imageAttachments().size(), documentAttachment.filename());
                }
            } catch (Exception e) {
                log.error("Failed to process document '{}': {}", documentAttachment.filename(), e.getMessage(), e);
            }
        }

        storeDocumentIdsInCommandMetadata(processedDocumentIds, processedFilenames, command);

        if (allRelevantChunkTexts.isEmpty()) {
            log.info("No relevant context found in documents");
            return new DocumentOrchestrationResult(
                    userQuery, mutableAttachments, pdfAsImageFilenames,
                    processedDocumentIds, processedFilenames);
        }

        String contextText = String.join("\n\n---\n\n", allRelevantChunkTexts);
        String ragQuery = String.format(ragProperties.getPrompts().getAugmentedPromptTemplate(), contextText, userQuery);
        String placeholder = buildRagPlaceholder(documentAttachments);
        String augmentedQuery = ragQuery + "\n" + placeholder;

        log.info("Created RAG augmented query ({} chars) with {} chunks from {} document(s)",
                augmentedQuery.length(), allRelevantChunkTexts.size(), documentAttachments.size());

        return new DocumentOrchestrationResult(
                augmentedQuery, mutableAttachments, pdfAsImageFilenames,
                processedDocumentIds, processedFilenames);
    }

    /**
     * On follow-up messages (no new attachments), checks if the handler has injected stored RAG
     * documentIds into command metadata and fetches relevant chunks from VectorStore.
     */
    private String processFollowUpRagIfAvailable(String userQuery, AICommand command) {
        if (command == null || command.metadata() == null) {
            return userQuery;
        }

        String rawDocumentIds = command.metadata().get(AICommand.RAG_DOCUMENT_IDS_FIELD);
        if (rawDocumentIds == null || rawDocumentIds.isBlank()) {
            return userQuery;
        }

        List<String> ragDocumentIds = Arrays.stream(rawDocumentIds.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();

        if (ragDocumentIds.isEmpty()) {
            return userQuery;
        }

        log.info("RAG follow-up: found {} stored documentId(s), fetching relevant chunks", ragDocumentIds.size());

        List<Document> allChunks = new ArrayList<>();
        for (String docId : ragDocumentIds) {
            try {
                List<Document> chunks = fileRagService.findAllByDocumentId(docId);
                allChunks.addAll(chunks);
            } catch (Exception e) {
                log.warn("RAG follow-up: failed to fetch chunks for documentId={}: {}", docId, e.getMessage());
            }
        }

        if (allChunks.isEmpty()) {
            log.info("RAG follow-up: VectorStore returned no chunks for stored documentIds (may be lost after restart)");
            return userQuery;
        }

        String contextText = allChunks.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n\n---\n\n"));
        String ragQuery = String.format(ragProperties.getPrompts().getAugmentedPromptTemplate(), contextText, userQuery);
        log.info("RAG follow-up: augmented query with {} relevant chunks ({} chars)", allChunks.size(), ragQuery.length());
        return ragQuery;
    }

    private void storeDocumentIdsInCommandMetadata(List<String> documentIds,
                                                    List<String> filenames,
                                                    AICommand command) {
        if (documentIds.isEmpty() || command == null || command.metadata() == null) {
            return;
        }
        try {
            command.metadata().put(AICommand.RAG_DOCUMENT_IDS_FIELD, String.join(",", documentIds));
            command.metadata().put(AICommand.RAG_FILENAMES_FIELD, String.join(",", filenames));
            log.info("RAG: stored {} documentId(s) in command metadata", documentIds.size());
        } catch (UnsupportedOperationException ignored) {
            log.warn("RAG: command.metadata() is immutable, documentIds not persisted: {}", documentIds);
        }
    }

    private String buildRagPlaceholder(List<Attachment> documentAttachments) {
        StringBuilder sb = new StringBuilder("[Documents loaded for context: ");
        for (int i = 0; i < documentAttachments.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(documentAttachments.get(i).filename());
        }
        sb.append("]");
        return sb.toString();
    }
}
