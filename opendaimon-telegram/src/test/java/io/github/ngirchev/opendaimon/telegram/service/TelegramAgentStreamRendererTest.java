package io.github.ngirchev.opendaimon.telegram.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ngirchev.opendaimon.common.agent.AgentStreamEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TelegramAgentStreamRendererTest {

    private TelegramAgentStreamRenderer renderer;

    @BeforeEach
    void setUp() {
        renderer = new TelegramAgentStreamRenderer(new ObjectMapper());
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
    void shouldRenderToolCallWithFriendlyLabelAndQuery() {
        AgentStreamEvent event = AgentStreamEvent.toolCall("web_search", "{\"query\":\"bitcoin price\"}", 1);

        String html = renderer.render(event);

        assertThat(html).contains("Searching the web:");
        assertThat(html).contains("bitcoin price");
        assertThat(html).doesNotContain("<code>");
    }

    @Test
    void shouldRenderToolCallWithoutArgs() {
        AgentStreamEvent event = new AgentStreamEvent(
                AgentStreamEvent.EventType.TOOL_CALL, "web_search", 1, java.time.Instant.now());

        String html = renderer.render(event);

        assertThat(html).contains("Searching the web...");
    }

    @Test
    void shouldRenderObservationAsCompactDone() {
        AgentStreamEvent event = AgentStreamEvent.observation("The current price is $50,000", 1);

        String html = renderer.render(event);

        assertThat(html).contains("Done");
        assertThat(html).doesNotContain("The current price");
        assertThat(html).doesNotContain("<blockquote>");
    }

    @Test
    void shouldRenderCompactObservationForLongContent() {
        String longContent = "A".repeat(600);
        AgentStreamEvent event = AgentStreamEvent.observation(longContent, 1);

        String html = renderer.render(event);

        assertThat(html).contains("Done");
        assertThat(html).doesNotContain("AAA");
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

        assertThat(html).contains("Done");
    }

    @Test
    void shouldHandleNullToolCallContent() {
        AgentStreamEvent event = new AgentStreamEvent(
                AgentStreamEvent.EventType.TOOL_CALL, null, 1, java.time.Instant.now());

        String html = renderer.render(event);

        assertThat(html).contains("Using a tool...");
    }

    @Test
    void shouldRenderDefaultLabelForUnknownTool() {
        AgentStreamEvent event = AgentStreamEvent.toolCall("run", "{}", 1);

        String html = renderer.render(event);

        assertThat(html).contains("Using a tool...");
        assertThat(html).doesNotContain("run");
    }

    @Test
    void shouldRenderFriendlyLabelAndUrlForFetchUrl() {
        AgentStreamEvent event = AgentStreamEvent.toolCall("fetch_url", "{\"url\":\"https://example.com\"}", 1);

        String html = renderer.render(event);

        assertThat(html).contains("Reading a web page:");
        assertThat(html).contains("https://example.com");
    }

    @Test
    void shouldRenderFriendlyLabelAndUrlForHttpGet() {
        AgentStreamEvent event = AgentStreamEvent.toolCall("http_get", "{\"url\":\"https://api.example.com\"}", 1);

        String html = renderer.render(event);

        assertThat(html).contains("Making an HTTP request:");
        assertThat(html).contains("https://api.example.com");
    }

    @Test
    void shouldRenderFriendlyLabelAndUrlForHttpPost() {
        AgentStreamEvent event = AgentStreamEvent.toolCall(
                "http_post", "{\"url\":\"https://api.x\",\"body\":\"payload\"}", 1);

        String html = renderer.render(event);

        assertThat(html).contains("Sending an HTTP request:");
        assertThat(html).contains("https://api.x");
        assertThat(html).doesNotContain("payload");
    }

    @Test
    void shouldFallBackToLabelOnlyWhenArgsJsonMalformed() {
        AgentStreamEvent event = AgentStreamEvent.toolCall("web_search", "{not json", 1);

        String html = renderer.render(event);

        assertThat(html).contains("Searching the web...");
        assertThat(html).doesNotContain("not json");
    }

    @Test
    void shouldFallBackToLabelOnlyWhenArgKeyMissing() {
        AgentStreamEvent event = AgentStreamEvent.toolCall("web_search", "{\"foo\":\"bar\"}", 1);

        String html = renderer.render(event);

        assertThat(html).contains("Searching the web...");
        assertThat(html).doesNotContain("bar");
    }

    @Test
    void shouldFallBackToLabelOnlyWhenUrlBlank() {
        AgentStreamEvent event = AgentStreamEvent.toolCall("fetch_url", "{\"url\":\"\"}", 1);

        String html = renderer.render(event);

        assertThat(html).contains("Reading a web page...");
    }

    @Test
    void shouldEscapeHtmlInToolCallArgument() {
        AgentStreamEvent event = AgentStreamEvent.toolCall(
                "fetch_url", "{\"url\":\"https://x/<script>\"}", 1);

        String html = renderer.render(event);

        assertThat(html).contains("&lt;script&gt;");
        assertThat(html).doesNotContain("<script>");
    }

    @Test
    void shouldTruncateVeryLongToolCallArgument() {
        String longUrl = "https://example.com/" + "a".repeat(300);
        AgentStreamEvent event = AgentStreamEvent.toolCall(
                "fetch_url", "{\"url\":\"" + longUrl + "\"}", 1);

        String html = renderer.render(event);

        assertThat(html).contains("Reading a web page:");
        assertThat(html).doesNotContain(longUrl);
        assertThat(html).endsWith("...");
    }
}
