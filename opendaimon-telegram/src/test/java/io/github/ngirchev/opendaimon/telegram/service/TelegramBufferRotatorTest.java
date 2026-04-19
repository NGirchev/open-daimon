package io.github.ngirchev.opendaimon.telegram.service;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the cut-selection ladder: paragraph → sentence → whitespace → hard cut.
 * Each tier is exercised in isolation by making only that tier available within
 * the {@code [0, maxLength]} window.
 */
class TelegramBufferRotatorTest {

    @Test
    void shouldReturnEmptyWhenBufferFitsUnderLimit() {
        StringBuilder buf = new StringBuilder("short text");

        Optional<String> head = TelegramBufferRotator.rotateIfExceeds(buf, 50);

        assertThat(head).isEmpty();
        assertThat(buf.toString()).isEqualTo("short text");
    }

    @Test
    void shouldCutAtParagraphBoundaryWhenPresent() {
        // "A".repeat(30) + "\n\n" + "B".repeat(30) → length 62. At maxLength=50 the
        // cut should happen at the "\n\n" boundary (position 30, cut index 32).
        StringBuilder buf = new StringBuilder("A".repeat(30) + "\n\n" + "B".repeat(30));

        Optional<String> head = TelegramBufferRotator.rotateIfExceeds(buf, 50);

        assertThat(head).isPresent();
        assertThat(head.get()).isEqualTo("A".repeat(30) + "\n\n");
        assertThat(buf.toString()).isEqualTo("B".repeat(30));
    }

    @Test
    void shouldFallBackToSentenceBoundaryWhenNoParagraphInWindow() {
        // No "\n\n" in the head window — the rotator should cut at the last ". ".
        // Head: 40 x 'a' + ". " = 42 chars; tail starts at "b…b" of length 20.
        StringBuilder buf = new StringBuilder("a".repeat(40) + ". " + "b".repeat(20));

        Optional<String> head = TelegramBufferRotator.rotateIfExceeds(buf, 50);

        assertThat(head).isPresent();
        assertThat(head.get()).isEqualTo("a".repeat(40) + ". ");
        assertThat(buf.toString()).isEqualTo("b".repeat(20));
    }

    @Test
    void shouldFallBackToWhitespaceWhenNoSentenceBoundary() {
        // Only whitespace separators available in the window.
        StringBuilder buf = new StringBuilder("a".repeat(30) + " " + "b".repeat(30));

        Optional<String> head = TelegramBufferRotator.rotateIfExceeds(buf, 40);

        assertThat(head).isPresent();
        assertThat(head.get()).isEqualTo("a".repeat(30) + " ");
        assertThat(buf.toString()).isEqualTo("b".repeat(30));
    }

    @Test
    void shouldHardCutAtMaxLengthWhenNoBoundaryFound() {
        // Single unbroken run — no paragraph, sentence or whitespace within the window.
        StringBuilder buf = new StringBuilder("x".repeat(100));

        Optional<String> head = TelegramBufferRotator.rotateIfExceeds(buf, 40);

        assertThat(head).isPresent();
        assertThat(head.get()).hasSize(40);
        assertThat(head.get()).isEqualTo("x".repeat(40));
        assertThat(buf.toString()).isEqualTo("x".repeat(60));
    }

    @Test
    void shouldReturnEmptyWhenMaxLengthIsZeroOrNegative() {
        // Defensive: maxLength ≤ 0 means the caller has no sensible limit to enforce.
        StringBuilder buf = new StringBuilder("abc");

        assertThat(TelegramBufferRotator.rotateIfExceeds(buf, 0)).isEmpty();
        assertThat(TelegramBufferRotator.rotateIfExceeds(buf, -5)).isEmpty();
        assertThat(buf.toString()).isEqualTo("abc");
    }

    @Test
    void shouldPickTheLastParagraphBoundaryWithinTheWindow() {
        // Two paragraph boundaries before maxLength: rotator should pick the LAST one.
        StringBuilder buf = new StringBuilder("aa\n\nbb\n\ncc" + "d".repeat(100));

        Optional<String> head = TelegramBufferRotator.rotateIfExceeds(buf, 20);

        assertThat(head).isPresent();
        assertThat(head.get()).isEqualTo("aa\n\nbb\n\n");
        assertThat(buf.toString()).isEqualTo("cc" + "d".repeat(100));
    }
}
