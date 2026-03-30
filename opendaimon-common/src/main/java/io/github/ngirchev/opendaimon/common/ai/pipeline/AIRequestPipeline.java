package io.github.ngirchev.opendaimon.common.ai.pipeline;

import io.github.ngirchev.opendaimon.common.ai.command.AICommand;
import io.github.ngirchev.opendaimon.common.ai.document.DocumentOrchestrationResult;
import io.github.ngirchev.opendaimon.common.ai.document.IDocumentOrchestrator;
import io.github.ngirchev.opendaimon.common.ai.factory.AICommandFactoryRegistry;
import io.github.ngirchev.opendaimon.common.command.IChatCommand;
import io.github.ngirchev.opendaimon.common.command.ICommand;
import io.github.ngirchev.opendaimon.common.command.OrchestratedChatCommand;
import io.github.ngirchev.opendaimon.common.model.Attachment;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates the full request preparation pipeline: document preprocessing → command creation.
 *
 * <p>Handlers call {@link #prepareCommand} instead of {@code AICommandFactoryRegistry.createCommand()}
 * directly. This ensures document processing (RAG indexing, vision OCR) happens BEFORE
 * the command factory determines model capabilities.
 *
 * <p>Flow:
 * <ol>
 *   <li>If documents are present and orchestrator is available:
 *       orchestrate documents → augmented query + modified attachments</li>
 *   <li>Wrap original command with orchestrated data</li>
 *   <li>Factory creates AICommand — sees correct attachments (IMAGE from PDF if OCR failed)
 *       and augmented query text</li>
 * </ol>
 *
 * <p>When no orchestrator is available (RAG disabled), falls through to factory directly.
 */
@Slf4j
public class AIRequestPipeline {

    private final IDocumentOrchestrator documentOrchestrator;
    private final AICommandFactoryRegistry factoryRegistry;

    public AIRequestPipeline(IDocumentOrchestrator documentOrchestrator,
                              AICommandFactoryRegistry factoryRegistry) {
        this.documentOrchestrator = documentOrchestrator;
        this.factoryRegistry = factoryRegistry;
    }

    /**
     * Prepares an AICommand by running document orchestration before the factory.
     *
     * @param command  original chat command from handler
     * @param metadata mutable metadata map (orchestrator stores ragDocumentIds here)
     * @return AICommand with correct capabilities and augmented query
     */
    @SuppressWarnings("unchecked")
    public AICommand prepareCommand(ICommand<?> command, Map<String, String> metadata) {
        if (documentOrchestrator == null || !(command instanceof IChatCommand<?> chatCommand)) {
            return factoryRegistry.createCommand(command, metadata);
        }

        List<Attachment> attachments = chatCommand.attachments() != null
                ? chatCommand.attachments() : List.of();
        String userText = chatCommand.userText();

        // Check if there's work for the orchestrator (documents or follow-up RAG docIds)
        boolean hasDocuments = attachments.stream().anyMatch(Attachment::isDocument);
        // Follow-up RAG only applies when there are NO new attachments at all.
        // If the user sends a new image or document, it's a new request — not a follow-up.
        boolean hasFollowUpRag = attachments.isEmpty()
                && metadata != null
                && metadata.containsKey(AICommand.RAG_DOCUMENT_IDS_FIELD);

        // Detect attachments that are neither IMAGE nor recognized document.
        // These would be silently ignored — the model would answer without seeing the file content.
        // Fail fast with a clear error instead of giving a misleading answer.
        if (!hasDocuments) {
            List<Attachment> unrecognized = attachments.stream()
                    .filter(a -> !a.isImage() && !a.isDocument())
                    .toList();
            if (!unrecognized.isEmpty()) {
                String files = unrecognized.stream()
                        .map(a -> a.mimeType() != null ? a.mimeType() : "unknown")
                        .distinct()
                        .collect(java.util.stream.Collectors.joining(", "));
                log.warn("AIRequestPipeline: unrecognized attachment type(s): {}", files);
                throw new io.github.ngirchev.opendaimon.common.exception.DocumentContentNotExtractableException(
                        "Unsupported file type: " + files);
            }
        }

        if (!hasDocuments && !hasFollowUpRag) {
            return factoryRegistry.createCommand(command, metadata);
        }

        log.debug("AIRequestPipeline: orchestrating documents before factory. docs={}, followUpRag={}",
                hasDocuments, hasFollowUpRag);

        // Build a lightweight AICommand-like wrapper to pass metadata for follow-up RAG
        DocumentOrchestrationResult result = documentOrchestrator.orchestrate(
                userText, new ArrayList<>(attachments), new MetadataOnlyCommand(metadata));

        // Store document IDs in metadata for handler to persist
        if (result.hasProcessedDocuments()) {
            metadata.put(AICommand.RAG_DOCUMENT_IDS_FIELD,
                    String.join(",", result.processedDocumentIds()));
            metadata.put(AICommand.RAG_FILENAMES_FIELD,
                    String.join(",", result.processedFilenames()));
        }

        // Store pdfAsImageFilenames in metadata for gateway's attachment context message
        if (!result.pdfAsImageFilenames().isEmpty()) {
            metadata.put("pdfAsImageFilenames",
                    String.join(",", result.pdfAsImageFilenames()));
        }

        // Wrap original command with orchestrated data
        OrchestratedChatCommand<?> orchestrated = new OrchestratedChatCommand<>(
                chatCommand,
                result.augmentedUserQuery(),
                result.attachments());

        return factoryRegistry.createCommand((ICommand) orchestrated, metadata);
    }

    /**
     * Minimal AICommand implementation to pass metadata to the orchestrator
     * for follow-up RAG document ID lookup.
     */
    private record MetadataOnlyCommand(Map<String, String> metadata) implements AICommand {
        @Override
        public java.util.Set<io.github.ngirchev.opendaimon.common.ai.ModelCapabilities> modelCapabilities() {
            return java.util.Set.of();
        }

        @Override
        public <T extends io.github.ngirchev.opendaimon.common.ai.command.AICommandOptions> T options() {
            return null;
        }
    }
}
