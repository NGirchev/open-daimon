package io.github.ngirchev.opendaimon.ai.springai.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ngirchev.opendaimon.common.agent.AgentToolResult;

/**
 * Classifies a completed tool invocation into a triple {@code (streamContent, observation, toolError)}
 * that drives both the {@code OBSERVATION} stream event and the step-history record.
 *
 * <p>Spring AI's {@code @Tool} contract is string-typed: a tool method returns a {@code String}
 * regardless of whether the call succeeded. The built-in {@link io.github.ngirchev.opendaimon.ai.springai.tool.HttpApiTool}
 * and {@link io.github.ngirchev.opendaimon.ai.springai.tool.WebTools} both catch HTTP failures
 * internally and surface them as {@code "HTTP error …"} / {@code "Error: …"} strings, so the
 * framework still reports {@code success = true}. Without this classifier the Telegram UI would
 * render such failures as a triumphant "📋 Tool result received" instead of the expected
 * "⚠️ Tool failed: …" marker.
 *
 * <p>Responsibilities kept <b>public static</b> so {@code SpringAgentLoopActions} can also
 * re-use {@link #normalizeStringToolResult} / {@link #isTextualToolFailure} from the
 * {@code fetch_url} guard bookkeeping.
 */
public final class ToolObservationClassifier {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final int ERROR_SUMMARY_MAX_LEN = 200;
    private static final String MISSING_WEB_SEARCH_QUERY_PREFIX =
            "Error: argument 'query' is required and must not be blank.";
    private static final String MISSING_WEB_SEARCH_QUERY_STREAM_CONTENT =
            "Search query is missing.";

    /**
     * Output triple:
     * <ul>
     *   <li>{@code streamContent} — UI-facing text for the OBSERVATION stream event
     *       (may be null if no result at all, otherwise a cleaned/shortened line).</li>
     *   <li>{@code observation} — full observation text preserved in the step history
     *       for the model to reason over in subsequent iterations.</li>
     *   <li>{@code toolError} — UI-facing flag that toggles the ⚠️ renderer.</li>
     * </ul>
     */
    public record Classification(String streamContent, String observation, boolean toolError) {}

    private ToolObservationClassifier() {
        throw new AssertionError("static utility, do not instantiate");
    }

    /**
     * Inspects {@code toolResult} and returns the renderer-ready classification.
     * Null-safe — a {@code null} result yields {@code (null, "No result", false)}.
     */
    public static Classification classify(AgentToolResult toolResult) {
        if (toolResult == null) {
            return new Classification(null, "No result", false);
        }
        if (!toolResult.success()) {
            return new Classification(toolResult.error(), "Error: " + toolResult.error(), true);
        }
        String raw = toolResult.result();
        String observation = raw;
        if (raw == null) {
            return new Classification(null, null, false);
        }
        String trimmed = normalizeStringToolResult(raw);
        if (isTextualToolFailure(trimmed)) {
            if (isMissingWebSearchQuery(toolResult.toolName(), trimmed)) {
                return new Classification(MISSING_WEB_SEARCH_QUERY_STREAM_CONTENT, observation, true);
            }
            return new Classification(summarizeToolError(trimmed), observation, true);
        }
        return new Classification(trimmed, observation, false);
    }

    /**
     * Heuristic: true when the tool returned a non-exceptional but textually-marked
     * failure. Three prefixes are recognised, each originating from a distinct source:
     * <ul>
     *   <li>{@code "HTTP error "} — produced by {@link io.github.ngirchev.opendaimon.ai.springai.tool.WebTools}
     *       {@code handleWebClientResponseException} when a downstream HTTP call
     *       returns a non-2xx status (e.g. {@code "HTTP error 403 FORBIDDEN: …"}).</li>
     *   <li>{@code "Error: "} — produced by {@code WebTools.fetchUrl} for structured
     *       REASON_* codes (invalid URL, timeout, too large, unreadable 2xx) as well
     *       as any generic exception message surfaced as a tool result.</li>
     *   <li>{@code "Exception occurred in tool:"} — produced by Spring AI's
     *       {@code DefaultToolCallResultConverter} when a {@code @Tool} method throws
     *       an unhandled exception: the framework catches it above our try/catch and
     *       substitutes this canonical string as a "successful" tool result. Without
     *       recognising it the Telegram UI would render it as {@code 📋 Tool result received}.</li>
     * </ul>
     *
     * <p>Exposed publicly so the {@code fetch_url} short-circuit guard can apply the same
     * rule for counting host failures — keeping one definition avoids drift between UI
     * classification and retry-throttling heuristics.
     */
    public static boolean isTextualToolFailure(String text) {
        return text != null && (
                text.startsWith("HTTP error ")
                || text.startsWith("Error: ")
                || text.startsWith("Exception occurred in tool:")
        );
    }

    /**
     * Spring AI serialises {@code String} tool return values as JSON-quoted strings
     * (e.g. {@code "HTTP error 200 OK"} → {@code "\"HTTP error 200 OK\""}). Unwrap the
     * outer quotes — falling back to naive substring if Jackson can't parse — so the
     * textual-failure prefix check works regardless of whether the upstream serializer
     * added them.
     */
    public static String normalizeStringToolResult(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length() >= 2) {
            try {
                return OBJECT_MAPPER.readValue(trimmed, String.class).trim();
            } catch (Exception ignored) {
                return trimmed.substring(1, trimmed.length() - 1).trim();
            }
        }
        return trimmed;
    }

    private static boolean isMissingWebSearchQuery(String toolName, String text) {
        return "web_search".equals(toolName)
                && text != null
                && text.startsWith(MISSING_WEB_SEARCH_QUERY_PREFIX);
    }

    /**
     * Extracts a short, UI-friendly error line from a textual tool failure like
     * {@code "HTTP error 403 FORBIDDEN: <html …>"} or {@code "Error: connection refused"}.
     * Keeps only the head of the first line so the Telegram {@code ⚠️ Tool failed: …}
     * marker stays compact (large CloudFlare challenge pages are ~7 kB otherwise).
     */
    private static String summarizeToolError(String raw) {
        int newline = raw.indexOf('\n');
        String firstLine = newline >= 0 ? raw.substring(0, newline) : raw;
        if (firstLine.length() > ERROR_SUMMARY_MAX_LEN) {
            return firstLine.substring(0, ERROR_SUMMARY_MAX_LEN) + "…";
        }
        return firstLine;
    }
}
