package io.github.ngirchev.opendaimon.ai.springai.agent;

import java.util.List;

/**
 * Stream-time filter that strips LLM-internal tool-call and reasoning markup from a
 * token stream while preserving everything else.
 *
 * <p>Covers the following tag variants — both the canonical Qwen {@code <tool_call>}
 * wrapper and the loose fallback that some Ollama-hosted models emit directly:
 * <ul>
 *   <li>{@code <think>...</think>}</li>
 *   <li>{@code <tool_call>...</tool_call>}</li>
 *   <li>{@code <tool_name>...</tool_name>}</li>
 *   <li>{@code <arg_key>...</arg_key>}, {@code <arg_value>...</arg_value>}</li>
 *   <li>{@code <name>...</name>} — context-gated, see below</li>
 * </ul>
 *
 * <p><b>{@code <name>} handling:</b> because {@code <name>} is a legitimate XML
 * token a user may ask the model to produce (e.g. "show me an XML example with a
 * {@code <name>} tag"), stripping it unconditionally corrupts user-visible
 * output. Instead this filter mirrors the batch sanitizer in
 * {@link AgentTextSanitizer#stripToolCallTags(String)}: {@code <name>} is treated
 * as tool-call markup only after the stream has already emitted an unambiguous
 * loose tool-call anchor ({@code <tool_call>}, {@code <tool_name>},
 * {@code <arg_key>}, or {@code <arg_value>}). Before any anchor has been seen,
 * {@code <name>} passes through as ordinary content.
 *
 * <p>Designed for chunked input: tags split across {@link #feed(String)} calls
 * (e.g. {@code "<th"} + {@code "ink>"}) are correctly handled. The filter holds
 * back a small tail of recently fed characters until it can prove the tail is not
 * the start of a tag opening or closing.
 *
 * <p>Behavior contract:
 * <ul>
 *     <li>{@link #feed(String)} returns the portion of buffered output safe to emit so far.</li>
 *     <li>{@link #flush()} returns the trailing buffer; if the stream ended inside a block,
 *     the unfinished block content is dropped.</li>
 * </ul>
 */
final class StreamingAnswerFilter {

    /**
     * Immutable pairing of opening tag + matching close tag, ordered from longest-open
     * to shortest-open so the dispatcher matches greedily (e.g. {@code <tool_name>}
     * is tried before {@code <tool>} would be, avoiding prefix confusion).
     */
    private record TagPair(String open, String close) {}

    /** Tags stripped unconditionally whenever encountered in the stream. */
    private static final List<TagPair> BASE_TAG_PAIRS = List.of(
            new TagPair("<tool_call>", "</tool_call>"),
            new TagPair("<tool_name>", "</tool_name>"),
            new TagPair("<arg_value>", "</arg_value>"),
            new TagPair("<arg_key>", "</arg_key>"),
            new TagPair("<think>", "</think>")
    );

    /** Ambiguous pair — only stripped once a loose tool-call anchor has been observed. */
    private static final TagPair NAME_TAG_PAIR = new TagPair("<name>", "</name>");

    private static final List<TagPair> EXTENDED_TAG_PAIRS;
    static {
        var ext = new java.util.ArrayList<TagPair>(BASE_TAG_PAIRS.size() + 1);
        ext.addAll(BASE_TAG_PAIRS);
        ext.add(NAME_TAG_PAIR);
        EXTENDED_TAG_PAIRS = List.copyOf(ext);
    }

    private static final int MAX_TAG_LEN;
    static {
        int max = Math.max(NAME_TAG_PAIR.open().length(), NAME_TAG_PAIR.close().length());
        for (TagPair p : BASE_TAG_PAIRS) {
            max = Math.max(max, Math.max(p.open().length(), p.close().length()));
        }
        MAX_TAG_LEN = max;
    }

    private final StringBuilder buffer = new StringBuilder();
    /** Non-null when currently inside a suppressed block; holds the expected close tag. */
    private String activeCloseTag;
    /**
     * Flips to {@code true} the first time the stream yields an unambiguous loose
     * tool-call marker (see {@link #isLooseAnchor}). Enables {@code <name>}
     * stripping for the remainder of the stream.
     */
    private boolean looseToolCallAnchorSeen;

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
            if (activeCloseTag == null) {
                if (!consumeOutside(out, atEnd)) {
                    return;
                }
            } else {
                if (!consumeInside(activeCloseTag, atEnd)) {
                    return;
                }
            }
        }
    }

    /**
     * Emits text from the buffer up to the next opening tag, stripping orphan close
     * tags that appear while outside any block.
     *
     * <p>Some models occasionally emit a closing tag without a matching opening one (e.g. when
     * the model quotes tool-call markup inside reasoning prose but the opening tag was never
     * streamed). Treating such orphans as plain text leaks raw XML into the user-facing answer,
     * so we drop them while preserving the surrounding text.
     *
     * @return true if a transition happened (loop should continue), false if the buffer was
     * fully drained for the current state.
     */
    private boolean consumeOutside(StringBuilder out, boolean atEnd) {
        int earliestIdx = -1;
        TagPair earliestOpen = null;
        TagPair earliestOrphanClosePair = null;

        for (TagPair p : activeTagPairs()) {
            int idxOpen = buffer.indexOf(p.open());
            if (idxOpen >= 0 && (earliestIdx < 0 || idxOpen < earliestIdx)) {
                earliestIdx = idxOpen;
                earliestOpen = p;
                earliestOrphanClosePair = null;
            }
            int idxClose = buffer.indexOf(p.close());
            if (idxClose >= 0 && (earliestIdx < 0 || idxClose < earliestIdx)) {
                earliestIdx = idxClose;
                earliestOpen = null;
                earliestOrphanClosePair = p;
            }
        }

        if (earliestIdx >= 0) {
            out.append(buffer, 0, earliestIdx);
            if (earliestOpen != null) {
                if (isLooseAnchor(earliestOpen)) {
                    looseToolCallAnchorSeen = true;
                }
                buffer.delete(0, earliestIdx + earliestOpen.open().length());
                activeCloseTag = earliestOpen.close();
            } else {
                // Orphan close tag — strip without emitting, remain OUTSIDE.
                // Orphan </tool_call>/</arg_*>/</tool_name> also enables <name> stripping:
                // the matching open was lost (split chunk, upstream truncation) but the
                // closer alone still proves the model entered loose tool-call mode.
                if (isLooseAnchor(earliestOrphanClosePair)) {
                    looseToolCallAnchorSeen = true;
                }
                buffer.delete(0, earliestIdx + earliestOrphanClosePair.close().length());
            }
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
            activeCloseTag = null;
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

    /**
     * Returns the tag-pair list the filter is currently matching against. Starts as
     * {@link #BASE_TAG_PAIRS} and upgrades to {@link #EXTENDED_TAG_PAIRS} (which also
     * includes {@code <name>...</name>}) once a loose tool-call anchor has been seen.
     */
    private List<TagPair> activeTagPairs() {
        return looseToolCallAnchorSeen ? EXTENDED_TAG_PAIRS : BASE_TAG_PAIRS;
    }

    /**
     * True for tag pairs whose mere presence in the stream unambiguously indicates
     * loose tool-call markup: {@code <tool_call>}, {@code <tool_name>},
     * {@code <arg_key>}, {@code <arg_value>}. {@code <think>} and {@code <name>} are
     * deliberately excluded — {@code <think>} is reasoning (not tool-call) and
     * {@code <name>} is the ambiguous token whose stripping this method gates.
     */
    private static boolean isLooseAnchor(TagPair pair) {
        return pair != null && switch (pair.open()) {
            case "<tool_call>", "<tool_name>", "<arg_key>", "<arg_value>" -> true;
            default -> false;
        };
    }
}
