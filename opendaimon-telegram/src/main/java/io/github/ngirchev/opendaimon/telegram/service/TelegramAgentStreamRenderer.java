package io.github.ngirchev.opendaimon.telegram.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ngirchev.opendaimon.common.agent.AgentStreamEvent;
import lombok.RequiredArgsConstructor;

import java.util.Map;

/**
 * Converts {@link AgentStreamEvent} into <em>raw markdown</em> text fragments
 * that are appended to a single, growing agent transcript. The transcript
 * buffer is later rendered to Telegram HTML once per edit call — doing the
 * markdown-to-HTML conversion over the whole buffer lets spanning tokens
 * (e.g. {@code **bold**}) survive chunk boundaries that would otherwise break
 * if each chunk was rendered independently.
 *
 * <p>Returns {@code null} for events that do not contribute visible text to
 * the transcript (model metadata, placeholder THINKING frames with no
 * content, and terminal events that carry a full-final-answer payload which
 * has already been streamed in chunks).
 */
@RequiredArgsConstructor
public class TelegramAgentStreamRenderer {

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
     * Renders an event as raw markdown text ready to be appended to the
     * transcript buffer. Returns {@code null} when the event contributes no
     * transcript text (the caller decides how to handle it — e.g. METADATA
     * updates the model name, FINAL_ANSWER/MAX_ITERATIONS set responseText).
     */
    public String render(AgentStreamEvent event) {
        return switch (event.type()) {
            case PARTIAL_ANSWER -> event.content();
            case TOOL_CALL -> renderToolCall(event.content());
            case OBSERVATION -> "\n\n*✅ done*\n\n";
            case ERROR -> "\n\n**❌ Error:** " + nullToEmpty(event.content()) + "\n\n";
            // Surfaced as a trailing marker so the user sees WHY the stream stopped
            // instead of an abrupt cut in reasoning text. The event's content field
            // duplicates what was already streamed via PARTIAL_ANSWER chunks, so only
            // the marker itself goes into the transcript.
            case MAX_ITERATIONS -> "\n\n*⚠️ reached iteration limit*\n\n";
            // Structured reasoning from the provider arrives separately from the visible
            // assistant text stream (which already flows in via PARTIAL_ANSWER). We skip
            // it to avoid duplicating reasoning in the transcript.
            case THINKING -> null;
            case FINAL_ANSWER, METADATA -> null;
        };
    }

    private String renderToolCall(String content) {
        if (content == null) {
            return "\n\n**🔧 " + DEFAULT_TOOL_LABEL + "…**\n\n";
        }
        int colonIndex = content.indexOf(": ");
        String toolName = colonIndex >= 0 ? content.substring(0, colonIndex) : content;
        String argsJson = colonIndex >= 0 ? content.substring(colonIndex + 2) : "";
        String label = TOOL_LABELS.getOrDefault(toolName, DEFAULT_TOOL_LABEL);
        String arg = extractArg(toolName, argsJson);
        if (arg == null || arg.isBlank()) {
            return "\n\n**🔧 " + label + "…**\n\n";
        }
        return "\n\n**🔧 " + label + ":** " + truncate(arg, TOOL_ARG_MAX_LENGTH) + "\n\n";
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

    private static String nullToEmpty(String text) {
        return text == null ? "" : text;
    }

    private static String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "…";
    }
}
