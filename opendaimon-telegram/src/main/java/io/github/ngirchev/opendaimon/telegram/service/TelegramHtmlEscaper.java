package io.github.ngirchev.opendaimon.telegram.service;

/**
 * HTML-escape helper for Telegram messages that use {@code parse_mode=HTML}.
 *
 * <p>Escapes only the characters that Telegram treats as HTML syntax:
 * {@code &}, {@code <}, {@code >}. {@code &} is replaced first so that pre-existing
 * entities are not double-escaped.
 *
 * <p>Used by the agent-stream orchestrator where the status and tentative-answer
 * buffers hold <em>pre-escaped</em> HTML fragments: bot literals (emoji, {@code Tool:},
 * {@code Query:}, {@code 💭 Thinking...}, separators) are never escaped, and every
 * fragment authored by the model or user (tool name, arguments, reasoning, error text,
 * PARTIAL_ANSWER chunks, FINAL_ANSWER text) is escaped through this helper before
 * being appended.
 */
public final class TelegramHtmlEscaper {

    private TelegramHtmlEscaper() {}

    public static String escape(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
