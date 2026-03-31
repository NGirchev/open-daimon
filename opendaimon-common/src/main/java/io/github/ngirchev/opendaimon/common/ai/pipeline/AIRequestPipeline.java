package io.github.ngirchev.opendaimon.common.ai.pipeline;

import io.github.ngirchev.fsm.impl.extended.ExDomainFsm;
import io.github.ngirchev.opendaimon.common.ai.command.AICommand;
import io.github.ngirchev.opendaimon.common.ai.factory.AICommandFactoryRegistry;
import io.github.ngirchev.opendaimon.common.ai.pipeline.fsm.AttachmentEvent;
import io.github.ngirchev.opendaimon.common.ai.pipeline.fsm.AttachmentProcessingContext;
import io.github.ngirchev.opendaimon.common.ai.pipeline.fsm.AttachmentState;
import io.github.ngirchev.opendaimon.common.command.IChatCommand;
import io.github.ngirchev.opendaimon.common.command.ICommand;
import io.github.ngirchev.opendaimon.common.command.ICommandType;
import io.github.ngirchev.opendaimon.common.command.OrchestratedChatCommand;
import io.github.ngirchev.opendaimon.common.exception.DocumentContentNotExtractableException;
import io.github.ngirchev.opendaimon.common.model.Attachment;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Orchestrates the full request preparation pipeline using a Finite State Machine
 * for document processing.
 *
 * <p>Handlers call {@link #prepareCommand} instead of {@code AICommandFactoryRegistry.createCommand()}
 * directly. This ensures document processing (RAG indexing, vision OCR) happens BEFORE
 * the command factory determines model capabilities.
 *
 * <p>Flow:
 * <ol>
 *   <li>If documents are present and FSM is available:
 *       each document runs through the FSM (RECEIVED → terminal state)</li>
 *   <li>Results collected: augmented query + modified attachments</li>
 *   <li>Factory creates AICommand with correct capabilities</li>
 * </ol>
 *
 * <p>When no FSM is available (RAG disabled), falls through to factory directly.
 */
@Slf4j
public class AIRequestPipeline {

    private static final String DEFAULT_DOCUMENT_QUERY = "Summarize this document and provide key points.";

    private final ExDomainFsm<AttachmentProcessingContext, AttachmentState, AttachmentEvent> documentFsm;
    private final IRagQueryAugmenter ragQueryAugmenter;
    private final AICommandFactoryRegistry factoryRegistry;

    public AIRequestPipeline(
            ExDomainFsm<AttachmentProcessingContext, AttachmentState, AttachmentEvent> documentFsm,
            IRagQueryAugmenter ragQueryAugmenter,
            AICommandFactoryRegistry factoryRegistry) {
        this.documentFsm = documentFsm;
        this.ragQueryAugmenter = ragQueryAugmenter;
        this.factoryRegistry = factoryRegistry;
    }

    /**
     * Prepares an AICommand by running document processing FSM before the factory.
     *
     * @param command  original chat command from handler
     * @param metadata mutable metadata map (stores ragDocumentIds, pdfAsImageFilenames)
     * @return AICommand with correct capabilities and augmented query
     */
    @SuppressWarnings("unchecked")
    public AICommand prepareCommand(ICommand<?> command, Map<String, String> metadata) {
        if (documentFsm == null || !(command instanceof IChatCommand<?> chatCommand)) {
            return factoryRegistry.createCommand(command, metadata);
        }

        List<Attachment> attachments = chatCommand.attachments() != null
                ? chatCommand.attachments() : List.of();
        String userText = chatCommand.userText();

        boolean hasDocuments = attachments.stream().anyMatch(Attachment::isDocument);
        // Follow-up RAG only applies when there are NO new attachments at all.
        boolean hasFollowUpRag = attachments.isEmpty()
                && metadata != null
                && metadata.containsKey(AICommand.RAG_DOCUMENT_IDS_FIELD);

        // Detect unrecognized attachments — fail fast instead of silently ignoring
        if (!hasDocuments) {
            List<Attachment> unrecognized = attachments.stream()
                    .filter(a -> !a.isImage() && !a.isDocument())
                    .toList();
            if (!unrecognized.isEmpty()) {
                String files = unrecognized.stream()
                        .map(a -> a.mimeType() != null ? a.mimeType() : "unknown")
                        .distinct()
                        .collect(Collectors.joining(", "));
                log.warn("AIRequestPipeline: unrecognized attachment type(s): {}", files);
                throw new DocumentContentNotExtractableException("Unsupported file type: " + files);
            }
        }

        if (!hasDocuments && !hasFollowUpRag) {
            return factoryRegistry.createCommand(command, metadata);
        }

        // --- Follow-up RAG path (no new documents) ---
        if (!hasDocuments) {
            String augmented = processFollowUpRag(userText, metadata);
            return buildOrchestratedCommand(chatCommand, augmented, attachments, metadata);
        }

        // --- Document processing via FSM ---
        if (userText == null || userText.isBlank()) {
            userText = DEFAULT_DOCUMENT_QUERY;
            log.info("Empty user query with attachments, using default summarization prompt");
        }

        log.debug("AIRequestPipeline: processing {} attachment(s) via FSM", attachments.size());

        List<AttachmentProcessingContext> contexts = processDocumentsThroughFsm(attachments, userText);
        return buildResultFromContexts(chatCommand, contexts, userText, attachments, metadata);
    }

    /**
     * Runs each document attachment through the FSM.
     * Images are skipped (they pass directly to gateway).
     */
    private List<AttachmentProcessingContext> processDocumentsThroughFsm(
            List<Attachment> attachments, String userText) {

        List<AttachmentProcessingContext> contexts = new ArrayList<>();

        for (Attachment attachment : attachments) {
            if (!attachment.isDocument()) {
                continue; // images pass through directly
            }

            AttachmentProcessingContext ctx = new AttachmentProcessingContext(attachment, userText);
            try {
                documentFsm.handle(ctx, AttachmentEvent.PROCESS);
                log.debug("FSM completed: filename={}, state={}", attachment.filename(), ctx.getState());
            } catch (Exception e) {
                log.error("FSM failed for '{}': {}", attachment.filename(), e.getMessage(), e);
                ctx.setErrorMessage(e.getMessage());
            }
            contexts.add(ctx);
        }

        return contexts;
    }

    /**
     * Collects results from FSM contexts and builds the final orchestrated command.
     */
    @SuppressWarnings("unchecked")
    private <T extends ICommandType> AICommand buildResultFromContexts(
            IChatCommand<T> chatCommand,
            List<AttachmentProcessingContext> contexts,
            String userText,
            List<Attachment> originalAttachments,
            Map<String, String> metadata) {

        List<String> allChunkTexts = new ArrayList<>();
        List<String> processedDocumentIds = new ArrayList<>();
        List<String> processedFilenames = new ArrayList<>();
        List<Attachment> mutableAttachments = new ArrayList<>(originalAttachments);
        List<String> pdfAsImageFilenames = new ArrayList<>();

        for (AttachmentProcessingContext ctx : contexts) {
            if (ctx.isRagIndexed()) {
                // Successfully indexed — collect chunks and IDs
                allChunkTexts.addAll(ctx.getExtractedChunks());
                if (ctx.getDocumentId() != null) {
                    processedDocumentIds.add(ctx.getDocumentId());
                    processedFilenames.add(ctx.getProcessedFilename());
                }
            } else if (ctx.isImageFallback()) {
                // Vision OCR failed — add rendered images for direct vision
                mutableAttachments.addAll(ctx.getImageAttachments());
                pdfAsImageFilenames.add(ctx.getProcessedFilename());
                log.info("Added {} fallback image(s) from PDF '{}'",
                        ctx.getImageAttachments().size(), ctx.getProcessedFilename());
            } else if (ctx.isError()) {
                log.warn("Document processing error for '{}': {}",
                        ctx.getProcessedFilename(), ctx.getErrorMessage());
            }
        }

        // Store document IDs in metadata for handler to persist
        if (!processedDocumentIds.isEmpty() && metadata != null) {
            metadata.put(AICommand.RAG_DOCUMENT_IDS_FIELD,
                    String.join(",", processedDocumentIds));
            metadata.put(AICommand.RAG_FILENAMES_FIELD,
                    String.join(",", processedFilenames));
        }

        // Store pdfAsImageFilenames in metadata for gateway's attachment context message
        if (!pdfAsImageFilenames.isEmpty() && metadata != null) {
            metadata.put("pdfAsImageFilenames",
                    String.join(",", pdfAsImageFilenames));
        }

        // Augment query with RAG context
        String augmentedQuery = userText;
        if (ragQueryAugmenter != null && !allChunkTexts.isEmpty()) {
            augmentedQuery = ragQueryAugmenter.augment(userText, allChunkTexts, processedFilenames);
        }

        return buildOrchestratedCommand(chatCommand, augmentedQuery, mutableAttachments, metadata);
    }

    /**
     * Handles follow-up messages where documents were processed in a prior turn.
     */
    private String processFollowUpRag(String userQuery, Map<String, String> metadata) {
        if (ragQueryAugmenter == null || metadata == null) {
            return userQuery;
        }

        String rawDocumentIds = metadata.get(AICommand.RAG_DOCUMENT_IDS_FIELD);
        if (rawDocumentIds == null || rawDocumentIds.isBlank()) {
            return userQuery;
        }

        List<String> documentIds = Arrays.stream(rawDocumentIds.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();

        if (documentIds.isEmpty()) {
            return userQuery;
        }

        log.info("AIRequestPipeline: follow-up RAG with {} stored documentId(s)", documentIds.size());
        return ragQueryAugmenter.augmentFromStoredDocuments(userQuery, documentIds);
    }

    /**
     * Wraps the original command with orchestrated data and delegates to factory.
     */
    @SuppressWarnings("unchecked")
    private <T extends ICommandType> AICommand buildOrchestratedCommand(
            IChatCommand<T> chatCommand,
            String augmentedQuery,
            List<Attachment> attachments,
            Map<String, String> metadata) {

        OrchestratedChatCommand<T> orchestrated = new OrchestratedChatCommand<>(
                chatCommand, augmentedQuery, attachments);

        return factoryRegistry.createCommand((ICommand) orchestrated, metadata);
    }
}
