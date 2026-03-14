package io.github.ngirchev.opendaimon.common.exception;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UserMessageTooLongExceptionTest {

    @Test
    void constructorWithMessage_setsMessageAndZeroTokens() {
        UserMessageTooLongException ex = new UserMessageTooLongException("Too long");
        assertEquals("Too long", ex.getMessage());
        assertEquals(0, ex.getEstimatedTokens());
        assertEquals(0, ex.getMaxAllowed());
    }

    @Test
    void constructorWithMessageAndCause_setsCause() {
        Throwable cause = new IllegalArgumentException("bad");
        UserMessageTooLongException ex = new UserMessageTooLongException("Too long", cause);
        assertEquals("Too long", ex.getMessage());
        assertEquals(cause, ex.getCause());
        assertEquals(0, ex.getEstimatedTokens());
        assertEquals(0, ex.getMaxAllowed());
    }

    @Test
    void constructorWithTokens_setsTokensAndFormattedMessage() {
        UserMessageTooLongException ex = new UserMessageTooLongException(1500, 1000);
        assertEquals(1500, ex.getEstimatedTokens());
        assertEquals(1000, ex.getMaxAllowed());
        assertEquals("Message too long: 1500 tokens, limit 1000", ex.getMessage());
    }
}
