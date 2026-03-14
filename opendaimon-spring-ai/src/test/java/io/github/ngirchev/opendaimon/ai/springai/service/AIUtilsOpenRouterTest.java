package io.github.ngirchev.opendaimon.ai.springai.service;

import io.github.ngirchev.opendaimon.common.service.AIUtils;
import io.github.ngirchev.opendaimon.ai.springai.retry.OpenRouterEmptyStreamException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers AIUtils.isOpenRouterEmptyStreamInChain when the cause chain contains
 * OpenRouterEmptyStreamException (class lives in this module).
 */
class AIUtilsOpenRouterTest {

    @Test
    void isOpenRouterEmptyStreamInChain_whenDirectException_returnsTrue() {
        assertTrue(AIUtils.isOpenRouterEmptyStreamInChain(new OpenRouterEmptyStreamException("empty stream")));
    }

    @Test
    void isOpenRouterEmptyStreamInChain_whenCauseIsOpenRouterEmptyStreamException_returnsTrue() {
        Throwable cause = new OpenRouterEmptyStreamException("empty");
        RuntimeException wrapped = new RuntimeException("wrapped", cause);
        assertTrue(AIUtils.isOpenRouterEmptyStreamInChain(wrapped));
    }
}
