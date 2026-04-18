package io.github.ngirchev.opendaimon.ai.springai.agent;

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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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
                chatModel, toolCallingManager, List.of(), null);
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
