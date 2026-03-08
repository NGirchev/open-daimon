package io.github.ngirchev.aibot.common.model;

/**
 * Enumeration of possible service response statuses.
 */
public enum ResponseStatus {
    /**
     * Response created but not yet sent to user
     */
    PENDING,
    
    /**
     * Response successfully sent to user
     */
    SUCCESS,
    
    /**
     * Error occurred while processing request or sending response
     */
    ERROR
} 