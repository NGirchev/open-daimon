package io.github.ngirchev.opendaimon.telegram.service;

import io.github.ngirchev.opendaimon.common.agent.AgentStreamEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TelegramAgentStreamRendererTest {

    private TelegramAgentStreamRenderer renderer;

    @BeforeEach
    void setUp() {
        renderer = new TelegramAgentStreamRenderer();
    }

    @Test
    void shouldRenderThinkingEventWithoutContent() {
        AgentStreamEvent event = AgentStreamEvent.thinking(0);

        String html = renderer.render(event);

        assertThat(html).contains("Thinking...");
        assertThat(html).contains("<i>");
    }

    @Test
    void shouldRenderThinkingEventWithReasoningContent() {
        AgentStreamEvent event = AgentStreamEvent.thinking("I need to search for Bitcoin price", 0);

        String html = renderer.render(event);

        assertThat(html).contains("I need to search for Bitcoin price");
        assertThat(html).doesNotContain("Thinking...");
        assertThat(html).contains("<i>");
    }

    @Test
    void shouldRenderToolCallWithNameAndArgs() {
        AgentStreamEvent event = AgentStreamEvent.toolCall("web_search", "bitcoin price", 1);

        String html = renderer.render(event);

        assertThat(html).contains("<code>web_search</code>");
        assertThat(html).contains("bitcoin price");
    }

    @Test
    void shouldRenderToolCallWithoutArgs() {
        AgentStreamEvent event = new AgentStreamEvent(
                AgentStreamEvent.EventType.TOOL_CALL, "web_search", 1, java.time.Instant.now());

        String html = renderer.render(event);

        assertThat(html).contains("<code>web_search</code>");
    }

    @Test
    void shouldRenderObservation() {
        AgentStreamEvent event = AgentStreamEvent.observation("The current price is $50,000", 1);

        String html = renderer.render(event);

        assertThat(html).contains("<blockquote>");
        assertThat(html).contains("The current price is $50,000");
    }

    @Test
    void shouldTruncateLongObservation() {
        String longContent = "A".repeat(600);
        AgentStreamEvent event = AgentStreamEvent.observation(longContent, 1);

        String html = renderer.render(event);

        assertThat(html).contains("...");
        assertThat(html.length()).isLessThan(600);
    }

    @Test
    void shouldRenderError() {
        AgentStreamEvent event = AgentStreamEvent.error("Connection timeout", 2);

        String html = renderer.render(event);

        assertThat(html).contains("<b>Error:</b>");
        assertThat(html).contains("Connection timeout");
    }

    @Test
    void shouldReturnNullForFinalAnswer() {
        AgentStreamEvent event = AgentStreamEvent.finalAnswer("The answer is 42", 3);

        assertThat(renderer.render(event)).isNull();
    }

    @Test
    void shouldReturnNullForMaxIterations() {
        AgentStreamEvent event = AgentStreamEvent.maxIterations("Partial answer", 10);

        assertThat(renderer.render(event)).isNull();
    }

    @Test
    void shouldEscapeHtmlInContent() {
        AgentStreamEvent event = AgentStreamEvent.error("<script>alert('xss')</script>", 0);

        String html = renderer.render(event);

        assertThat(html).doesNotContain("<script>");
        assertThat(html).contains("&lt;script&gt;");
    }

    @Test
    void shouldHandleNullObservationContent() {
        AgentStreamEvent event = AgentStreamEvent.observation(null, 1);

        String html = renderer.render(event);

        assertThat(html).contains("No result");
    }

    @Test
    void shouldHandleNullToolCallContent() {
        AgentStreamEvent event = new AgentStreamEvent(
                AgentStreamEvent.EventType.TOOL_CALL, null, 1, java.time.Instant.now());

        String html = renderer.render(event);

        assertThat(html).contains("Tool call");
    }
}
