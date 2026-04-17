package io.github.ngirchev.opendaimon.ai.springai.agent;

/**
 * Stream-time filter that strips {@code <think>...</think>} and {@code <tool_call>...</tool_call>}
 * blocks from a token stream while preserving everything else.
 *
 * <p>Designed for chunked input: tags split across {@link #feed(String)} calls (e.g. {@code "<th"}
 * + {@code "ink>"}) are correctly handled. The filter holds back a small tail of recently fed
 * characters until it can prove the tail is not the start of a tag opening or closing.
 *
 * <p>Behavior contract:
 * <ul>
 *     <li>{@link #feed(String)} returns the portion of buffered output safe to emit so far.</li>
 *     <li>{@link #flush()} returns the trailing buffer; if the stream ended inside a block,
 *     the unfinished block content is dropped.</li>
 * </ul>
 */
final class StreamingAnswerFilter {

    private static final String THINK_OPEN = "<think>";
    private static final String THINK_CLOSE = "</think>";
    private static final String TOOL_OPEN = "<tool_call>";
    private static final String TOOL_CLOSE = "</tool_call>";

    private static final int MAX_TAG_LEN = TOOL_CLOSE.length();

    private enum State { OUTSIDE, INSIDE_THINK, INSIDE_TOOL_CALL }

    private final StringBuilder buffer = new StringBuilder();
    private State state = State.OUTSIDE;

    String feed(String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return "";
        }
        buffer.append(chunk);
        StringBuilder out = new StringBuilder();
        process(out, false);
        return out.toString();
    }

    String flush() {
        StringBuilder out = new StringBuilder();
        process(out, true);
        return out.toString();
    }

    private void process(StringBuilder out, boolean atEnd) {
        while (true) {
            if (state == State.OUTSIDE) {
                if (!consumeOutside(out, atEnd)) {
                    return;
                }
            } else {
                String closeTag = state == State.INSIDE_THINK ? THINK_CLOSE : TOOL_CLOSE;
                if (!consumeInside(closeTag, atEnd)) {
                    return;
                }
            }
        }
    }

    /**
     * Emits text from the buffer up to the next opening tag.
     *
     * @return true if a transition happened (loop should continue), false if the buffer was
     * fully drained for the current state.
     */
    private boolean consumeOutside(StringBuilder out, boolean atEnd) {
        int idxThink = buffer.indexOf(THINK_OPEN);
        int idxTool = buffer.indexOf(TOOL_OPEN);
        int idxOpen = minNonNegative(idxThink, idxTool);

        if (idxOpen >= 0) {
            out.append(buffer, 0, idxOpen);
            boolean isThink = idxOpen == idxThink;
            int tagLen = isThink ? THINK_OPEN.length() : TOOL_OPEN.length();
            buffer.delete(0, idxOpen + tagLen);
            state = isThink ? State.INSIDE_THINK : State.INSIDE_TOOL_CALL;
            return true;
        }

        if (atEnd) {
            out.append(buffer);
            buffer.setLength(0);
            return false;
        }

        int safe = buffer.length() - (MAX_TAG_LEN - 1);
        if (safe > 0) {
            int safeNoLt = lastIndexOfLtBefore(safe);
            int emitUpTo = safeNoLt >= 0 ? safeNoLt : safe;
            if (emitUpTo > 0) {
                out.append(buffer, 0, emitUpTo);
                buffer.delete(0, emitUpTo);
            }
        }
        return false;
    }

    /**
     * Skips buffered content until the matching close tag.
     *
     * @return true if the close tag was found and consumed (loop should continue).
     */
    private boolean consumeInside(String closeTag, boolean atEnd) {
        int idxClose = buffer.indexOf(closeTag);
        if (idxClose >= 0) {
            buffer.delete(0, idxClose + closeTag.length());
            state = State.OUTSIDE;
            return true;
        }

        if (atEnd) {
            buffer.setLength(0);
            return false;
        }

        int retain = closeTag.length() - 1;
        int drop = buffer.length() - retain;
        if (drop > 0) {
            buffer.delete(0, drop);
        }
        return false;
    }

    /**
     * Finds the largest position {@code <= bound} that is not the start of a {@code '<'} char.
     * If the only positions {@code >= bound} contain {@code '<'}, returns the index of the first
     * such {@code '<'} so the caller can hold it back.
     */
    private int lastIndexOfLtBefore(int bound) {
        for (int i = bound - 1; i >= 0; i--) {
            if (buffer.charAt(i) == '<') {
                return i;
            }
        }
        return -1;
    }

    private static int minNonNegative(int a, int b) {
        if (a < 0) return b;
        if (b < 0) return a;
        return Math.min(a, b);
    }
}
