package io.github.ngirchev.opendaimon.telegram.service;

import io.github.ngirchev.opendaimon.common.agent.AgentStreamEvent;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts {@link AgentStreamEvent} into Telegram-compatible HTML strings.
 *
 * <p>Returns {@code null} for final-answer events (FINAL_ANSWER_CHUNK, FINAL_ANSWER,
 * MAX_ITERATIONS) and metadata that are handled separately by the caller.
 *
 * <p>Uses only Telegram-supported HTML tags: {@code <b>}, {@code <i>},
 * {@code <code>}, {@code <blockquote>}, {@code <a>}.
 */
public class TelegramAgentStreamRenderer {

    private static final int OBSERVATION_MAX_LENGTH = 500;
    private static final int TOOL_QUERY_MAX_LENGTH = 220;
    private static final int TOOL_ERROR_MAX_LENGTH = 220;
    private static final String NO_TOOL_OUTPUT = "(no tool output)";
    private static final String NO_RESULT = "no result";
    private static final String FRIENDLY_TOO_LARGE = "Page is too large to parse";
    private static final String FRIENDLY_UNREADABLE_2XX = "Site returned HTTP 200, but content could not be extracted";
    private static final Pattern JSON_QUERY_PATTERN =
            Pattern.compile("\"query\"\\s*:\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern JSON_URL_PATTERN =
            Pattern.compile("\"(?:url|uri)\"\\s*:\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTTP_URL_PATTERN = Pattern.compile("https?://[^\\s\"'<>)}\\]]+");

    /**
     * Renders an agent stream event as Telegram HTML.
     *
     * @param event the agent stream event
     * @return HTML string to send, or {@code null} if the event should not be sent as a status message
     */
    public String render(AgentStreamEvent event) {
        return switch (event.type()) {
            case THINKING -> renderThinking(event.content());
            case TOOL_CALL -> renderToolCall(event.content());
            case OBSERVATION -> renderObservation(event.content());
            case ERROR -> "<b>Error:</b> <i>" + escapeHtml(event.content()) + "</i>";
            case FINAL_ANSWER_CHUNK, FINAL_ANSWER, MAX_ITERATIONS, METADATA -> null;
        };
    }

    private String renderThinking(String content) {
        if (content == null || content.isBlank()) {
            return "<i>\uD83D\uDCAD Thinking...</i>";
        }
        return "<i>\uD83D\uDCAD " + escapeHtml(truncate(content, OBSERVATION_MAX_LENGTH)) + "</i>";
    }

    private String renderToolCall(String content) {
        if (content == null) {
            return "<b>\uD83D\uDD27 Tool call</b>";
        }
        int colonIndex = content.indexOf(": ");
        String toolName = colonIndex < 0 ? content : content.substring(0, colonIndex);
        String args = colonIndex < 0 ? null : content.substring(colonIndex + 2);
        String header = "<b>\uD83D\uDD27 Tool:</b> <code>" + escapeHtml(toolName) + "</code>";
        if ("web_search".equalsIgnoreCase(toolName != null ? toolName.trim() : null)) {
            return renderWebSearchToolCall(header, args);
        }
        String toolUrl = extractToolUrl(toolName, args);
        if (toolUrl == null) {
            return header;
        }
        String escapedUrl = escapeHtml(toolUrl);
        return header + "\n<i>URL: <a href=\"" + escapeHtmlAttribute(toolUrl) + "\">" + escapedUrl + "</a></i>";
    }

    private String renderWebSearchToolCall(String header, String args) {
        String queryText = extractJsonFieldValue(JSON_QUERY_PATTERN, args);
        if (queryText == null || queryText.isBlank()) {
            queryText = args;
        }
        if (queryText == null || queryText.isBlank()) {
            return header;
        }

        StringBuilder details = new StringBuilder(header);
        details.append("\n<i>Query: ")
                .append(escapeHtml(truncate(queryText.trim(), TOOL_QUERY_MAX_LENGTH)))
                .append("</i>");

        String queryUrl = extractFirstHttpUrl(queryText);
        if (queryUrl != null) {
            details.append("\n<i>URL: <a href=\"")
                    .append(escapeHtmlAttribute(queryUrl))
                    .append("\">")
                    .append(escapeHtml(queryUrl))
                    .append("</a></i>");
        }
        return details.toString();
    }

    private String renderObservation(String content) {
        if (isNoResult(content)) {
            return "<blockquote>\uD83D\uDCCB No result</blockquote>";
        }
        if (isToolError(content)) {
            String rawReason = extractErrorReason(content);
            String friendlyReason = toUserFriendlyReason(rawReason);
            return "<blockquote>\u26A0\uFE0F Tool failed: "
                    + escapeHtml(truncate(friendlyReason, TOOL_ERROR_MAX_LENGTH))
                    + "</blockquote>";
        }
        return "<blockquote>\uD83D\uDCCB Tool result received</blockquote>";
    }

    private static String extractToolUrl(String toolName, String args) {
        if (toolName == null || args == null || args.isBlank()) {
            return null;
        }
        String normalizedToolName = toolName.trim().toLowerCase(Locale.ROOT);
        if ("web_search".equals(normalizedToolName)) {
            String queryText = extractJsonFieldValue(JSON_QUERY_PATTERN, args);
            return extractFirstHttpUrl(queryText != null ? queryText : args);
        }
        if (!"fetch_url".equals(normalizedToolName)
                && !"http_get".equals(normalizedToolName)
                && !"http_post".equals(normalizedToolName)) {
            return null;
        }
        String jsonUrl = extractJsonFieldValue(JSON_URL_PATTERN, args);
        if (jsonUrl != null) {
            return jsonUrl;
        }
        return extractFirstHttpUrl(args);
    }

    private static String extractJsonFieldValue(Pattern pattern, String args) {
        if (args == null || args.isBlank()) {
            return null;
        }
        Matcher matcher = pattern.matcher(args);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    private static String extractFirstHttpUrl(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher matcher = HTTP_URL_PATTERN.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        return matcher.group();
    }

    private static boolean isNoResult(String content) {
        if (content == null || content.isBlank()) {
            return true;
        }
        String normalized = content.trim();
        return NO_TOOL_OUTPUT.equalsIgnoreCase(normalized) || NO_RESULT.equalsIgnoreCase(normalized);
    }

    private static boolean isToolError(String content) {
        if (content == null || content.isBlank()) {
            return false;
        }
        String normalized = content.trim().toLowerCase(Locale.ROOT);
        return normalized.startsWith("error:")
                || normalized.startsWith("failed:")
                || normalized.contains(" failed:");
    }

    private static String extractErrorReason(String content) {
        String normalized = content == null ? "" : content.trim();
        if (normalized.regionMatches(true, 0, "error:", 0, "error:".length())) {
            return normalized.substring("error:".length()).trim();
        }
        if (normalized.regionMatches(true, 0, "failed:", 0, "failed:".length())) {
            return normalized.substring("failed:".length()).trim();
        }
        String lowered = normalized.toLowerCase(Locale.ROOT);
        int failedMarkerIndex = lowered.indexOf(" failed:");
        if (failedMarkerIndex >= 0) {
            return normalized.substring(failedMarkerIndex + " failed:".length()).trim();
        }
        return normalized;
    }

    private static String toUserFriendlyReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "Unknown tool failure";
        }
        String normalized = reason.toLowerCase(Locale.ROOT);
        if (normalized.contains("too_large")
                || normalized.contains("response body too large")
                || normalized.contains("databufferlimitexception")) {
            return FRIENDLY_TOO_LARGE;
        }
        if (normalized.contains("unreadable_2xx")
                || normalized.startsWith("http 200")
                || normalized.contains("status=200")) {
            return FRIENDLY_UNREADABLE_2XX;
        }
        return reason;
    }

    private static String escapeHtmlAttribute(String text) {
        return escapeHtml(text).replace("\"", "&quot;");
    }

    private static String escapeHtml(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private static String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }
}
