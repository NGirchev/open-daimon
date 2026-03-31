package io.github.ngirchev.opendaimon.common.ai.pipeline;

import io.github.ngirchev.fsm.impl.extended.ExDomainFsm;
import io.github.ngirchev.opendaimon.common.ai.command.AICommand;
import io.github.ngirchev.opendaimon.common.ai.factory.AICommandFactoryRegistry;
import io.github.ngirchev.opendaimon.common.ai.pipeline.fsm.AIRequestContext;
import io.github.ngirchev.opendaimon.common.ai.pipeline.fsm.AIRequestPipelineActions;
import io.github.ngirchev.opendaimon.common.ai.pipeline.fsm.AttachmentEvent;
import io.github.ngirchev.opendaimon.common.ai.pipeline.fsm.AttachmentProcessingContext;
import io.github.ngirchev.opendaimon.common.ai.pipeline.fsm.AttachmentState;
import io.github.ngirchev.opendaimon.common.command.IChatCommand;
import io.github.ngirchev.opendaimon.common.command.ICommand;
import io.github.ngirchev.opendaimon.common.command.ICommandType;
import io.github.ngirchev.opendaimon.common.command.OrchestratedChatCommand;
import io.github.ngirchev.opendaimon.common.model.Attachment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Default implementation of {@link AIRequestPipelineActions}.
 *
 * <p>Ports logic from {@link AIRequestPipeline} into discrete FSM action methods.
 * Each method corresponds to a single FSM transition action and populates
 * the {@link AIRequestContext} with results for subsequent transitions.
 */
@Slf4j
@RequiredArgsConstructor
public class DefaultAIRequestPipelineActions implements AIRequestPipelineActions {

    private static final String DEFAULT_DOCUMENT_QUERY = "Summarize this document and provide key points.";

    private final ExDomainFsm<AttachmentProcessingContext, AttachmentState, AttachmentEvent> documentFsm;
    private final IRagQueryAugmenter ragQueryAugmenter;
    private final AICommandFactoryRegistry factoryRegistry;

    @Override
    public void validate(AIRequestContext ctx) {
        ICommand<?> command = ctx.getCommand();

        if (command instanceof IChatCommand<?> chatCommand) {
            ctx.setChatCommand(chatCommand);
            List<Attachment> attachments = chatCommand.attachments() != null
                    ? chatCommand.attachments() : List.of();
            ctx.setAttachments(attachments);
            ctx.setUserText(chatCommand.userText());
        }

        log.debug("FSM validate: command={}, isChatCommand={}",
                command.getClass().getSimpleName(), ctx.isChatCommand());
    }

    @Override
    public void classify(AIRequestContext ctx) {
        List<Attachment> attachments = ctx.getAttachments();
        Map<String, String> metadata = ctx.getMetadata();

        boolean hasDocuments = attachments.stream().anyMatch(Attachment::isDocument);
        ctx.setHasDocuments(hasDocuments);

        boolean hasFollowUpRag = attachments.isEmpty()
                && metadata != null
                && metadata.containsKey(AICommand.RAG_DOCUMENT_IDS_FIELD);
        ctx.setHasFollowUpRag(hasFollowUpRag);

        // Detect unrecognized attachments when no documents present
        if (!hasDocuments) {
            List<String> unrecognized = attachments.stream()
                    .filter(a -> !a.isImage() && !a.isDocument())
                    .map(a -> a.mimeType() != null ? a.mimeType() : "unknown")
                    .distinct()
                    .toList();
            ctx.setUnrecognizedTypes(unrecognized);
        }

        log.debug("FSM classify: hasDocuments={}, hasFollowUpRag={}, unrecognized={}",
                hasDocuments, hasFollowUpRag, ctx.getUnrecognizedTypes().size());
    }

    @Override
    public void buildPassthrough(AIRequestContext ctx) {
        AICommand result = factoryRegistry.createCommand(ctx.getCommand(), ctx.getMetadata());
        ctx.setResult(result);
        log.debug("FSM buildPassthrough: command passed directly to factory");
    }

    @Override
    public void processFollowUpRag(AIRequestContext ctx) {
        String userQuery = ctx.getUserText();
        Map<String, String> metadata = ctx.getMetadata();

        if (ragQueryAugmenter == null || metadata == null) {
            ctx.setAugmentedQuery(userQuery);
            return;
        }

        String rawDocumentIds = metadata.get(AICommand.RAG_DOCUMENT_IDS_FIELD);
        if (rawDocumentIds == null || rawDocumentIds.isBlank()) {
            ctx.setAugmentedQuery(userQuery);
            return;
        }

        List<String> documentIds = Arrays.stream(rawDocumentIds.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();

        if (documentIds.isEmpty()) {
            ctx.setAugmentedQuery(userQuery);
            return;
        }

        log.info("FSM processFollowUpRag: {} stored documentId(s)", documentIds.size());
        String augmented = ragQueryAugmenter.augmentFromStoredDocuments(userQuery, documentIds);
        ctx.setAugmentedQuery(augmented);
    }

    @Override
    public void processDocuments(AIRequestContext ctx) {
        List<Attachment> attachments = ctx.getAttachments();
        String userText = ctx.getUserText();

        if (userText == null || userText.isBlank()) {
            userText = DEFAULT_DOCUMENT_QUERY;
            ctx.setUserText(userText);
            log.info("Empty user query with attachments, using default summarization prompt");
        }

        log.debug("FSM processDocuments: processing {} attachment(s) via document FSM",
                attachments.size());

        List<AttachmentProcessingContext> contexts = new ArrayList<>();
        for (Attachment attachment : attachments) {
            if (!attachment.isDocument()) {
                continue;
            }

            AttachmentProcessingContext docCtx = new AttachmentProcessingContext(attachment, userText);
            try {
                documentFsm.handle(docCtx, AttachmentEvent.PROCESS);
                log.debug("Document FSM completed: filename={}, state={}",
                        attachment.filename(), docCtx.getState());
            } catch (Exception e) {
                log.error("Document FSM failed for '{}': {}",
                        attachment.filename(), e.getMessage(), e);
                docCtx.setErrorMessage(e.getMessage());
            }
            contexts.add(docCtx);
        }

        ctx.setFsmContexts(contexts);
    }

    @Override
    public void collectResults(AIRequestContext ctx) {
        List<String> allChunkTexts = new ArrayList<>();
        List<String> processedDocumentIds = new ArrayList<>();
        List<String> processedFilenames = new ArrayList<>();
        List<Attachment> mutableAttachments = new ArrayList<>(ctx.getAttachments());
        List<String> pdfAsImageFilenames = new ArrayList<>();

        for (AttachmentProcessingContext docCtx : ctx.getFsmContexts()) {
            if (docCtx.isRagIndexed()) {
                allChunkTexts.addAll(docCtx.getExtractedChunks());
                if (docCtx.getDocumentId() != null) {
                    processedDocumentIds.add(docCtx.getDocumentId());
                    processedFilenames.add(docCtx.getProcessedFilename());
                }
            } else if (docCtx.isImageFallback()) {
                mutableAttachments.addAll(docCtx.getImageAttachments());
                pdfAsImageFilenames.add(docCtx.getProcessedFilename());
                log.info("Added {} fallback image(s) from PDF '{}'",
                        docCtx.getImageAttachments().size(), docCtx.getProcessedFilename());
            } else if (docCtx.isError()) {
                log.warn("Document processing error for '{}': {}",
                        docCtx.getProcessedFilename(), docCtx.getErrorMessage());
            }
        }

        ctx.setAllChunkTexts(allChunkTexts);
        ctx.setProcessedDocumentIds(processedDocumentIds);
        ctx.setProcessedFilenames(processedFilenames);
        ctx.setMutableAttachments(mutableAttachments);
        ctx.setPdfAsImageFilenames(pdfAsImageFilenames);

        // Store in metadata for handler to persist
        Map<String, String> metadata = ctx.getMetadata();
        if (!processedDocumentIds.isEmpty() && metadata != null) {
            metadata.put(AICommand.RAG_DOCUMENT_IDS_FIELD,
                    String.join(",", processedDocumentIds));
            metadata.put(AICommand.RAG_FILENAMES_FIELD,
                    String.join(",", processedFilenames));
        }
        if (!pdfAsImageFilenames.isEmpty() && metadata != null) {
            metadata.put("pdfAsImageFilenames",
                    String.join(",", pdfAsImageFilenames));
        }

        log.debug("FSM collectResults: chunks={}, docIds={}, imageFallbacks={}",
                allChunkTexts.size(), processedDocumentIds.size(), pdfAsImageFilenames.size());
    }

    @Override
    public void augmentQuery(AIRequestContext ctx) {
        String userText = ctx.getUserText();
        String augmentedQuery = userText;

        if (ragQueryAugmenter != null && !ctx.getAllChunkTexts().isEmpty()) {
            augmentedQuery = ragQueryAugmenter.augment(
                    userText, ctx.getAllChunkTexts(), ctx.getProcessedFilenames());
        }

        ctx.setAugmentedQuery(augmentedQuery);
        log.debug("FSM augmentQuery: augmented={}", augmentedQuery != null && !augmentedQuery.equals(userText));
    }

    @Override
    @SuppressWarnings("unchecked")
    public void buildCommand(AIRequestContext ctx) {
        IChatCommand<?> chatCommand = ctx.getChatCommand();
        String augmentedQuery = ctx.getAugmentedQuery();
        List<Attachment> attachments = ctx.getMutableAttachments().isEmpty()
                ? ctx.getAttachments() : ctx.getMutableAttachments();
        Map<String, String> metadata = ctx.getMetadata();

        OrchestratedChatCommand<?> orchestrated = new OrchestratedChatCommand<>(
                chatCommand, augmentedQuery, attachments);

        AICommand result = factoryRegistry.createCommand((ICommand) orchestrated, metadata);
        ctx.setResult(result);

        log.debug("FSM buildCommand: orchestrated command built");
    }

    @Override
    public void handleError(AIRequestContext ctx) {
        String types = String.join(", ", ctx.getUnrecognizedTypes());
        ctx.setErrorMessage("Unsupported file type: " + types);
        log.warn("FSM handleError: unrecognized attachment type(s): {}", types);
    }
}
