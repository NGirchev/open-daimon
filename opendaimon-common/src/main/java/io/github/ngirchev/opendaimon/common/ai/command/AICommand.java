package io.github.ngirchev.opendaimon.common.ai.command;

import io.github.ngirchev.opendaimon.common.ai.ModelCapabilities;

import java.util.Map;
import java.util.Set;

public interface AICommand {
    String ROLE_FIELD = "role";
    String PREFERRED_MODEL_ID_FIELD = "preferredModelId";
    String THREAD_KEY_FIELD = "threadKey";
    String ASSISTANT_ROLE_ID_FIELD = "assistantRoleId";
    String USER_ID_FIELD = "userId";
    String LANGUAGE_CODE_FIELD = "languageCode";
    String USER_PRIORITY_FIELD = "userPriority";
    /** Comma-separated RAG documentIds stored on the USER message that had the attachment. */
    String RAG_DOCUMENT_IDS_FIELD = "ragDocumentIds";
    /** Comma-separated RAG filenames corresponding to RAG_DOCUMENT_IDS_FIELD entries. */
    String RAG_FILENAMES_FIELD = "ragFilenames";

    Set<ModelCapabilities> modelCapabilities();

    /**
     * Optional (preferred) capabilities — used to rank candidates but do NOT block selection.
     * If no candidate has them, the best required-only match is used instead.
     */
    default Set<ModelCapabilities> optionalCapabilities() { return Set.of(); }

    Map<String, String> metadata();
    <T extends AICommandOptions> T options();

    /**
     * Pipeline-prepared user text (RAG-augmented / document-aware).
     * Returns {@code null} for command types that carry no user-facing text.
     */
    default String userRole() { return null; }
}
