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
        assertThat(html).contains("Query:");
        assertThat(html).contains("bitcoin price");
    }

    @Test
    void shouldRenderFetchUrlToolCallWithVisibleUrl() {
        AgentStreamEvent event = AgentStreamEvent.toolCall(
                "fetch_url",
                "{\"url\":\"https://www.reddit.com/r/rust/comments/1rr42gw/benchmarking_rust_vs_spring_boot_vs_quarkus_for/\"}",
                1
        );

        String html = renderer.render(event);

        assertThat(html).contains("<code>fetch_url</code>");
        assertThat(html).contains("URL:");
        assertThat(html).contains("https://www.reddit.com/r/rust/comments/1rr42gw/benchmarking_rust_vs_spring_boot_vs_quarkus_for/");
        assertThat(html).contains("<a href=");
    }

    @Test
    void shouldRenderFetchUrlToolCallWithMissingUrl() {
        AgentStreamEvent event = AgentStreamEvent.toolCall(
                "fetch_url",
                "{\"url\":null}",
                1
        );

        String html = renderer.render(event);

        assertThat(html).contains("<code>fetch_url</code>");
        assertThat(html).contains("URL: missing");
        assertThat(html).doesNotContain("<a href=");
    }

    @Test
    void shouldRenderWebSearchToolCallWithUrlFromQuery() {
        AgentStreamEvent event = AgentStreamEvent.toolCall(
                "web_search",
                "{\"query\":\"https://www.researchgate.net/publication/399200817_Comparative_performance_analysis\"}",
                1
        );

        String html = renderer.render(event);

        assertThat(html).contains("<code>web_search</code>");
        assertThat(html).contains("Query:");
        assertThat(html).contains("URL:");
        assertThat(html).contains("https://www.researchgate.net/publication/399200817_Comparative_performance_analysis");
        assertThat(html).contains("<a href=");
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
        assertThat(html).contains("Tool result received");
        assertThat(html).doesNotContain("$50,000");
    }

    @Test
    void shouldNotRenderUrlsFromObservationPayload() {
        AgentStreamEvent event = AgentStreamEvent.observation(
                "{\"query\":\"Rust vs Spring Boot\", \"hits\":[{\"url\":\"https://example.com/article\"}]}",
                1
        );

        String html = renderer.render(event);

        assertThat(html).contains("Tool result received");
        assertThat(html).doesNotContain("Links found");
        assertThat(html).doesNotContain("https://example.com/article");
        assertThat(html).doesNotContain("<a href=");
    }

    @Test
    void shouldTruncateLongObservation() {
        String longContent = "A".repeat(600);
        AgentStreamEvent event = AgentStreamEvent.observation(longContent, 1);

        String html = renderer.render(event);

        assertThat(html).contains("Tool result received");
        assertThat(html).doesNotContain("...");
    }

    @Test
    void shouldRenderObservationErrorAsShortFailure() {
        AgentStreamEvent event = AgentStreamEvent.observation(
                "fetch_url failed: HTTP 403 for https://example.com/really/long/path",
                1
        );

        String html = renderer.render(event);

        assertThat(html).contains("Tool failed:");
        assertThat(html).contains("Access denied by site (HTTP 403)");
        assertThat(html).contains("https://example.com/really/long/path");
        assertThat(html).contains("<a href=");
        assertThat(html).doesNotContain("Tool result received");
    }

    @Test
    void shouldRenderMissingUrlFailureAsFriendlyMessage() {
        AgentStreamEvent event = AgentStreamEvent.observation(
                "fetch_url failed: MISSING_URL for (missing url argument)",
                1
        );

        String html = renderer.render(event);

        assertThat(html).contains("Tool failed:");
        assertThat(html).contains("Missing URL argument");
        assertThat(html).contains("URL: missing");
    }

    @Test
    void shouldRenderFriendlyFailureForTooLargeFetchUrlResponse() {
        AgentStreamEvent event = AgentStreamEvent.observation(
                "fetch_url failed: TOO_LARGE for https://example.com/huge",
                1
        );

        String html = renderer.render(event);

        assertThat(html).contains("Tool failed:");
        assertThat(html).contains("Page is too large to parse");
    }

    @Test
    void shouldRenderFriendlyFailureForUnreadable2xxResponse() {
        AgentStreamEvent event = AgentStreamEvent.observation(
                "Error: UNREADABLE_2XX_RESPONSE",
                1
        );

        String html = renderer.render(event);

        assertThat(html).contains("Tool failed:");
        assertThat(html).contains("Site returned HTTP 200, but content could not be extracted");
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
    void shouldReturnNullForFinalAnswerChunk() {
        AgentStreamEvent event = AgentStreamEvent.finalAnswerChunk("The answer is", 3);

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
    void shouldRenderNoResultForNoToolOutputPlaceholder() {
        AgentStreamEvent event = AgentStreamEvent.observation("(no tool output)", 1);

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
