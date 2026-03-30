package io.github.ngirchev.opendaimon.common.ai.document;

import io.github.ngirchev.opendaimon.common.ai.command.AICommand;
import io.github.ngirchev.opendaimon.common.model.Attachment;

import java.util.List;

/**
 * Orchestrates document processing for the AI pipeline.
 *
 * <p>Handles the full document lifecycle before the gateway call:
 * <ul>
 *   <li>New documents: analyze, preprocess (text extraction or vision OCR), index in RAG,
 *       build augmented query with relevant context</li>
 *   <li>Follow-up messages: fetch stored RAG chunks by document IDs from command metadata,
 *       augment query with previously indexed context</li>
 * </ul>
 *
 * <p>This interface lives in {@code opendaimon-common} so that any AI provider module
 * (not just {@code opendaimon-spring-ai}) can implement it. The gateway delegates
 * document processing to this orchestrator instead of handling it internally.
 *
 * <p>Implementations are responsible for:
 * <ul>
 *   <li>Filtering document attachments from the attachment list</li>
 *   <li>Delegating to {@link IDocumentPreprocessor} for each document</li>
 *   <li>Building RAG-augmented queries from preprocessing results</li>
 *   <li>Storing document IDs in command metadata for persistence</li>
 *   <li>Handling follow-up RAG context retrieval</li>
 * </ul>
 */
public interface IDocumentOrchestrator {

    /**
     * Processes document attachments and builds augmented query with RAG context.
     *
     * <p>For new documents: preprocesses each document, indexes in RAG, and builds
     * an augmented query with relevant context chunks.
     *
     * <p>For follow-up messages (no new documents): retrieves stored RAG chunks
     * from command metadata and augments the query.
     *
     * @param userQuery original user query text
     * @param attachments mutable list of attachments (may be modified with rendered images)
     * @param command AI command with metadata (includes threadKey, ragDocumentIds for follow-ups)
     * @return orchestration result with augmented query, modified attachments, and metadata
     */
    DocumentOrchestrationResult orchestrate(String userQuery, List<Attachment> attachments, AICommand command);
}
