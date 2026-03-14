package io.github.ngirchev.opendaimon.common.exception;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DocumentContentNotExtractableExceptionTest {

    @Test
    void constructorWithMessage_setsMessage() {
        DocumentContentNotExtractableException ex = new DocumentContentNotExtractableException("No text in PDF");
        assertEquals("No text in PDF", ex.getMessage());
    }

    @Test
    void constructorWithMessageAndCause_setsBoth() {
        Throwable cause = new RuntimeException("underlying");
        DocumentContentNotExtractableException ex =
                new DocumentContentNotExtractableException("No text", cause);
        assertEquals("No text", ex.getMessage());
        assertEquals(cause, ex.getCause());
    }
}
