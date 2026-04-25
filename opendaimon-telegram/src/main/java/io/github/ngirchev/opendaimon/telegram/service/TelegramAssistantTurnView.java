package io.github.ngirchev.opendaimon.telegram.service;

import io.github.ngirchev.opendaimon.common.model.ThinkingMode;
import io.github.ngirchev.opendaimon.telegram.command.handler.impl.fsm.AssistantTurn;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders an {@link AssistantTurn} into one or more Telegram messages and keeps them in
 * sync with the model on each {@link #reconcile()} call. The view owns the message-id
 * mapping ({@code statusMessageId} + ordered list of {@code answerMessageIds}) and never
 * leaks Telegram-API decisions back into the FSM — actions just mutate {@link AssistantTurn},
 * and at end-of-turn the FSM calls {@link #reconcile()} or {@link #flushFinal()}.
 *
 * <p>Rendering by {@link ThinkingMode}:
 * <ul>
 *   <li><b>SILENT</b> — never shows reasoning. While the turn streams, no message is sent.
 *       On {@code SETTLED} the final answer is split at paragraph boundaries (max
 *       {@code maxMessageLength} chars) and sent as N answer messages.</li>
 *   <li><b>HIDE_REASONING</b> — a status message shows the last reasoning line italicised
 *       plus any tool-call entries. After {@code SETTLED} the status message is left as
 *       the latest tool-call summary; the final answer is sent as separate messages.</li>
 *   <li><b>SHOW_ALL</b> — the status message accumulates all reasoning text + tool-call
 *       entries (a transcript). Final answer is sent separately.</li>
 *   <li><b>ERROR</b> — final answer messages are skipped; status either remains as last
 *       reasoning snapshot or is overwritten with the localized error placeholder by the
 *       caller.</li>
 * </ul>
 *
 * <p>Splitting: when {@code finalAnswer.length() > maxMessageLength} the answer is split
 * at paragraph boundaries (then sentence, then whitespace, then hard cut) so each piece
 * fits Telegram's per-message limit.
 *
 * <p>Threading: {@code reconcile()} is single-threaded by the caller (FSM pipeline). The
 * underlying {@link TelegramRateLimitedBot} blocks the calling thread while waiting for a
 * quota slot — that's by design. View instances are <strong>not</strong> shared across
 * turns.
 */
@Slf4j
public class TelegramAssistantTurnView {

    public static final String STATUS_THINKING_PLACEHOLDER = "💭 <i>Думаю…</i>";

    private final TelegramRateLimitedBot rate;
    private final long chatId;
    private final Integer replyToMessageId;
    private final int maxMessageLength;
    private final AssistantTurn turn;

    private Integer statusMessageId;
    private String lastSentStatusHtml;
    private final List<Integer> answerMessageIds = new ArrayList<>();
    private final List<String> lastSentAnswerHtmls = new ArrayList<>();

    public TelegramAssistantTurnView(TelegramRateLimitedBot rate,
                                     long chatId,
                                     Integer replyToMessageId,
                                     int maxMessageLength,
                                     AssistantTurn turn) {
        this.rate = rate;
        this.chatId = chatId;
        this.replyToMessageId = replyToMessageId;
        this.maxMessageLength = Math.max(100, maxMessageLength);
        this.turn = turn;
    }

    /**
     * Synchronize the chat with the current state of the turn. Performs the minimum
     * number of {@code sendMessage}/{@code editMessage} calls — text already on the
     * wire is not re-sent if it matches the desired snapshot.
     *
     * <p>The rate-limited bot blocks the caller until the per-chat / global quota
     * opens, so consecutive {@code reconcile()} calls are bounded by the quota and
     * never trigger 429.
     */
    public synchronized void reconcile() {
        AssistantTurn.State state = turn.getState();
        ThinkingMode mode = turn.getThinkingMode();

        String desiredStatus = renderStatus(mode, state);
        syncStatus(desiredStatus);

        List<String> desiredAnswers = renderAnswerMessages(mode, state);
        syncAnswers(desiredAnswers);
    }

    /**
     * Final reconcile for end-of-turn — same as {@link #reconcile()}. Provided as a
     * named entry point so the FSM can call it deterministically after the stream
     * finishes.
     */
    public void flushFinal() {
        reconcile();
    }

    /** Visible for tests. */
    Integer statusMessageId() {
        return statusMessageId;
    }

    /** Visible for tests. */
    List<Integer> answerMessageIds() {
        return List.copyOf(answerMessageIds);
    }

    // ─── Rendering ─────────────────────────────────────────────────────────

    private String renderStatus(ThinkingMode mode, AssistantTurn.State state) {
        if (mode == ThinkingMode.SILENT) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        if (mode == ThinkingMode.SHOW_ALL) {
            String reasoning = turn.getReasoning();
            if (!reasoning.isEmpty()) {
                sb.append("<i>").append(escape(reasoning)).append("</i>");
            }
        } else { // HIDE_REASONING
            String reasoning = turn.getReasoning();
            if (state == AssistantTurn.State.STREAMING) {
                if (reasoning.isEmpty() && turn.getToolCalls().isEmpty()) {
                    return STATUS_THINKING_PLACEHOLDER;
                }
                String tail = tailLine(reasoning, 200);
                if (!tail.isEmpty()) {
                    sb.append("💭 <i>").append(escape(tail)).append("</i>");
                }
            }
        }
        for (AssistantTurn.ToolCallEntry call : turn.getToolCalls()) {
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append("🔧 <b>Tool:</b> ").append(escape(call.tool()));
            if (call.args() != null && !call.args().isBlank()) {
                sb.append("\n<b>Args:</b> ").append(escape(call.args()));
            }
            if (call.observation() != null) {
                sb.append("\n📋 <i>").append(escape(tailLine(call.observation(), 240))).append("</i>");
            }
        }
        if (sb.length() == 0) {
            return state == AssistantTurn.State.STREAMING ? STATUS_THINKING_PLACEHOLDER : null;
        }
        return sb.toString();
    }

    private List<String> renderAnswerMessages(ThinkingMode mode, AssistantTurn.State state) {
        if (state == AssistantTurn.State.ERROR) {
            return List.of();
        }
        String answer = turn.getFinalAnswer();
        if (answer == null || answer.isBlank()) {
            return List.of();
        }
        return splitForTelegram(answer, maxMessageLength);
    }

    private void syncStatus(String desired) {
        if (desired == null) {
            return; // SILENT mode never opens a status; ERROR/SETTLED leaves it as-is.
        }
        if (statusMessageId == null) {
            Integer id = rate.sendMessage(chatId, desired, replyToMessageId, true);
            if (id != null) {
                statusMessageId = id;
                lastSentStatusHtml = desired;
            }
            return;
        }
        if (!desired.equals(lastSentStatusHtml)) {
            if (rate.editMessage(chatId, statusMessageId, desired, true)) {
                lastSentStatusHtml = desired;
            }
        }
    }

    private void syncAnswers(List<String> desiredAnswers) {
        for (int i = 0; i < desiredAnswers.size(); i++) {
            String expected = desiredAnswers.get(i);
            if (i >= answerMessageIds.size()) {
                Integer id = rate.sendMessage(chatId, expected, replyToMessageId, false);
                if (id != null) {
                    answerMessageIds.add(id);
                    lastSentAnswerHtmls.add(expected);
                }
            } else if (!expected.equals(lastSentAnswerHtmls.get(i))) {
                if (rate.editMessage(chatId, answerMessageIds.get(i), expected, false)) {
                    lastSentAnswerHtmls.set(i, expected);
                }
            }
        }
    }

    // ─── Pure helpers ──────────────────────────────────────────────────────

    static List<String> splitForTelegram(String text, int max) {
        if (text.length() <= max) {
            return List.of(text);
        }
        List<String> out = new ArrayList<>();
        int idx = 0;
        while (idx < text.length()) {
            int remaining = text.length() - idx;
            if (remaining <= max) {
                out.add(text.substring(idx));
                break;
            }
            int end = idx + max;
            // Prefer paragraph boundary
            int cut = text.lastIndexOf("\n\n", end);
            if (cut <= idx) {
                // Try sentence boundary
                cut = text.lastIndexOf(". ", end);
                if (cut <= idx) {
                    cut = text.lastIndexOf(' ', end);
                }
            }
            if (cut <= idx) {
                cut = end; // hard cut
            }
            out.add(text.substring(idx, cut).stripTrailing());
            idx = cut;
            while (idx < text.length() && (text.charAt(idx) == '\n' || text.charAt(idx) == ' ')) {
                idx++;
            }
        }
        return out;
    }

    private static String tailLine(String text, int maxChars) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        int newline = text.lastIndexOf('\n');
        String tail = newline < 0 ? text : text.substring(newline + 1);
        if (tail.length() > maxChars) {
            tail = tail.substring(tail.length() - maxChars);
        }
        return tail.trim();
    }

    private static String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
