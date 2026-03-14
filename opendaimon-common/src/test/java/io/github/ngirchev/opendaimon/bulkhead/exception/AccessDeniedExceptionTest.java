package io.github.ngirchev.opendaimon.bulkhead.exception;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AccessDeniedExceptionTest {

    @Test
    void constructorWithMessage_setsMessage() {
        AccessDeniedException ex = new AccessDeniedException("Access denied");
        assertEquals("Access denied", ex.getMessage());
    }

    @Test
    void constructorWithMessageAndCause_setsBoth() {
        Throwable cause = new IllegalStateException("blocked");
        AccessDeniedException ex = new AccessDeniedException("Denied", cause);
        assertEquals("Denied", ex.getMessage());
        assertEquals(cause, ex.getCause());
    }
}
