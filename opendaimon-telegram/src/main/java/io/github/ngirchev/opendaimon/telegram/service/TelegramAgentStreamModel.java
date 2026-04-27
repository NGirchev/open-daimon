package io.github.ngirchev.opendaimon.telegram.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ngirchev.opendaimon.common.agent.AgentStreamEvent;
import io.github.ngirchev.opendaimon.common.service.AIUtils;

/**
 * Provider-neutral model for one agent stream.
 *
 * <p>The Spring AI agent loop emits the same {@link AgentStreamEvent} sequence for
 * OpenRouter, Ollama, and any future provider. This model keeps that stream as local
 * state first, then lets Telegram render periodic snapshots from it. A
 * {@code PARTIAL_ANSWER} is only a candidate while the current iteration is still open:
 * a later tool call can prove it was pre-tool reasoning. Only terminal
 * {@code FINAL_ANSWER}/{@code MAX_ITERATIONS} content becomes the confirmed user answer.
 */
public final class TelegramAgentStreamModel {

    public static final String STATUS_THINKING_LINE = "💭 Thinking...";
    public static final String STATUS_MAX_ITER_LINE = "⚠️ reached iteration limit";

    private static final int CANDIDATE_TAIL_LIMIT = 400;
    private static final String MISSING_TOOL_ARGUMENT = "missing";

    private final boolean silent;
    private final boolean preserveReasoning;
    private final ObjectMapper objectMapper;
    private final StringBuilder statusHtml = new StringBuilder();
    private final StringBuilder candidateEscaped = new StringBuilder();
    private String confirmedAnswer;
    private boolean statusDirty;
    private boolean answerDirty;
    private int currentIteration = -1;
    private boolean toolCallSeenThisIteration;

    public TelegramAgentStreamModel(boolean silent, boolean preserveReasoning) {
        this(silent, preserveReasoning, new ObjectMapper());
    }

    public TelegramAgentStreamModel(boolean silent, boolean preserveReasoning, ObjectMapper objectMapper) {
        this.silent = silent;
        this.preserveReasoning = preserveReasoning;
        this.objectMapper = objectMapper;
        if (!silent) {
            statusHtml.append(STATUS_THINKING_LINE);
            statusDirty = true;
            currentIteration = 0;
        }
    }

    public void apply(AgentStreamEvent event) {
        if (event == null) {
            return;
        }
        switch (event.type()) {
            case METADATA -> {
                // Side-channel metadata is handled by the FSM context.
            }
            case THINKING -> applyThinking(event);
            case PARTIAL_ANSWER -> applyPartialAnswer(event);
            case TOOL_CALL -> applyToolCall(event);
            case OBSERVATION -> applyObservation(event);
            case FINAL_ANSWER -> confirmAnswer(event.content());
            case MAX_ITERATIONS -> applyMaxIterations(event.content());
            case ERROR -> appendStatus("\n\n❌ Error: " + TelegramHtmlEscaper.escape(nullToEmpty(event.content())));
        }
    }

    public String statusHtml() {
        return statusHtml.toString();
    }

    public String answerHtml() {
        return confirmedAnswer == null ? "" : AIUtils.convertMarkdownToHtml(confirmedAnswer);
    }

    public String answerText() {
        return confirmedAnswer == null ? "" : confirmedAnswer;
    }

    public boolean hasStatus() {
        return !silent && !statusHtml.isEmpty();
    }

    public boolean hasConfirmedAnswer() {
        return confirmedAnswer != null && !confirmedAnswer.isBlank();
    }

    public boolean isStatusDirty() {
        return statusDirty;
    }

    public boolean isAnswerDirty() {
        return answerDirty;
    }

    public void markStatusClean() {
        statusDirty = false;
    }

    public void markAnswerClean() {
        answerDirty = false;
    }

    public int currentIteration() {
        return currentIteration;
    }

    public boolean isToolCallSeenThisIteration() {
        return toolCallSeenThisIteration;
    }

    public boolean hasCandidateText() {
        return candidateEscaped.length() > 0;
    }

    private void applyThinking(AgentStreamEvent event) {
        boolean newIteration = event.iteration() != currentIteration;
        updateIteration(event.iteration());
        if (silent) {
            return;
        }
        String content = event.content();
        if (content == null || content.isBlank()) {
            if (statusHtml.isEmpty()) {
                appendStatus(STATUS_THINKING_LINE);
            } else if (newIteration) {
                appendStatus("\n\n" + STATUS_THINKING_LINE);
            }
            return;
        }
        String reasoningHtml = "<i>" + collapseToSingleLine(TelegramHtmlEscaper.escape(content)) + "</i>";
        if (statusHtml.toString().endsWith("</i>") || statusHtml.toString().endsWith(STATUS_THINKING_LINE)) {
            replaceTrailingLine(reasoningHtml);
        } else {
            appendStatus("\n\n" + reasoningHtml);
        }
    }

    private void applyPartialAnswer(AgentStreamEvent event) {
        updateIteration(event.iteration());
        String chunk = event.content();
        if (chunk == null || chunk.isEmpty()) {
            return;
        }
        candidateEscaped.append(TelegramHtmlEscaper.escape(chunk));
        if (!silent && !toolCallSeenThisIteration) {
            replaceTrailingLine(candidateTailOverlay());
        }
    }

    private void applyToolCall(AgentStreamEvent event) {
        updateIteration(event.iteration());
        toolCallSeenThisIteration = true;
        if (silent) {
            candidateEscaped.setLength(0);
            return;
        }
        ToolCallParts parts = parseToolCall(event.content());
        String blockBody = renderToolCallBlock(parts.toolName(), parts.args());
        if (preserveReasoning) {
            if (candidateEscaped.length() > 0) {
                replaceTrailingLine(candidateTailOverlay());
            }
            appendStatus("\n\n" + blockBody);
        } else {
            replaceTrailingLine(blockBody);
        }
        candidateEscaped.setLength(0);
    }

    private void applyObservation(AgentStreamEvent event) {
        if (silent) {
            return;
        }
        String body;
        if (event.error()) {
            body = "⚠️ Tool failed: " + TelegramHtmlEscaper.escape(nullToEmpty(event.content()));
        } else if (event.content() == null || event.content().isBlank()
                || "(no tool output)".equals(event.content())) {
            body = "📋 No result";
        } else {
            body = "📋 Tool result received";
        }
        appendStatus("\n<blockquote>" + body + "</blockquote>");
    }

    private void applyMaxIterations(String content) {
        confirmAnswer(content);
        if (!silent) {
            appendStatus("\n\n" + STATUS_MAX_ITER_LINE);
        }
    }

    public void confirmAnswer(String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        if (content.equals(confirmedAnswer)) {
            return;
        }
        confirmedAnswer = content;
        clearTrailingStatusOverlay();
        candidateEscaped.setLength(0);
        answerDirty = true;
    }

    /**
     * Drops the trailing italic status line after answer confirmation when it is either
     * a streamed answer candidate or hidden reasoning. SHOW_ALL keeps pure reasoning
     * overlays, but still removes candidate overlays to avoid duplicating the answer.
     */
    private void clearTrailingStatusOverlay() {
        if (silent) {
            return;
        }
        boolean candidateOverlayRendered = candidateEscaped.length() > 0;
        if (preserveReasoning && !candidateOverlayRendered) {
            return;
        }
        String html = statusHtml.toString();
        if (!html.endsWith("</i>")) {
            return;
        }
        int lastBoundary = html.lastIndexOf("\n\n");
        int trailingLineStart = lastBoundary >= 0 ? lastBoundary + 2 : 0;
        if (!html.startsWith("<i>", trailingLineStart)) {
            return;
        }
        if (lastBoundary >= 0) {
            statusHtml.setLength(lastBoundary);
        } else {
            // Overlay was the only content; Telegram rejects empty edits, so leave a
            // minimal completion marker.
            statusHtml.setLength(0);
            statusHtml.append("✅");
        }
        statusDirty = true;
    }

    private void updateIteration(int iteration) {
        if (iteration != currentIteration) {
            currentIteration = iteration;
            toolCallSeenThisIteration = false;
            candidateEscaped.setLength(0);
        }
    }

    private void appendStatus(String escapedHtml) {
        if (escapedHtml == null || escapedHtml.isEmpty()) {
            return;
        }
        statusHtml.append(escapedHtml);
        statusDirty = true;
    }

    private void replaceTrailingLine(String escapedHtml) {
        int lastBoundary = statusHtml.lastIndexOf("\n\n");
        int cut = lastBoundary >= 0 ? lastBoundary + 2 : 0;
        statusHtml.setLength(cut);
        statusHtml.append(escapedHtml);
        statusDirty = true;
    }

    private String candidateTailOverlay() {
        int rawStart = Math.max(0, candidateEscaped.length() - CANDIDATE_TAIL_LIMIT);
        int wordStart = rawStart;
        if (rawStart > 0) {
            // Skip forward to the next whitespace so the tail starts on a word boundary.
            // Without this, a `**bold**` pair can be sliced mid-marker and the regex in
            // AIUtils.applyMarkdownReplacements leaves the orphan `**` visible in chat.
            for (int i = rawStart; i < candidateEscaped.length(); i++) {
                char c = candidateEscaped.charAt(i);
                if (c == ' ' || c == '\n' || c == '\t') {
                    wordStart = i + 1;
                    break;
                }
            }
        }
        String tailEscaped = candidateEscaped.substring(wordStart);
        String tailHtml = AIUtils.convertEscapedMarkdownToHtml(collapseToSingleLine(tailEscaped));
        return "<i>" + tailHtml + "</i>";
    }

    private String renderToolCallBlock(String toolName, String args) {
        String label = ToolLabels.label(toolName);
        String escapedArgs = args == null || args.isBlank()
                ? ""
                : TelegramHtmlEscaper.escape(ToolLabels.truncateArg(args));
        return escapedArgs.isEmpty()
                ? "🔧 <b>Tool:</b> " + label + "\n<b>Query:</b> " + MISSING_TOOL_ARGUMENT
                : "🔧 <b>Tool:</b> " + label + "\n<b>Query:</b> " + escapedArgs;
    }

    private ToolCallParts parseToolCall(String content) {
        if (content == null || content.isBlank()) {
            return new ToolCallParts("", "");
        }
        int colonIndex = content.indexOf(": ");
        String toolName = colonIndex >= 0 ? content.substring(0, colonIndex) : content;
        String argsJson = colonIndex >= 0 ? content.substring(colonIndex + 2) : "";
        String friendlyArg = extractFriendlyArg(argsJson);
        return new ToolCallParts(toolName, friendlyArg != null ? friendlyArg : "");
    }

    private String extractFriendlyArg(String argsJson) {
        if (argsJson == null || argsJson.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(argsJson);
            if (!node.isObject()) {
                return null;
            }
            var fields = node.fields();
            while (fields.hasNext()) {
                JsonNode value = fields.next().getValue();
                if (value.isTextual() && !value.asText().isBlank()) {
                    return value.asText();
                }
            }
            return null;
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static String collapseToSingleLine(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        return value.replaceAll("\\s+", " ").trim();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private record ToolCallParts(String toolName, String args) {}
}
