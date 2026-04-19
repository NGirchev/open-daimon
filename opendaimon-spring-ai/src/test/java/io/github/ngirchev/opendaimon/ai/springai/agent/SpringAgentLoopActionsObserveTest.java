package io.github.ngirchev.opendaimon.ai.springai.agent;

import io.github.ngirchev.opendaimon.common.agent.AgentContext;
import io.github.ngirchev.opendaimon.common.agent.AgentStreamEvent;
import io.github.ngirchev.opendaimon.common.agent.AgentStreamEvent.EventType;
import io.github.ngirchev.opendaimon.common.agent.AgentToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.model.tool.ToolCallingManager;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.ai.chat.prompt.Prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
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
                chatModel, toolCallingManager, List.of(), null, Duration.ofSeconds(30));
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
    void shouldPromoteJsonQuotedHttpErrorToFailedObservation() {
        // Spring AI serializes String tool return values as JSON-quoted strings.
        // The raw responseData arriving in observe() looks like: "\"HTTP error 403 FORBIDDEN\""
        // (with surrounding double-quotes), not "HTTP error 403 FORBIDDEN". The unquoting step
        // must strip those outer quotes before the startsWith check so the heuristic fires.
        ctx.setToolResult(AgentToolResult.success(
                "http_get",
                "\"HTTP error 403 FORBIDDEN\""));

        actions.observe(ctx);

        AgentStreamEvent event = events.stream()
                .filter(e -> e.type() == EventType.OBSERVATION)
                .findFirst()
                .orElseThrow();
        assertThat(event.error()).isTrue();
        assertThat(event.content()).startsWith("HTTP error 403 FORBIDDEN");
    }

    @Test
    void shouldPromoteDecodeFailureOn2xxToFailedObservation() {
        // After the WebTools / HttpApiTool 2xx-guard fix, body-decode failures on HTTP 200
        // no longer surface as the absurd "HTTP error 200 OK" marker. Instead they return
        // "Error: fetch_url could not decode response body for <url>" — which falls under
        // the generic "Error: " prefix and must still be classified as FAILED so Telegram
        // renders "⚠️ Tool failed: …" and the agent picks a different URL.
        ctx.setToolResult(AgentToolResult.success(
                "fetch_url",
                "Error: fetch_url could not decode response body for https://hackernoon.com/huge-article"));

        actions.observe(ctx);

        AgentStreamEvent event = events.stream()
                .filter(e -> e.type() == EventType.OBSERVATION)
                .findFirst()
                .orElseThrow();
        assertThat(event.error()).isTrue();
        assertThat(event.content()).startsWith("Error: fetch_url could not decode");
    }

    @Test
    void shouldPromoteJsonQuotedErrorPrefixToFailedObservation() {
        ctx.setToolResult(AgentToolResult.success(
                "fetch_url",
                "\"Error: timeout after 6000 ms\""));

        actions.observe(ctx);

        AgentStreamEvent event = events.stream()
                .filter(e -> e.type() == EventType.OBSERVATION)
                .findFirst()
                .orElseThrow();
        assertThat(event.error()).isTrue();
        assertThat(event.content()).startsWith("Error: timeout");
    }

    @Test
    void shouldNotUnquoteJsonObjectResults() {
        // JSON objects (e.g. SearchResult) start with '{' — must not be mistakenly unquoted
        ctx.setToolResult(AgentToolResult.success(
                "web_search",
                "{\"query\":\"test\",\"hits\":[]}"));

        actions.observe(ctx);

        AgentStreamEvent event = events.stream()
                .filter(e -> e.type() == EventType.OBSERVATION)
                .findFirst()
                .orElseThrow();
        assertThat(event.error()).isFalse();
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

    @Test
    void shouldTruncateToFirstToolCallWhenMultipleReturnedInThink() {
        ChatModel chatModel = mock(ChatModel.class);
        ToolCallingManager toolCallingManager = mock(ToolCallingManager.class);
        SpringAgentLoopActions actionsWithMockModel = new SpringAgentLoopActions(
                chatModel, toolCallingManager, List.of(), null, Duration.ofSeconds(30));

        AssistantMessage.ToolCall call1 = new AssistantMessage.ToolCall(
                "id1", "function", "web_search", "{\"q\":\"test\"}");
        AssistantMessage.ToolCall call2 = new AssistantMessage.ToolCall(
                "id2", "function", "http_get", "{\"url\":\"x\"}");
        AssistantMessage msg = AssistantMessage.builder()
                .toolCalls(List.of(call1, call2))
                .build();
        Generation gen = new Generation(msg);
        ChatResponse response = new ChatResponse(List.of(gen));
        doReturn(Flux.just(response)).when(chatModel).stream(any(Prompt.class));

        AgentContext thinkCtx = new AgentContext("test task", "conv-1", Map.of(), 5, Set.of());
        List<AgentStreamEvent> thinkEvents = new ArrayList<>();
        thinkCtx.setStreamSink(thinkEvents::add);

        actionsWithMockModel.think(thinkCtx);

        ChatResponse stored = thinkCtx.getExtra("spring.lastResponse");
        assertThat(stored).isNotNull();
        assertThat(stored.getResult().getOutput().getToolCalls()).hasSize(1);
        assertThat(stored.getResult().getOutput().getToolCalls().getFirst().name())
                .isEqualTo("web_search");
    }
}
