package io.github.ngirchev.aibot.common.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import io.github.ngirchev.aibot.common.config.CoreCommonProperties;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TokenCounterTest {

    @Mock
    private CoreCommonProperties coreCommonProperties;

    @Mock
    private CoreCommonProperties.ManualConversationHistoryProperties manualHistory;

    private TokenCounter tokenCounter;

    @BeforeEach
    void setUp() {
        when(coreCommonProperties.getManualConversationHistory()).thenReturn(manualHistory);
        when(manualHistory.getTokenEstimationCharsPerToken()).thenReturn(4);
        tokenCounter = new TokenCounter(coreCommonProperties);
    }

    @Test
    void whenTextIsNull_thenReturnZero() {
        // Act
        String nullText = null;
        int result = tokenCounter.estimateTokens(nullText);

        // Assert
        assertEquals(0, result);
    }

    @Test
    void whenTextIsEmpty_thenReturnZero() {
        // Act
        int result = tokenCounter.estimateTokens("");

        // Assert
        assertEquals(0, result);
    }

    @Test
    void whenTextHasExactMultipleOfCharsPerToken_thenReturnCorrectCount() {
        // Arrange - 8 chars, 4 chars per token = 2 tokens
        String text = "12345678";

        // Act
        int result = tokenCounter.estimateTokens(text);

        // Assert
        assertEquals(2, result);
    }

    @Test
    void whenTextHasRemainder_thenRoundUp() {
        // Arrange - 9 chars, 4 chars per token = 2.25 tokens, round up to 3
        String text = "123456789";

        // Act
        int result = tokenCounter.estimateTokens(text);

        // Assert
        assertEquals(3, result);
    }

    @Test
    void whenTextListIsNull_thenReturnZero() {
        // Act
        int result = tokenCounter.estimateTokens((List<String>) null);

        // Assert
        assertEquals(0, result);
    }

    @Test
    void whenTextListIsEmpty_thenReturnZero() {
        // Act
        int result = tokenCounter.estimateTokens(Collections.emptyList());

        // Assert
        assertEquals(0, result);
    }

    @Test
    void whenTextListHasMultipleTexts_thenReturnSum() {
        // Arrange - each text 4 chars = 1 token, total 3 tokens
        List<String> texts = Arrays.asList("1234", "5678", "9012");

        // Act
        int result = tokenCounter.estimateTokens(texts);

        // Assert
        assertEquals(3, result);
    }
}

