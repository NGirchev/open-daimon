package io.github.ngirchev.opendaimon.telegram.service;

import java.util.Map;

/**
 * Per-tool friendly label mapping for the status transcript.
 *
 * <p>Given the raw agent tool name (e.g. {@code web_search}), returns a user-facing
 * English label (e.g. {@code Searching the web}) that is rendered into the
 * {@code 🔧 Tool: <label>} line of the status message. Unknown tools fall back to
 * a generic label.
 */
public final class ToolLabels {

    public static final String DEFAULT_LABEL = "Using a tool";

    /** Max length of the rendered tool argument (characters) before ellipsis. */
    public static final int TOOL_ARG_MAX_LENGTH = 200;

    private static final Map<String, String> LABELS = Map.of(
            "web_search", "Searching the web",
            "fetch_url", "Reading a web page",
            "http_get", "Making an HTTP request",
            "http_post", "Sending an HTTP request"
    );

    private ToolLabels() {}

    public static String label(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return DEFAULT_LABEL;
        }
        return LABELS.getOrDefault(toolName, DEFAULT_LABEL);
    }

    public static String truncateArg(String arg) {
        if (arg == null || arg.length() <= TOOL_ARG_MAX_LENGTH) {
            return arg;
        }
        return arg.substring(0, TOOL_ARG_MAX_LENGTH) + "…";
    }
}
