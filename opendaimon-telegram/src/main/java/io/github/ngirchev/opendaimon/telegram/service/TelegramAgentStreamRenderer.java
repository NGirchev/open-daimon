package io.github.ngirchev.opendaimon.telegram.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ngirchev.opendaimon.common.agent.AgentStreamEvent;
import io.github.ngirchev.opendaimon.telegram.command.handler.impl.fsm.MessageHandlerContext;
import lombok.RequiredArgsConstructor;

/**
 * Translates an {@link AgentStreamEvent} into a pure {@link RenderedUpdate} describing
 * what the Telegram orchestrator should do. The renderer is side-effect-free: it does
 * not touch Telegram, does not mutate the context (callers read the context state to
 * pick the right branch), and is trivially unit-testable.
 *
 * <p>{@code PARTIAL_ANSWER} and {@code FINAL_ANSWER} return {@link RenderedUpdate.NoOp}
 * because the orchestrator handles the tentative-answer bubble lifecycle directly —
 * that logic is stateful (edit/open/rotate) and belongs next to the Telegram API calls.
 */
@RequiredArgsConstructor
public class TelegramAgentStreamRenderer {

    private final ObjectMapper objectMapper;

    /**
     * Returns the update that should be applied for this event given the current
     * orchestrator context state.
     *
     * <p>The context is read-only here: renderer only inspects
     * {@link MessageHandlerContext#getCurrentIteration()} and
     * {@link MessageHandlerContext#isTentativeAnswerActive()}. All mutation happens in
     * the orchestrator that consumes the returned update.
     */
    public RenderedUpdate render(AgentStreamEvent event, MessageHandlerContext ctx) {
        return switch (event.type()) {
            case THINKING -> renderThinking(event, ctx);
            case TOOL_CALL -> renderToolCall(event, ctx);
            case OBSERVATION -> renderObservation(event);
            case ERROR -> new RenderedUpdate.AppendErrorToStatus(nullToEmpty(event.content()));
            // PARTIAL_ANSWER / FINAL_ANSWER / MAX_ITERATIONS / METADATA are orchestrated
            // directly (they interact with the tentative-answer lifecycle or with
            // responseText persistence), so the renderer doesn't emit an update for them.
            case PARTIAL_ANSWER, FINAL_ANSWER, MAX_ITERATIONS, METADATA -> new RenderedUpdate.NoOp();
        };
    }

    private RenderedUpdate renderThinking(AgentStreamEvent event, MessageHandlerContext ctx) {
        String content = event.content();
        if (content == null || content.isBlank()) {
            // Placeholder "THINKING" marker — fires at the start of each iteration.
            // If this is a new iteration boundary, the orchestrator appends a fresh
            // "💭 Thinking..." line; otherwise (first iteration where ensureStatusMessage
            // already planted the marker) it's a no-op.
            if (event.iteration() != ctx.getCurrentIteration()) {
                return new RenderedUpdate.AppendFreshThinking();
            }
            return new RenderedUpdate.NoOp();
        }
        // Structured reasoning text (from provider metadata): overlay it as the trailing
        // reasoning line, replacing "💭 Thinking..." or the previous reasoning snippet.
        return new RenderedUpdate.ReplaceTrailingThinkingLine(content);
    }

    private RenderedUpdate renderToolCall(AgentStreamEvent event, MessageHandlerContext ctx) {
        ToolCallParts parts = parseToolCall(event.content());
        if (ctx.isTentativeAnswerActive()) {
            String folded = ctx.getTentativeAnswerBuffer().toString();
            return new RenderedUpdate.RollbackAndAppendToolCall(parts.toolName(), parts.args(), folded);
        }
        return new RenderedUpdate.AppendToolCall(parts.toolName(), parts.args());
    }

    private RenderedUpdate renderObservation(AgentStreamEvent event) {
        String content = event.content();
        if (event.error()) {
            return new RenderedUpdate.AppendObservation(
                    RenderedUpdate.ObservationKind.FAILED, nullToEmpty(content));
        }
        if (content == null || content.isBlank() || "(no tool output)".equals(content)) {
            return new RenderedUpdate.AppendObservation(RenderedUpdate.ObservationKind.EMPTY, "");
        }
        return new RenderedUpdate.AppendObservation(RenderedUpdate.ObservationKind.RESULT, "");
    }

    /**
     * Parses {@code AgentStreamEvent.toolCall} content which is formatted as
     * {@code "toolName: argsJson"}. Attempts to extract a friendly, per-tool argument
     * (e.g. the {@code query} field for {@code web_search}); falls back to the raw JSON
     * string, and to an empty-args tuple on malformed input.
     */
    private ToolCallParts parseToolCall(String content) {
        if (content == null || content.isBlank()) {
            return new ToolCallParts("", "");
        }
        int colonIndex = content.indexOf(": ");
        String toolName = colonIndex >= 0 ? content.substring(0, colonIndex) : content;
        String argsJson = colonIndex >= 0 ? content.substring(colonIndex + 2) : "";
        String friendlyArg = extractFriendlyArg(toolName, argsJson);
        return new ToolCallParts(toolName, friendlyArg != null ? friendlyArg : "");
    }

    private String extractFriendlyArg(String toolName, String argsJson) {
        if (argsJson == null || argsJson.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(argsJson);
            return extractFirstStringValue(node);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private String extractFirstStringValue(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        var it = node.fields();
        while (it.hasNext()) {
            var entry = it.next();
            JsonNode v = entry.getValue();
            if (v.isTextual() && !v.asText().isBlank()) {
                return v.asText();
            }
        }
        return null;
    }

    private static String nullToEmpty(String text) {
        return text == null ? "" : text;
    }

    /** Internal tuple: parsed tool name + friendly argument (already truncated-ready). */
    private record ToolCallParts(String toolName, String args) {}
}
