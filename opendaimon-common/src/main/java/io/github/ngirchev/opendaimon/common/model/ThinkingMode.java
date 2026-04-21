package io.github.ngirchev.opendaimon.common.model;

/**
 * Per-user reasoning-visibility mode for the Telegram {@code /thinking} command.
 *
 * <ul>
 *   <li>{@link #SHOW_ALL} — reasoning persists above each tool-call block in the final transcript.</li>
 *   <li>{@link #HIDE_REASONING} — reasoning flashes during the stream, then gets overwritten by the
 *       tool-call block (current default).</li>
 *   <li>{@link #SILENT} — no thinking-related rendering ever: the {@code "💭 Thinking..."} placeholder
 *       is never written, and {@code THINKING} stream events are dropped at the renderer boundary.</li>
 * </ul>
 */
public enum ThinkingMode {
    SHOW_ALL,
    HIDE_REASONING,
    SILENT
}
