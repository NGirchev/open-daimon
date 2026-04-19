package io.github.ngirchev.opendaimon.telegram.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the stateless debounce / rotation helper shared by status and
 * tentative-answer edit paths in {@code TelegramMessageHandlerActions}.
 */
class TelegramProgressBatcherTest {

    private static final long DEBOUNCE_MS = 500L;

    @Test
    @DisplayName("should flush immediately when forceFlush is true regardless of window")
    void shouldFlushImmediatelyWhenForceFlushTrue() {
        long lastFlushAt = 1_000L;
        long now = 1_100L; // 100 ms later — well inside the debounce window

        boolean result = TelegramProgressBatcher.shouldFlush(lastFlushAt, now, DEBOUNCE_MS, true);

        assertThat(result)
                .as("forceFlush=true must bypass debounce (structural/terminal events)")
                .isTrue();
    }

    @Test
    @DisplayName("should skip edit when within debounce window and not forced")
    void shouldSkipEditWhenWithinDebounceWindow() {
        long lastFlushAt = 1_000L;
        long now = 1_100L; // 100 ms since last flush, window is 500 ms

        boolean result = TelegramProgressBatcher.shouldFlush(lastFlushAt, now, DEBOUNCE_MS, false);

        assertThat(result)
                .as("PARTIAL_ANSWER-style chunk inside the window must be deferred")
                .isFalse();
    }

    @Test
    @DisplayName("should flush after the debounce window has elapsed")
    void shouldFlushAfterDebounceWindowElapsed() {
        long lastFlushAt = 1_000L;
        long now = 1_500L; // exactly on the boundary

        boolean result = TelegramProgressBatcher.shouldFlush(lastFlushAt, now, DEBOUNCE_MS, false);

        assertThat(result)
                .as("window is >= debounceMs, flush should fire on the boundary")
                .isTrue();
    }

    @Test
    @DisplayName("should flush unconditionally when debounceMs is zero (throttling disabled)")
    void shouldFlushUnconditionallyWhenDebounceDisabled() {
        long lastFlushAt = 1_000L;
        long now = 1_000L;

        boolean result = TelegramProgressBatcher.shouldFlush(lastFlushAt, now, 0L, false);

        assertThat(result)
                .as("debounceMs<=0 disables throttling (used by test fixtures)")
                .isTrue();
    }

    @Test
    @DisplayName("should return empty when buffer is within max length — no rotation required")
    void shouldReturnEmptyWhenBufferWithinLimit() {
        StringBuilder buffer = new StringBuilder("short content");

        Optional<String> head = TelegramProgressBatcher.selectContentToFlush(buffer, 100);

        assertThat(head).isEmpty();
        assertThat(buffer).hasToString("short content");
    }

    @Test
    @DisplayName("should select paragraph boundary when buffer exceeds max length")
    void shouldSelectParagraphBoundaryWhenBufferExceedsMaxLength() {
        // Paragraph break at index 16 ("first block.\n\n").
        // maxLength=25 forces rotation; expected cut is right after the "\n\n" that fits in [0,25].
        String first = "First paragraph.";
        String tail = "Second paragraph that pushes the buffer past the limit.";
        StringBuilder buffer = new StringBuilder(first + "\n\n" + tail);

        Optional<String> head = TelegramProgressBatcher.selectContentToFlush(buffer, 25);

        assertThat(head)
                .as("head must stop at the paragraph boundary so the prose is not cut mid-word")
                .hasValue(first + "\n\n");
        assertThat(buffer)
                .as("buffer must be mutated in place to hold only the tail after the cut")
                .hasToString(tail);
    }
}
