package io.github.ngirchev.opendaimon.ai.springai.agent;

import io.github.ngirchev.opendaimon.common.agent.AgentContext;
import io.github.ngirchev.opendaimon.common.agent.AgentStreamEvent;
import io.github.ngirchev.opendaimon.common.agent.AgentStreamEvent.EventType;
import io.github.ngirchev.opendaimon.common.agent.AgentToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Verifies the textual-failure heuristic in {@link SpringAgentLoopActions#observe(AgentContext)}.
 * Built-in Spring AI {@code @Tool} implementations (HttpApiTool, WebTools) return HTTP failures
 * as a non-exceptional {@link String} — {@code toolResult.success()} stays true. The Telegram
 * layer would then render "📋 Tool result received" even for 403 pages, contradicting the spec
 * that mandates "⚠️ Tool failed: …" on failure.
 */
class SpringAgentLoopActionsObserveTest {

    private SpringAgentLoopActions actions;
    private AgentContext ctx;
    private List<AgentStreamEvent> events;

    @BeforeEach
    void setUp() {
        ChatModel chatModel = mock(ChatModel.class);
        ToolCallingManager toolCallingManager = mock(ToolCallingManager.class);
        actions = new SpringAgentLoopActions(
                chatModel, toolCallingManager, List.of(), null, null, null);
        ctx = new AgentContext("test task", "conv-1", Map.of(), 5, Set.of());
        events = new ArrayList<>();
        ctx.setStreamSink(events::add);
    }

    @Test
    void shouldPromoteHttpErrorResultToFailedObservation() {
        ctx.setToolResult(AgentToolResult.success(
                "http_get",
                "HTTP error 403 FORBIDDEN: <html>…</html>"));

        actions.observe(ctx);

        AgentStreamEvent event = events.stream()
                .filter(e -> e.type() == EventType.OBSERVATION)
                .findFirst()
                .orElseThrow();
        assertThat(event.error()).isTrue();
        assertThat(event.content()).startsWith("HTTP error 403 FORBIDDEN");
    }

    @Test
    void shouldPromoteErrorPrefixedResultToFailedObservation() {
        ctx.setToolResult(AgentToolResult.success("http_get", "Error: connection refused"));

        actions.observe(ctx);

        AgentStreamEvent event = events.stream()
                .filter(e -> e.type() == EventType.OBSERVATION)
                .findFirst()
                .orElseThrow();
        assertThat(event.error()).isTrue();
        assertThat(event.content()).isEqualTo("Error: connection refused");
    }

    @Test
    void shouldKeepRegularResultAsSuccessfulObservation() {
        ctx.setToolResult(AgentToolResult.success("web_search", "Found 3 relevant hits"));

        actions.observe(ctx);

        AgentStreamEvent event = events.stream()
                .filter(e -> e.type() == EventType.OBSERVATION)
                .findFirst()
                .orElseThrow();
        assertThat(event.error()).isFalse();
        assertThat(event.content()).isEqualTo("Found 3 relevant hits");
    }

    @Test
    void shouldTruncateVeryLongHttpErrorSummary() {
        String bigBody = "HTTP error 403 FORBIDDEN: " + "x".repeat(5000);
        ctx.setToolResult(AgentToolResult.success("http_get", bigBody));

        actions.observe(ctx);

        AgentStreamEvent event = events.stream()
                .filter(e -> e.type() == EventType.OBSERVATION)
                .findFirst()
                .orElseThrow();
        assertThat(event.error()).isTrue();
        assertThat(event.content()).hasSizeLessThanOrEqualTo(201);
    }
}
