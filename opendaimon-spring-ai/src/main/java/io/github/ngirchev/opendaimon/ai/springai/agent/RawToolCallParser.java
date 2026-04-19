package io.github.ngirchev.opendaimon.ai.springai.agent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses tool-call XML markup that some LLM providers (notably Ollama-hosted
 * Qwen variants and a few OpenRouter models) emit as plain text instead of using
 * the structured function-calling API.
 *
 * <p>Recognised shapes:
 * <pre>
 *   &lt;tool_call&gt;
 *     &lt;name&gt;http_get&lt;/name&gt;
 *     &lt;arg_key&gt;url&lt;/arg_key&gt;
 *     &lt;arg_value&gt;https://example.com&lt;/arg_value&gt;
 *   &lt;/tool_call&gt;
 * </pre>
 * plus the {@code <tool_name>…</tool_name>} Qwen variant and a loose fallback
 * with just a bare tool name on its own line immediately before the first
 * {@code <arg_key>}.
 *
 * <p>Strictness is deliberate — the parser refuses to fabricate a tool name from
 * prose ("use http_get for this" must not trigger a spurious call); it accepts
 * only explicit {@code <name>}/{@code <tool_name>} tags, or a bare-name pattern
 * in the narrow 200-character window right before {@code <arg_key>}.
 *
 * <p>Registered tool-callback list is captured once at construction so the
 * parser is safe to reuse across agent runs (it never mutates its state).
 */
@Slf4j
final class RawToolCallParser {

    /** Matches {@code <name>toolName</name>} inside raw tool call markup. */
    private static final Pattern NAME_TAG_PATTERN =
            Pattern.compile("<name>(\\w+)</name>");

    /**
     * Matches {@code <tool_name>toolName</tool_name>} — the Ollama/Qwen variant. Kept as a
     * separate pattern (not combined with {@link #NAME_TAG_PATTERN}) because some models
     * emit both in the same payload and we want a deterministic priority: {@code <name>}
     * wins, {@code <tool_name>} is the fallback.
     */
    private static final Pattern TOOL_NAME_TAG_PATTERN =
            Pattern.compile("<tool_name>(\\w+)</tool_name>");

    /** Matches {@code <arg_key>key</arg_key>...<arg_value>value</arg_value>} pairs. */
    private static final Pattern ARG_PAIR_PATTERN =
            Pattern.compile("<arg_key>(.*?)</arg_key>\\s*<arg_value>(.*?)</arg_value>", Pattern.DOTALL);

    /** Parsed raw tool call from text output (fallback for models without structured function calling). */
    record RawToolCall(String name, String arguments) {}

    private final List<ToolCallback> toolCallbacks;

    RawToolCallParser(List<ToolCallback> toolCallbacks) {
        this.toolCallbacks = toolCallbacks != null ? List.copyOf(toolCallbacks) : List.of();
    }

    /**
     * Attempts to parse a tool call from raw XML tags in the text output.
     *
     * <p>Requirements for a valid parse:
     * <ul>
     *   <li>At least one {@code <arg_key>/<arg_value>} pair must be present.</li>
     *   <li>Tool name resolved in this order:
     *       <ol>
     *         <li>{@code <name>…</name>} tag, or</li>
     *         <li>{@code <tool_name>…</tool_name>} tag, or</li>
     *         <li>bare registered tool name on its own line inside a
     *             <b>pre-arg prefix window</b> — the up-to-200-character slice of text
     *             immediately before the first {@code <arg_key>}. Substring-match
     *             against the full text is <b>not</b> accepted — otherwise prose
     *             like "use http_get for this" unrelated to a later set of arg tags
     *             would trigger a spurious tool call.</li>
     *       </ol>
     *   </li>
     *   <li>Tool name must correspond to a registered tool callback.</li>
     * </ul>
     *
     * @param text raw text output from the LLM (after think-tag stripping)
     * @return parsed tool call, or null if no valid tool call pattern found
     */
    RawToolCall tryParseRawToolCall(String text) {
        if (text == null) {
            return null;
        }

        Matcher firstArgCheck = ARG_PAIR_PATTERN.matcher(text);
        if (!firstArgCheck.find()) {
            return null;
        }
        int firstArgStart = firstArgCheck.start();

        String toolName = null;
        Matcher nameMatcher = NAME_TAG_PATTERN.matcher(text);
        if (nameMatcher.find()) {
            toolName = nameMatcher.group(1).trim();
        }

        if (toolName == null) {
            Matcher toolNameMatcher = TOOL_NAME_TAG_PATTERN.matcher(text);
            if (toolNameMatcher.find()) {
                toolName = toolNameMatcher.group(1).trim();
            }
        }

        if (toolName == null) {
            toolName = findBareToolNameBeforeFirstArg(text, firstArgStart);
        }

        if (toolName == null) {
            log.debug("RawToolCallParser: markup found but no <name>/<tool_name> tag "
                    + "and no bare registered tool name in the pre-arg prefix window — skipping");
            return null;
        }

        String resolvedName = toolName;
        boolean registered = toolCallbacks.stream()
                .anyMatch(cb -> cb.getToolDefinition().name().equals(resolvedName));
        if (!registered) {
            return null;
        }

        Matcher argMatcher = ARG_PAIR_PATTERN.matcher(text);
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        while (argMatcher.find()) {
            if (!first) {
                json.append(",");
            }
            json.append("\"").append(escapeJson(argMatcher.group(1).trim())).append("\":");
            json.append("\"").append(escapeJson(argMatcher.group(2).trim())).append("\"");
            first = false;
        }
        json.append("}");

        log.info("RawToolCallParser: parsed raw tool call — tool={}, args={}", toolName, json);
        return new RawToolCall(toolName, json.toString());
    }

    /**
     * Looks for a registered tool name written on its own line inside the
     * up-to-200-character slice of text immediately preceding the first
     * {@code <arg_key>}. This is the narrowest possible interpretation of the
     * Ollama/Qwen fallback format {@code "<tool>\n<arg_key>…</arg_key>"} —
     * narrow enough that a mention of a tool name in unrelated prose earlier
     * in the response cannot trigger a spurious tool call.
     */
    private String findBareToolNameBeforeFirstArg(String text, int firstArgStart) {
        int windowStart = Math.max(0, firstArgStart - 200);
        String prefix = text.substring(windowStart, firstArgStart);
        for (ToolCallback cb : toolCallbacks) {
            String name = cb.getToolDefinition().name();
            Pattern bareNamePattern = Pattern.compile(
                    "(?m)^\\s*" + Pattern.quote(name) + "\\s*$");
            if (bareNamePattern.matcher(prefix).find()) {
                return name;
            }
        }
        return null;
    }

    /**
     * Escapes special characters for JSON string values.
     */
    static String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
