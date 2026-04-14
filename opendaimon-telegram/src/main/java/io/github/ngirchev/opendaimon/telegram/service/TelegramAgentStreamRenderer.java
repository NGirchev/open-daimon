package io.github.ngirchev.opendaimon.telegram.service;

import io.github.ngirchev.opendaimon.common.agent.AgentStreamEvent;

/**
 * Converts {@link AgentStreamEvent} into Telegram-compatible HTML strings.
 *
 * <p>Returns {@code null} for terminal events (FINAL_ANSWER, MAX_ITERATIONS)
 * that are handled separately by the caller.
 *
 * <p>Uses only Telegram-supported HTML tags: {@code <b>}, {@code <i>},
 * {@code <code>}, {@code <blockquote>}.
 */
public class TelegramAgentStreamRenderer {

    private static final int OBSERVATION_MAX_LENGTH = 500;

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
            case FINAL_ANSWER, MAX_ITERATIONS, METADATA -> null;
        };
    }

    private String renderThinking(String content) {
        if (content == null || content.isBlank()) {
            return "<i>\uD83E\uDD14 Thinking...</i>";
        }
        return "<i>\uD83E\uDD14 " + escapeHtml(truncate(content, OBSERVATION_MAX_LENGTH)) + "</i>";
    }

    private String renderToolCall(String content) {
        if (content == null) {
            return "<b>\uD83D\uDD27 Tool call</b>";
        }
        int colonIndex = content.indexOf(": ");
        if (colonIndex < 0) {
            return "<b>\uD83D\uDD27 Tool:</b> <code>" + escapeHtml(content) + "</code>";
        }
        String toolName = content.substring(0, colonIndex);
        String args = content.substring(colonIndex + 2);
        return "<b>\uD83D\uDD27 Tool:</b> <code>" + escapeHtml(toolName) + "</code>\n"
                + "<i>" + escapeHtml(truncate(args, OBSERVATION_MAX_LENGTH)) + "</i>";
    }

    private String renderObservation(String content) {
        if (content == null || content.isBlank()) {
            return "<blockquote>\uD83D\uDCCB No result</blockquote>";
        }
        return "<blockquote>\uD83D\uDCCB " + escapeHtml(truncate(content, OBSERVATION_MAX_LENGTH)) + "</blockquote>";
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
