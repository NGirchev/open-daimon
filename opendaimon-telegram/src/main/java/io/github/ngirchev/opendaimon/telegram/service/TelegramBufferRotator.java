package io.github.ngirchev.opendaimon.telegram.service;

import java.util.Optional;

/**
 * Rotates a mutable {@link StringBuilder} that accumulates Telegram message text.
 *
 * <p>When the buffer grows past {@code maxLength}, the head is extracted and the buffer
 * is mutated to hold only the tail. The extracted head is returned so the caller can
 * send it as the finalized (previous) message, leaving the tail to continue the live-edit
 * cycle in a new bubble.
 *
 * <p>Cut selection uses a priority ladder for graceful wrapping:
 * <ol>
 *   <li>last paragraph separator ({@code \n\n}) at or before {@code maxLength};</li>
 *   <li>last sentence terminator ({@code . }, {@code ! }, {@code ? }) at or before {@code maxLength};</li>
 *   <li>last whitespace at or before {@code maxLength};</li>
 *   <li>hard cut at {@code maxLength}.</li>
 * </ol>
 */
public final class TelegramBufferRotator {

    private TelegramBufferRotator() {}

    /**
     * Mutates {@code buf} in place: if it exceeds {@code maxLength}, the head is removed and
     * returned; otherwise returns {@link Optional#empty()} and leaves the buffer untouched.
     *
     * @param buf       mutable buffer; will be truncated to the tail if rotation fires
     * @param maxLength maximum length of a single Telegram message (characters)
     * @return the extracted head (ready to be sent as the now-finalized previous message) or empty
     */
    public static Optional<String> rotateIfExceeds(StringBuilder buf, int maxLength) {
        if (maxLength <= 0 || buf.length() <= maxLength) {
            return Optional.empty();
        }

        int cut = findCut(buf, maxLength);
        String head = buf.substring(0, cut);
        // Preserve the tail starting at the cut index. We intentionally keep the leading
        // whitespace / newlines of the tail — they'll be trimmed by Telegram's renderer.
        String tail = buf.substring(cut);
        buf.setLength(0);
        buf.append(tail);
        return Optional.of(head);
    }

    private static int findCut(StringBuilder buf, int maxLength) {
        // Look only in [0, maxLength] — cuts beyond that would defeat the purpose.
        String window = buf.substring(0, Math.min(buf.length(), maxLength));

        int paragraph = window.lastIndexOf("\n\n");
        if (paragraph > 0) {
            return paragraph + 2;
        }

        int sentence = lastSentenceBoundary(window);
        if (sentence > 0) {
            return sentence;
        }

        int whitespace = lastWhitespace(window);
        if (whitespace > 0) {
            return whitespace + 1;
        }

        return maxLength;
    }

    private static int lastSentenceBoundary(String window) {
        int dot = window.lastIndexOf(". ");
        int bang = window.lastIndexOf("! ");
        int q = window.lastIndexOf("? ");
        int best = Math.max(dot, Math.max(bang, q));
        return best > 0 ? best + 2 : -1;
    }

    private static int lastWhitespace(String window) {
        for (int i = window.length() - 1; i >= 0; i--) {
            if (Character.isWhitespace(window.charAt(i))) {
                return i;
            }
        }
        return -1;
    }
}
