package io.github.ngirchev.opendaimon.common.service;

import java.util.ArrayList;
import java.util.List;

/**
 * Stateful paragraph batcher — synchronous counterpart of {@link AIUtils#paragraphize}.
 *
 * <p>Accepts raw text chunks via {@link #feed(String)} and returns ready-to-render blocks
 * grouped by paragraph boundaries ({@code \n\n}). Short paragraphs are accumulated until they
 * reach {@code minParagraphLength}; oversized paragraphs are split at word boundaries to respect
 * {@code maxMessageLength}. On stream end, {@link #flush()} drains any remaining buffered content.
 *
 * <p>Intended for FSM/event-driven consumers that cannot use a reactive {@link reactor.core.publisher.Flux}
 * operator but need the same batching semantics (e.g. Telegram's PARTIAL_ANSWER handler).
 *
 * <p>Not thread-safe — each consumer session should own its own instance.
 */
public final class ParagraphBatcher {

    private final int maxMessageLength;
    private final int minParagraphLength;

    private String tail = "";
    private String accumulatedShortParagraphs = "";
    private String overflowBuffer = "";

    public ParagraphBatcher(int maxMessageLength) {
        this.maxMessageLength = maxMessageLength;
        this.minParagraphLength = Math.min(300, maxMessageLength);
    }

    public List<String> feed(String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return List.of();
        }
        List<String> ready = new ArrayList<>();
        List<String> paragraphs = splitChunkIntoParagraphs(chunk);
        for (String paragraph : paragraphs) {
            String trimmed = paragraph.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String grouped = groupByMinLength(trimmed);
            if (grouped != null) {
                splitByMaxLength(grouped, ready);
            }
        }
        return ready;
    }

    public List<String> flush() {
        List<String> ready = new ArrayList<>();
        String remainingTail = tail.trim();
        String finalTail = overflowBuffer.isEmpty()
                ? remainingTail
                : (remainingTail.isEmpty() ? overflowBuffer : remainingTail + "\n\n" + overflowBuffer);
        overflowBuffer = "";
        tail = "";

        if (!finalTail.isEmpty()) {
            if (finalTail.length() > maxMessageLength) {
                splitByMaxLength(finalTail, ready);
                if (!overflowBuffer.isEmpty()) {
                    ready.add(overflowBuffer);
                    overflowBuffer = "";
                }
            } else if (finalTail.length() >= minParagraphLength) {
                if (!accumulatedShortParagraphs.isEmpty()) {
                    ready.add(accumulatedShortParagraphs);
                    accumulatedShortParagraphs = "";
                }
                ready.add(finalTail);
            } else {
                accumulatedShortParagraphs = accumulatedShortParagraphs.isEmpty()
                        ? finalTail
                        : accumulatedShortParagraphs + "\n\n" + finalTail;
            }
        }

        String leftover = accumulatedShortParagraphs.trim();
        if (!leftover.isEmpty()) {
            ready.add(leftover);
            accumulatedShortParagraphs = "";
        }
        return ready;
    }

    private List<String> splitChunkIntoParagraphs(String chunk) {
        String text = tail + chunk;
        String[] paragraphs = text.split("\n\n", -1);
        if (text.endsWith("\n\n")) {
            tail = "";
            return List.of(paragraphs);
        }
        String incomplete = paragraphs[paragraphs.length - 1];
        List<String> complete = new ArrayList<>();
        for (int i = 0; i < paragraphs.length - 1; i++) {
            complete.add(paragraphs[i]);
        }
        if (maxMessageLength > 0 && incomplete.length() >= maxMessageLength) {
            int boundary = findNextWordBoundary(incomplete, maxMessageLength - 1);
            if (boundary <= incomplete.length()) {
                complete.add(incomplete.substring(0, boundary));
                tail = incomplete.substring(boundary);
                return complete;
            }
        }
        tail = incomplete;
        return complete;
    }

    private String groupByMinLength(String trimmed) {
        if (trimmed.length() < minParagraphLength) {
            accumulatedShortParagraphs = accumulatedShortParagraphs.isEmpty()
                    ? trimmed
                    : accumulatedShortParagraphs + "\n\n" + trimmed;
            if (accumulatedShortParagraphs.length() >= minParagraphLength) {
                String ready = accumulatedShortParagraphs;
                accumulatedShortParagraphs = "";
                return ready;
            }
            return null;
        }
        if (!accumulatedShortParagraphs.isEmpty()) {
            String ready = accumulatedShortParagraphs + "\n\n" + trimmed;
            accumulatedShortParagraphs = "";
            return ready;
        }
        return trimmed;
    }

    private void splitByMaxLength(String block, List<String> out) {
        String merged = overflowBuffer.isEmpty() ? block : overflowBuffer + "\n\n" + block;
        overflowBuffer = "";
        while (merged.length() > maxMessageLength) {
            int boundary = findNextWordBoundary(merged, maxMessageLength - 1);
            if (boundary >= merged.length()) {
                break;
            }
            out.add(merged.substring(0, boundary).trim());
            merged = merged.substring(boundary).trim();
        }
        if (!merged.isEmpty()) {
            if (merged.length() >= minParagraphLength) {
                out.add(merged);
            } else {
                overflowBuffer = merged;
            }
        }
    }

    private static int findNextWordBoundary(String text, int fromIndex) {
        int n = text.length();
        int i = Math.min(fromIndex, n);
        while (i < n && !Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        while (i < n && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        return i;
    }
}
