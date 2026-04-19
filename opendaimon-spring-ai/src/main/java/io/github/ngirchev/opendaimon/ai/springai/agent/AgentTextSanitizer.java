package io.github.ngirchev.opendaimon.ai.springai.agent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatResponse;

import java.util.regex.Pattern;

/**
 * Static utilities that clean up LLM output before it reaches downstream
 * consumers (user-visible answer, chat memory, summary prompt).
 *
 * <p>Split out of {@code SpringAgentLoopActions} so the same cleanup logic
 * can be shared with {@code SimpleChainExecutor} without coupling either of
 * them to FSM-specific state, and so the stripping rules can be tested in
 * isolation from the agent loop.
 *
 * <p>Two related families of markup are handled:
 * <ul>
 *   <li>{@code <think>…</think>} reasoning blocks — extracted for the
 *       reasoning stream, then removed from the answer text.</li>
 *   <li>{@code <tool_call>…</tool_call>} and its loose fallback inner tags
 *       ({@code <name>}, {@code <arg_key>}, {@code <arg_value>}, bare tool
 *       names on their own line) — stripped unconditionally because they are
 *       LLM implementation details, not user content.</li>
 * </ul>
 *
 * <p>The streaming analogue that performs the same filtering on a chunked
 * token stream lives in {@link StreamingAnswerFilter}. This class owns only
 * the batch (post-aggregation) path plus two streaming helpers
 * ({@link #normalizeDelta}, {@link #appendDelta}) used by the aggregation
 * buffer in the agent loop.
 */
@Slf4j
final class AgentTextSanitizer {

    /** Matches complete {@code <tool_call>...</tool_call>} blocks including content. */
    private static final Pattern TOOL_CALL_BLOCK_PATTERN =
            Pattern.compile("<tool_call>.*?</tool_call>", Pattern.DOTALL);

    /** Matches orphaned {@code <tool_call>} tag without closing — consumes to end of string. */
    private static final Pattern TOOL_CALL_OPEN_PATTERN =
            Pattern.compile("<tool_call>.*", Pattern.DOTALL);

    /** Matches orphaned {@code </tool_call>} closing tag. */
    private static final Pattern TOOL_CALL_CLOSE_PATTERN =
            Pattern.compile("</tool_call>");

    /** Matches loose inner tags: {@code <name>}, {@code <arg_key>}, {@code <arg_value>} with content. */
    private static final Pattern TOOL_CALL_INNER_TAGS_PATTERN =
            Pattern.compile("<(name|arg_key|arg_value)>.*?</\\1>", Pattern.DOTALL);

    /** Matches unclosed inner tags: e.g. {@code <arg_value>content} without a closing tag. */
    private static final Pattern TOOL_CALL_UNCLOSED_INNER_TAG_PATTERN =
            Pattern.compile("<(name|arg_key|arg_value)>[^\n]*");

    /** Matches a bare tool-like name on its own line (e.g. {@code http_get}, {@code web_search}). */
    private static final Pattern BARE_TOOL_NAME_PATTERN =
            Pattern.compile("(?m)^\\s*\\w+_\\w+\\s*$");

    private AgentTextSanitizer() {
        throw new AssertionError("static utility, do not instantiate");
    }

    /**
     * Attempts to extract reasoning/thinking content from the LLM response.
     *
     * <p>Two sources are checked:
     * <ol>
     *   <li>Generation metadata key "thinking" (Spring AI Ollama 1.1+ with think=true)</li>
     *   <li>Generation metadata key "reasoningContent" (OpenRouter/Anthropic)</li>
     *   <li>{@code <think>...</think>} tags in text output (older Ollama or custom models)</li>
     * </ol>
     *
     * @return reasoning text, or null if not available
     */
    static String extractReasoning(ChatResponse response) {
        try {
            if (response == null) {
                return null;
            } else {
                response.getResult();
            }
            var metadata = response.getResult().getMetadata();
            Object thinking = metadata.get("thinking");
            if (thinking instanceof String text && !text.isBlank()) {
                log.info("AgentTextSanitizer.extractReasoning: found 'thinking' metadata, length={}", text.length());
                return text;
            }
            Object reasoning = metadata.get("reasoningContent");
            if (reasoning instanceof String text && !text.isBlank()) {
                log.info("AgentTextSanitizer.extractReasoning: found 'reasoningContent' metadata, length={}", text.length());
                return text;
            }
            var output = response.getResult().getOutput();
            if (output != null && output.getText() != null) {
                String rawText = output.getText();
                if (rawText.contains("<think>")) {
                    log.info("AgentTextSanitizer.extractReasoning: found <think> tags, textLength={}", rawText.length());
                    return extractThinkTags(rawText);
                }
            }
        } catch (Exception e) {
            log.debug("AgentTextSanitizer.extractReasoning: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Extracts content from {@code <think>...</think>} tags (Ollama thinking mode).
     * Returns the thinking text, or null if no tags found.
     */
    static String extractThinkTags(String text) {
        if (text == null) {
            return null;
        }
        int start = text.indexOf("<think>");
        int end = text.indexOf("</think>");
        if (start < 0 || end < 0 || end <= start) {
            return null;
        }
        String thinking = text.substring(start + "<think>".length(), end).trim();
        return thinking.isEmpty() ? null : thinking;
    }

    /**
     * Strips {@code <think>...</think>} block from text, returning only the answer part.
     *
     * <p>Handles three malformed cases observed from real models:
     * <ul>
     *   <li>Matched pair: removes the block, keeps surrounding text.</li>
     *   <li>Open without close: drops from {@code <think>} to end — reasoning was never closed.</li>
     *   <li>Close without open: drops from start of text up to and including {@code </think>}.
     *       The open tag was lost (stream corruption, upstream sanitizer, or partial tag emit);
     *       text ahead of the orphan close is reasoning that must not leak to the user.</li>
     * </ul>
     *
     * <p>Diverges from {@link StreamingAnswerFilter} on the orphan-close case: the
     * streaming path may have already emitted the reasoning prefix to the user in
     * earlier chunks and can only strip the tag itself, whereas this method owns
     * the full response and safely drops the entire prefix.
     */
    static String stripThinkTags(String text) {
        if (text == null) {
            return null;
        }
        int start = text.indexOf("<think>");
        int end = text.indexOf("</think>");
        if (start < 0 && end < 0) {
            return text;
        }
        if (start < 0) {
            return text.substring(end + "</think>".length()).trim();
        }
        if (end < 0 || end <= start) {
            return text.substring(0, start).trim();
        }
        return (text.substring(0, start) + text.substring(end + "</think>".length())).trim();
    }

    /**
     * Strips raw XML tool call markup that some models emit in text responses
     * instead of using the structured function calling API.
     *
     * <p>Removes:
     * <ul>
     *   <li>{@code <tool_call>...</tool_call>} blocks (including partial/unclosed)</li>
     *   <li>Orphaned {@code </tool_call>} closing tags</li>
     *   <li>Closed inner tags: {@code <name>x</name>}, {@code <arg_key>x</arg_key>}, etc.</li>
     *   <li>Unclosed inner tags: {@code <arg_value>content} without closing tag</li>
     *   <li>Bare tool-like names on their own line (e.g. {@code http_get})</li>
     * </ul>
     */
    static String stripToolCallTags(String text) {
        if (text == null) {
            return null;
        }
        String result = TOOL_CALL_BLOCK_PATTERN.matcher(text).replaceAll("");
        result = TOOL_CALL_OPEN_PATTERN.matcher(result).replaceAll("");
        result = TOOL_CALL_CLOSE_PATTERN.matcher(result).replaceAll("");
        if (result.contains("<arg_key>") || result.contains("<arg_value>")) {
            result = TOOL_CALL_INNER_TAGS_PATTERN.matcher(result).replaceAll("");
            result = TOOL_CALL_UNCLOSED_INNER_TAG_PATTERN.matcher(result).replaceAll("");
            result = BARE_TOOL_NAME_PATTERN.matcher(result).replaceAll("");
        }
        return result.trim().isEmpty() ? "" : result.trim();
    }

    /**
     * Returns the delta to append to {@code accumulated} when a streaming chunk arrives.
     * Some providers (Ollama) send cumulative snapshots rather than true deltas: each
     * chunk repeats all previous content plus the new suffix. When the new chunk starts
     * with the entire accumulated text, only the suffix beyond it is the new content.
     */
    static String normalizeDelta(String accumulated, String chunk) {
        if (!accumulated.isEmpty() && chunk.startsWith(accumulated)) {
            return chunk.substring(accumulated.length());
        }
        return chunk;
    }

    /**
     * Allocation-free variant of {@link #normalizeDelta} used on the hot streaming
     * path. Compares the {@code chunk} prefix against {@code accumulated} in place
     * and appends only the genuinely new suffix, avoiding the O(N) {@code toString}
     * per chunk that would otherwise make a long answer O(N²) in total.
     */
    static void appendDelta(StringBuilder accumulated, String chunk) {
        int n = accumulated.length();
        if (n > 0 && chunk.length() >= n && startsWith(chunk, accumulated)) {
            accumulated.append(chunk, n, chunk.length());
        } else {
            accumulated.append(chunk);
        }
    }

    /**
     * Appends the genuinely new portion of {@code chunk} to {@code accumulator} and
     * returns that new portion as a {@code String}. Mirrors {@link #appendDelta}'s
     * snapshot-vs-delta detection (shared via {@link #startsWith}): if {@code chunk}
     * begins with the accumulator it is treated as a cumulative snapshot and only
     * the suffix beyond the accumulator is returned and appended. Otherwise the
     * chunk is treated as a plain delta and returned unchanged.
     *
     * <p>Used on the streaming pipeline to normalize provider-specific stream shapes
     * (snapshot vs true-delta) into monotonic deltas before they reach downstream
     * stateful consumers (e.g. {@link StreamingAnswerFilter}).
     */
    static String computeDelta(StringBuilder accumulator, String chunk) {
        int n = accumulator.length();
        if (n > 0 && chunk.length() >= n && startsWith(chunk, accumulator)) {
            String delta = chunk.substring(n);
            accumulator.append(delta);
            return delta;
        }
        accumulator.append(chunk);
        return chunk;
    }

    private static boolean startsWith(String chunk, StringBuilder prefix) {
        int n = prefix.length();
        for (int i = 0; i < n; i++) {
            if (chunk.charAt(i) != prefix.charAt(i)) {
                return false;
            }
        }
        return true;
    }
}
