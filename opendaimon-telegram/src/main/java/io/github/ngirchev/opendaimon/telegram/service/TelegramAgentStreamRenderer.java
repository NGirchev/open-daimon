package io.github.ngirchev.opendaimon.telegram.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ngirchev.opendaimon.common.agent.AgentStreamEvent;
import lombok.RequiredArgsConstructor;

import java.util.Map;

/**
 * Converts {@link AgentStreamEvent} into Telegram-compatible HTML strings.
 *
 * <p>Returns {@code null} for terminal events (FINAL_ANSWER, MAX_ITERATIONS)
 * that are handled separately by the caller.
 *
 * <p>Uses only Telegram-supported HTML tags: {@code <b>}, {@code <i>},
 * {@code <code>}, {@code <blockquote>}.
 */
@RequiredArgsConstructor
public class TelegramAgentStreamRenderer {

    private static final int THINKING_MAX_LENGTH = 500;

    private static final int TOOL_ARG_MAX_LENGTH = 200;

    private static final String DEFAULT_TOOL_LABEL = "Using a tool";

    private static final Map<String, String> TOOL_LABELS = Map.of(
            "web_search", "Searching the web",
            "fetch_url", "Reading a web page",
            "http_get", "Making an HTTP request",
            "http_post", "Sending an HTTP request"
    );

    private static final Map<String, String> TOOL_ARG_KEYS = Map.of(
            "web_search", "query",
            "fetch_url", "url",
            "http_get", "url",
            "http_post", "url"
    );

    private final ObjectMapper objectMapper;

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
            case FINAL_ANSWER, MAX_ITERATIONS, METADATA, PARTIAL_ANSWER -> null;
        };
    }

    private String renderThinking(String content) {
        if (content == null || content.isBlank()) {
            return "<i>\uD83E\uDD14 Thinking...</i>";
        }
        return "<i>\uD83E\uDD14 " + escapeHtml(truncate(content, THINKING_MAX_LENGTH)) + "</i>";
    }

    private String renderToolCall(String content) {
        if (content == null) {
            return "<b>\uD83D\uDD27 " + escapeHtml(DEFAULT_TOOL_LABEL) + "...</b>";
        }
        int colonIndex = content.indexOf(": ");
        String toolName = colonIndex >= 0 ? content.substring(0, colonIndex) : content;
        String argsJson = colonIndex >= 0 ? content.substring(colonIndex + 2) : "";
        String label = TOOL_LABELS.getOrDefault(toolName, DEFAULT_TOOL_LABEL);
        String arg = extractArg(toolName, argsJson);
        if (arg == null || arg.isBlank()) {
            return "<b>\uD83D\uDD27 " + escapeHtml(label) + "...</b>";
        }
        return "<b>\uD83D\uDD27 " + escapeHtml(label) + ":</b> "
                + escapeHtml(truncate(arg, TOOL_ARG_MAX_LENGTH));
    }

    private String extractArg(String toolName, String argsJson) {
        String key = TOOL_ARG_KEYS.get(toolName);
        if (key == null || argsJson == null || argsJson.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(argsJson);
            JsonNode value = node.get(key);
            return value != null && value.isTextual() ? value.asText() : null;
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private String renderObservation(String content) {
        return "<i>\u2705 Done</i>";
    }

    private static String escapeHtml(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
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
