package io.github.ngirchev.opendaimon.common.model;

/**
 * Enumeration of message roles in dialog.
 * Matches Spring AI Message roles.
 */
public enum MessageRole {
    /**
     * Message from user
     */
    USER,
    
    /**
     * Message from assistant (AI)
     */
    ASSISTANT,
    
    /**
     * System message (system prompt, summary, etc.)
     */
    SYSTEM
}

