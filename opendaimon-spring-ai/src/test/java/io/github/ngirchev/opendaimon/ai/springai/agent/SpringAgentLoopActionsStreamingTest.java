package io.github.ngirchev.opendaimon.ai.springai.agent;

import io.github.ngirchev.opendaimon.bulkhead.service.PriorityRequestExecutor;
import io.github.ngirchev.opendaimon.common.ai.command.AICommand;
import io.github.ngirchev.opendaimon.common.agent.AgentContext;
import io.github.ngirchev.opendaimon.common.agent.AgentStreamEvent;
import io.github.ngirchev.opendaimon.common.agent.AgentStreamEvent.EventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SpringAgentLoopActionsStreamingTest {

    private ChatModel chatModel;
    private SpringAgentLoopActions actions;
    private AgentContext ctx;
    private List<AgentStreamEvent> events;

    @BeforeEach
    void setUp() {
        chatModel = mock(ChatModel.class);
        ToolCallingManager toolCallingManager = mock(ToolCallingManager.class);
        actions = new SpringAgentLoopActions(
                chatModel, toolCallingManager, List.of(), null, Duration.ofSeconds(30));
        ctx = new AgentContext("test task", "conv-1", Map.of(), 5, Set.of());
        events = new ArrayList<>();
        ctx.setStreamSink(events::add);
    }

    @Test
    void shouldEmitPartialAnswerEventsWhenStreamingFinalAnswer() {
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(
                chunk("Hello, "),
                chunk("this is the "),
                chunk("final answer.")
        ));

        actions.think(ctx);

        List<AgentStreamEvent> partials = partialAnswers();
        assertThat(partials).hasSizeGreaterThanOrEqualTo(1);
        String joined = partials.stream().map(AgentStreamEvent::content).reduce("", String::concat);
        assertThat(joined).isEqualTo("Hello, this is the final answer.");
        assertThat(ctx.getCurrentTextResponse()).isEqualTo("Hello, this is the final answer.");
    }

    @Test
    void shouldNotEmitPartialAnswerWhenStreamContainsOnlyToolCallMarkup() {
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(
                chunk("<tool_call>"),
                chunk("<name>web_search</name>"),
                chunk("<arg_key>q</arg_key><arg_value>hi</arg_value>"),
                chunk("</tool_call>")
        ));

        actions.think(ctx);

        assertThat(partialAnswers()).isEmpty();
    }

    @Test
    void shouldNotEmitThinkContentInPartialAnswer() {
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(
                chunk("<think>"),
                chunk("internal reasoning"),
                chunk("</think>"),
                chunk("visible answer")
        ));

        actions.think(ctx);

        String joined = partialAnswers().stream()
                .map(AgentStreamEvent::content)
                .reduce("", String::concat);
        assertThat(joined).isEqualTo("visible answer");
        assertThat(joined).doesNotContain("internal reasoning");
    }

    @Test
    void shouldHandleToolCallTagSplitAcrossStreamChunks() {
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(
                chunk("prefix <to"),
                chunk("ol_call><name>web_search</name>"),
                chunk("<arg_key>q</arg_key><arg_value>x</arg_value>"),
                chunk("</tool_"),
                chunk("call> suffix")
        ));

        actions.think(ctx);

        String joined = partialAnswers().stream()
                .map(AgentStreamEvent::content)
                .reduce("", String::concat);
        assertThat(joined).isEqualTo("prefix  suffix");
    }

    @Test
    void shouldSetErrorMessageWhenStreamIsEmpty() {
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.empty());

        actions.think(ctx);

        assertThat(ctx.getErrorMessage()).isEqualTo("LLM returned an empty stream");
        assertThat(partialAnswers()).isEmpty();
    }

    /**
     * Fix 2 regression guard: some providers (e.g. Ollama) stream cumulative snapshots —
     * every chunk repeats the whole previous text plus the new suffix. Without snapshot
     * normalization the pipeline would feed every snapshot in full to
     * {@link StreamingAnswerFilter#feed}, and the PARTIAL_ANSWER consumer would see
     * {@code "Hello, Hello, this is the Hello, this is the final answer."} — the text
     * repeats on every chunk. After the fix only the true delta reaches the filter and
     * the joined PARTIAL_ANSWER stream reproduces the source text exactly once.
     */
    @Test
    void shouldEmitDeltaPartialAnswerEventsWhenProviderStreamsCumulativeSnapshots() {
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(
                chunk("Hello, "),
                chunk("Hello, this is the "),
                chunk("Hello, this is the final answer.")
        ));

        actions.think(ctx);

        List<AgentStreamEvent> partials = partialAnswers();
        assertThat(partials).isNotEmpty();
        String joined = partials.stream().map(AgentStreamEvent::content).reduce("", String::concat);
        assertThat(joined)
                .as("snapshot stream must emit each character exactly once across PARTIAL_ANSWER events")
                .isEqualTo("Hello, this is the final answer.");
        assertThat(ctx.getCurrentTextResponse()).isEqualTo("Hello, this is the final answer.");
    }

    /**
     * Fix 2 regression guard: when a snapshot-shaped stream repeats {@code <think>…</think>}
     * plus the visible tail on every chunk, the {@link StreamingAnswerFilter}'s tag state
     * machine used to re-open on each snapshot because it re-received the literal
     * {@code <think>} prefix — the visible answer could be swallowed or emitted multiple
     * times. After the fix the filter only ever sees the delta, so the reasoning block
     * leaves state exactly once and the answer surfaces exactly once.
     */
    @Test
    void shouldPreserveNonThinkContentWhenSnapshotStreamContainsEmbeddedThinkTag() {
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(
                chunk("<think>reasoning</think>"),
                chunk("<think>reasoning</think>answer"),
                chunk("<think>reasoning</think>answer tail")
        ));

        actions.think(ctx);

        String joined = partialAnswers().stream()
                .map(AgentStreamEvent::content)
                .reduce("", String::concat);
        assertThat(joined).isEqualTo("answer tail");
        assertThat(joined).doesNotContain("reasoning");
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldRouteFallbackCallThroughPriorityRequestExecutor() throws Exception {
        PriorityRequestExecutor mockExecutor = mock(PriorityRequestExecutor.class);
        when(mockExecutor.executeRequest(anyLong(), any(Callable.class)))
                .thenAnswer(inv -> ((Callable<?>) inv.getArgument(1)).call());

        ChatModel fallbackModel = mock(ChatModel.class);
        ToolCallingManager tcm = mock(ToolCallingManager.class);
        SpringAgentLoopActions actionsWithExecutor = new SpringAgentLoopActions(
                fallbackModel, tcm, List.of(), null, Duration.ofMillis(1),
                null, mockExecutor);

        // stream throws immediately → fallback to call()
        when(fallbackModel.stream(any(Prompt.class)))
                .thenThrow(new RuntimeException("stream unavailable"));
        when(fallbackModel.call(any(Prompt.class)))
                .thenReturn(chunk("fallback answer"));

        AgentContext ctxWithUser = new AgentContext(
                "test task", "conv-1",
                Map.of(AICommand.USER_ID_FIELD, "99"),
                5, Set.of());
        List<AgentStreamEvent> evts = new ArrayList<>();
        ctxWithUser.setStreamSink(evts::add);

        actionsWithExecutor.think(ctxWithUser);

        verify(mockExecutor).executeRequest(anyLong(), any(Callable.class));
    }

    private List<AgentStreamEvent> partialAnswers() {
        return events.stream()
                .filter(e -> e.type() == EventType.PARTIAL_ANSWER)
                .toList();
    }

    private static ChatResponse chunk(String text) {
        AssistantMessage msg = new AssistantMessage(text);
        Generation gen = new Generation(msg);
        return ChatResponse.builder().generations(List.of(gen)).build();
    }
}
