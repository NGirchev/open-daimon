package io.github.ngirchev.opendaimon.common.exception;

/**
 * Thrown when text cannot be extracted from an attached document (PDF, DOCX, etc.).
 * E.g. image-only PDF, empty pages. The message is intended for display to the user.
 */
public class DocumentContentNotExtractableException extends RuntimeException {

    public DocumentContentNotExtractableException(String message) {
        super(message);
    }

    public DocumentContentNotExtractableException(String message, Throwable cause) {
        super(message, cause);
    }
}
