package io.github.ngirchev.opendaimon.common.ai;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LlmParamNamesTest {

    @Test
    void getDouble_returnsNullWhenKeyMissing() {
        assertNull(LlmParamNames.getDouble(Map.of(), "missing"));
    }

    @Test
    void getDouble_returnsDoubleAsIs() {
        assertEquals(0.5, LlmParamNames.getDouble(Map.of("t", 0.5), "t"));
    }

    @Test
    void getDouble_convertsIntegerToDouble() {
        assertEquals(1.0, LlmParamNames.getDouble(Map.of("n", 1), "n"));
    }

    @Test
    void getDouble_convertsLongToDouble() {
        assertEquals(2.0, LlmParamNames.getDouble(Map.of("n", 2L), "n"));
    }

    @Test
    void getDouble_convertsFloatToDouble() {
        assertEquals(0.3, LlmParamNames.getDouble(Map.of("n", 0.3f), "n"), 1e-6);
    }

    @Test
    void getDouble_parsesStringToDouble() {
        assertEquals(1.5, LlmParamNames.getDouble(Map.of("n", "1.5"), "n"));
    }

    @Test
    void getDouble_invalidStringReturnsNull() {
        assertNull(LlmParamNames.getDouble(Map.of("n", "not-a-number"), "n"));
    }

    @Test
    void getDouble_unsupportedTypeReturnsNull() {
        assertNull(LlmParamNames.getDouble(Map.of("n", true), "n"));
    }

    @Test
    void getInteger_returnsNullWhenKeyMissing() {
        assertNull(LlmParamNames.getInteger(Map.of(), "missing"));
    }

    @Test
    void getInteger_returnsIntegerAsIs() {
        assertEquals(42, LlmParamNames.getInteger(Map.of("n", 42), "n"));
    }

    @Test
    void getInteger_convertsLongToInteger() {
        assertEquals(10, LlmParamNames.getInteger(Map.of("n", 10L), "n"));
    }

    @Test
    void getInteger_convertsDoubleToInteger() {
        assertEquals(3, LlmParamNames.getInteger(Map.of("n", 3.7), "n"));
    }

    @Test
    void getInteger_convertsFloatToInteger() {
        assertEquals(2, LlmParamNames.getInteger(Map.of("n", 2.1f), "n"));
    }

    @Test
    void getInteger_parsesStringToInteger() {
        assertEquals(100, LlmParamNames.getInteger(Map.of("n", "100"), "n"));
    }

    @Test
    void getInteger_invalidStringReturnsNull() {
        assertNull(LlmParamNames.getInteger(Map.of("n", "abc"), "n"));
    }

    @Test
    void getInteger_unsupportedTypeReturnsNull() {
        assertNull(LlmParamNames.getInteger(Map.of("n", new Object()), "n"));
    }
}
