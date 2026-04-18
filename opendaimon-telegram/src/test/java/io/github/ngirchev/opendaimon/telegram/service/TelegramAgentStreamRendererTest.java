package io.github.ngirchev.opendaimon.telegram.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ngirchev.opendaimon.common.agent.AgentStreamEvent;
import io.github.ngirchev.opendaimon.telegram.command.TelegramCommand;
import io.github.ngirchev.opendaimon.telegram.command.handler.impl.fsm.MessageHandlerContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.objects.Message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The renderer is side-effect-free: it returns a pure {@link RenderedUpdate} describing
 * what the orchestrator should do. These tests cover each branch of the switch, plus
 * context-dependent behavior (tentative-answer active → rollback; iteration change → fresh thinking).
 */
class TelegramAgentStreamRendererTest {

    private TelegramAgentStreamRenderer renderer;
    private MessageHandlerContext ctx;

    @BeforeEach
    void setUp() {
        renderer = new TelegramAgentStreamRenderer(new ObjectMapper());
        TelegramCommand command = mock(TelegramCommand.class);
        Message message = mock(Message.class);
        when(message.getMessageId()).thenReturn(10);
        ctx = new MessageHandlerContext(command, message, html -> {});
    }

    @Test
    void shouldReturnNoOpForPartialAnswer() {
        // PARTIAL_ANSWER is orchestrated directly (tentative-answer bubble lifecycle) —
        // the renderer stays side-effect-free.
        AgentStreamEvent event = AgentStreamEvent.partialAnswer("Hello", 1);

        assertThat(renderer.render(event, ctx)).isInstanceOf(RenderedUpdate.NoOp.class);
    }

    @Test
    void shouldReturnNoOpForFinalAnswer() {
        AgentStreamEvent event = AgentStreamEvent.finalAnswer("The answer is 42", 3);

        assertThat(renderer.render(event, ctx)).isInstanceOf(RenderedUpdate.NoOp.class);
    }

    @Test
    void shouldReturnNoOpForMaxIterations() {
        AgentStreamEvent event = AgentStreamEvent.maxIterations(null, 10);

        assertThat(renderer.render(event, ctx)).isInstanceOf(RenderedUpdate.NoOp.class);
    }

    @Test
    void shouldReturnNoOpForMetadata() {
        AgentStreamEvent event = AgentStreamEvent.metadata("gpt-4o", 1);

        assertThat(renderer.render(event, ctx)).isInstanceOf(RenderedUpdate.NoOp.class);
    }

    @Test
    void shouldReturnAppendFreshThinkingWhenNullContentAndNewIteration() {
        // ctx.currentIteration starts at -1; a THINKING at iteration 0 is a rollover.
        AgentStreamEvent event = AgentStreamEvent.thinking(0);

        RenderedUpdate result = renderer.render(event, ctx);

        assertThat(result).isInstanceOf(RenderedUpdate.AppendFreshThinking.class);
    }

    @Test
    void shouldReturnNoOpWhenNullContentAndSameIteration() {
        ctx.setCurrentIteration(0);
        AgentStreamEvent event = AgentStreamEvent.thinking(0);

        RenderedUpdate result = renderer.render(event, ctx);

        assertThat(result).isInstanceOf(RenderedUpdate.NoOp.class);
    }

    @Test
    void shouldReturnReplaceTrailingThinkingLineWhenReasoningContent() {
        AgentStreamEvent event = AgentStreamEvent.thinking("Checking prices", 1);

        RenderedUpdate result = renderer.render(event, ctx);

        assertThat(result).isInstanceOf(RenderedUpdate.ReplaceTrailingThinkingLine.class);
        assertThat(((RenderedUpdate.ReplaceTrailingThinkingLine) result).reasoning())
                .isEqualTo("Checking prices");
    }

    @Test
    void shouldParseToolCallWithFriendlyArgWhenTentativeNotActive() {
        AgentStreamEvent event = AgentStreamEvent.toolCall("web_search", "{\"query\":\"btc price\"}", 1);

        RenderedUpdate result = renderer.render(event, ctx);

        assertThat(result).isInstanceOf(RenderedUpdate.AppendToolCall.class);
        RenderedUpdate.AppendToolCall call = (RenderedUpdate.AppendToolCall) result;
        assertThat(call.toolName()).isEqualTo("web_search");
        assertThat(call.args()).isEqualTo("btc price");
    }

    @Test
    void shouldReturnRollbackWhenTentativeAnswerIsActive() {
        // Tentative answer bubble is open with buffered prose that the agent has since
        // decided was reasoning — renderer must emit a rollback update, not a plain
        // tool-call append.
        ctx.setTentativeAnswerActive(true);
        ctx.getTentativeAnswerBuffer().append("Here is what I found so far…");
        AgentStreamEvent event = AgentStreamEvent.toolCall("fetch_url", "{\"url\":\"https://ex.com\"}", 2);

        RenderedUpdate result = renderer.render(event, ctx);

        assertThat(result).isInstanceOf(RenderedUpdate.RollbackAndAppendToolCall.class);
        RenderedUpdate.RollbackAndAppendToolCall rb = (RenderedUpdate.RollbackAndAppendToolCall) result;
        assertThat(rb.toolName()).isEqualTo("fetch_url");
        assertThat(rb.args()).isEqualTo("https://ex.com");
        assertThat(rb.foldedProse()).isEqualTo("Here is what I found so far…");
    }

    @Test
    void shouldReturnObservationResultForSuccessfulToolResult() {
        AgentStreamEvent event = AgentStreamEvent.observation("The price is $50,000", 1);

        RenderedUpdate result = renderer.render(event, ctx);

        assertThat(result).isInstanceOf(RenderedUpdate.AppendObservation.class);
        assertThat(((RenderedUpdate.AppendObservation) result).kind())
                .isEqualTo(RenderedUpdate.ObservationKind.RESULT);
    }

    @Test
    void shouldReturnObservationEmptyWhenContentBlank() {
        AgentStreamEvent event = AgentStreamEvent.observation("", 1);

        RenderedUpdate result = renderer.render(event, ctx);

        assertThat(((RenderedUpdate.AppendObservation) result).kind())
                .isEqualTo(RenderedUpdate.ObservationKind.EMPTY);
    }

    @Test
    void shouldReturnObservationFailedWhenErrorFlagSet() {
        AgentStreamEvent event = AgentStreamEvent.observation("Network timeout", true, 1);

        RenderedUpdate result = renderer.render(event, ctx);

        assertThat(result).isInstanceOf(RenderedUpdate.AppendObservation.class);
        RenderedUpdate.AppendObservation obs = (RenderedUpdate.AppendObservation) result;
        assertThat(obs.kind()).isEqualTo(RenderedUpdate.ObservationKind.FAILED);
        assertThat(obs.errorSummary()).isEqualTo("Network timeout");
    }

    @Test
    void shouldReturnAppendErrorToStatusForError() {
        AgentStreamEvent event = AgentStreamEvent.error("Connection timeout", 2);

        RenderedUpdate result = renderer.render(event, ctx);

        assertThat(result).isInstanceOf(RenderedUpdate.AppendErrorToStatus.class);
        assertThat(((RenderedUpdate.AppendErrorToStatus) result).message()).isEqualTo("Connection timeout");
    }

    @Test
    void shouldFallBackToEmptyArgsWhenToolCallJsonMalformed() {
        AgentStreamEvent event = AgentStreamEvent.toolCall("web_search", "{not json", 1);

        RenderedUpdate result = renderer.render(event, ctx);

        assertThat(result).isInstanceOf(RenderedUpdate.AppendToolCall.class);
        RenderedUpdate.AppendToolCall call = (RenderedUpdate.AppendToolCall) result;
        assertThat(call.toolName()).isEqualTo("web_search");
        assertThat(call.args()).isEmpty();
    }

    @Test
    void shouldParseToolCallWithNullContent() {
        AgentStreamEvent event = new AgentStreamEvent(
                AgentStreamEvent.EventType.TOOL_CALL, null, 1, java.time.Instant.now(), false);

        RenderedUpdate result = renderer.render(event, ctx);

        assertThat(result).isInstanceOf(RenderedUpdate.AppendToolCall.class);
        RenderedUpdate.AppendToolCall call = (RenderedUpdate.AppendToolCall) result;
        assertThat(call.toolName()).isEmpty();
        assertThat(call.args()).isEmpty();
    }

    @Test
    void shouldRenderNoToolOutputSentinelAsEmpty() {
        AgentStreamEvent event = AgentStreamEvent.observation("(no tool output)", false, 1);

        RenderedUpdate result = renderer.render(event, ctx);

        assertThat(result).isInstanceOf(RenderedUpdate.AppendObservation.class);
        assertThat(((RenderedUpdate.AppendObservation) result).kind())
                .isEqualTo(RenderedUpdate.ObservationKind.EMPTY);
    }

    @Test
    void shouldRenderNullContentAsEmpty() {
        AgentStreamEvent event = AgentStreamEvent.observation(null, false, 1);

        RenderedUpdate result = renderer.render(event, ctx);

        assertThat(result).isInstanceOf(RenderedUpdate.AppendObservation.class);
        assertThat(((RenderedUpdate.AppendObservation) result).kind())
                .isEqualTo(RenderedUpdate.ObservationKind.EMPTY);
    }

    @Test
    void shouldRenderBlankContentAsEmpty() {
        AgentStreamEvent event = AgentStreamEvent.observation("   ", false, 1);

        RenderedUpdate result = renderer.render(event, ctx);

        assertThat(result).isInstanceOf(RenderedUpdate.AppendObservation.class);
        assertThat(((RenderedUpdate.AppendObservation) result).kind())
                .isEqualTo(RenderedUpdate.ObservationKind.EMPTY);
    }

    @Test
    void shouldRenderActualContentAsResult() {
        AgentStreamEvent event = AgentStreamEvent.observation("Bitcoin price: $105,000", false, 1);

        RenderedUpdate result = renderer.render(event, ctx);

        assertThat(result).isInstanceOf(RenderedUpdate.AppendObservation.class);
        assertThat(((RenderedUpdate.AppendObservation) result).kind())
                .isEqualTo(RenderedUpdate.ObservationKind.RESULT);
    }
}
