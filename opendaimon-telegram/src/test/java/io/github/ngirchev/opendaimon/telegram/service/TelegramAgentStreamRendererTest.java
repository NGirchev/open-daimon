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
    void shouldReturnNullForThinkingWithoutContent() {
        AgentStreamEvent event = AgentStreamEvent.thinking(0);

        // Placeholder thinking frames are intentionally dropped — the unified transcript
        // already shows streaming reasoning text via PARTIAL_ANSWER.
        assertThat(renderer.render(event)).isNull();
    }

    @Test
    void shouldReturnNullForThinkingWithContent() {
        // Structured reasoning (from provider metadata) duplicates what already
        // streamed via PARTIAL_ANSWER in the visible assistant text — skipped.
        AgentStreamEvent event = AgentStreamEvent.thinking("I need to search for Bitcoin price", 0);

        assertThat(renderer.render(event)).isNull();
    }

    @Test
    void shouldRenderPartialAnswerAsRawContent() {
        AgentStreamEvent event = AgentStreamEvent.partialAnswer("Hello, world!", 1);

        assertThat(renderer.render(event)).isEqualTo("Hello, world!");
    }

    @Test
    void shouldRenderToolCallWithFriendlyLabelAndQuery() {
        AgentStreamEvent event = AgentStreamEvent.toolCall("web_search", "{\"query\":\"bitcoin price\"}", 1);

        String markdown = renderer.render(event);

        assertThat(markdown).contains("🔧");
        assertThat(markdown).contains("Searching the web:");
        assertThat(markdown).contains("bitcoin price");
        // Markers are fenced by blank lines so the transcript has clean paragraph breaks.
        assertThat(markdown).startsWith("\n\n");
        assertThat(markdown).endsWith("\n\n");
    }

    @Test
    void shouldRenderToolCallWithoutArgs() {
        AgentStreamEvent event = new AgentStreamEvent(
                AgentStreamEvent.EventType.TOOL_CALL, "web_search", 1, java.time.Instant.now());

        String markdown = renderer.render(event);

        assertThat(markdown).contains("Searching the web");
        assertThat(markdown).contains("…");
    }

    @Test
    void shouldRenderObservationAsCompactDoneMarker() {
        AgentStreamEvent event = AgentStreamEvent.observation("The current price is $50,000", 1);

        String markdown = renderer.render(event);

        assertThat(markdown).contains("✅");
        assertThat(markdown).contains("done");
        // Tool observation payload never leaks into the user transcript.
        assertThat(markdown).doesNotContain("The current price");
    }

    @Test
    void shouldRenderObservationMarkerIgnoringLongContent() {
        String longContent = "A".repeat(600);
        AgentStreamEvent event = AgentStreamEvent.observation(longContent, 1);

        String markdown = renderer.render(event);

        assertThat(markdown).contains("done");
        assertThat(markdown).doesNotContain("AAA");
    }

    @Test
    void shouldRenderErrorInline() {
        AgentStreamEvent event = AgentStreamEvent.error("Connection timeout", 2);

        String markdown = renderer.render(event);

        assertThat(markdown).contains("❌");
        assertThat(markdown).contains("Error:");
        assertThat(markdown).contains("Connection timeout");
    }

    @Test
    void shouldReturnNullForFinalAnswer() {
        // FINAL_ANSWER content has already flowed through the transcript via PARTIAL_ANSWER
        // chunks — the terminal event is used only to populate responseText for persistence.
        AgentStreamEvent event = AgentStreamEvent.finalAnswer("The answer is 42", 3);

        assertThat(renderer.render(event)).isNull();
    }

    @Test
    void shouldRenderMaxIterationsAsWarningMarker() {
        AgentStreamEvent event = AgentStreamEvent.maxIterations("Partial answer so far", 10);

        String markdown = renderer.render(event);

        assertThat(markdown).contains("⚠️");
        assertThat(markdown).contains("reached iteration limit");
        // The event's content duplicates what already streamed via PARTIAL_ANSWER —
        // don't leak the full answer text into the trailing marker.
        assertThat(markdown).doesNotContain("Partial answer so far");
        // Marker is fenced by blank lines so it stands as its own paragraph.
        assertThat(markdown).startsWith("\n\n");
        assertThat(markdown).endsWith("\n\n");
    }

    @Test
    void shouldReturnNullForMetadata() {
        AgentStreamEvent event = AgentStreamEvent.metadata("gpt-4o", 1);

        assertThat(renderer.render(event)).isNull();
    }

    @Test
    void shouldHandleNullObservationContent() {
        AgentStreamEvent event = AgentStreamEvent.observation(null, 1);

        String markdown = renderer.render(event);

        assertThat(markdown).contains("done");
    }

    @Test
    void shouldHandleNullToolCallContent() {
        AgentStreamEvent event = new AgentStreamEvent(
                AgentStreamEvent.EventType.TOOL_CALL, null, 1, java.time.Instant.now());

        String markdown = renderer.render(event);

        assertThat(markdown).contains("Using a tool");
    }

    @Test
    void shouldRenderDefaultLabelForUnknownTool() {
        AgentStreamEvent event = AgentStreamEvent.toolCall("run", "{}", 1);

        String markdown = renderer.render(event);

        assertThat(markdown).contains("Using a tool");
        assertThat(markdown).doesNotContain(": run");
    }

    @Test
    void shouldRenderFriendlyLabelAndUrlForFetchUrl() {
        AgentStreamEvent event = AgentStreamEvent.toolCall("fetch_url", "{\"url\":\"https://example.com\"}", 1);

        String markdown = renderer.render(event);

        assertThat(markdown).contains("Reading a web page:");
        assertThat(markdown).contains("https://example.com");
    }

    @Test
    void shouldRenderFriendlyLabelAndUrlForHttpGet() {
        AgentStreamEvent event = AgentStreamEvent.toolCall("http_get", "{\"url\":\"https://api.example.com\"}", 1);

        String markdown = renderer.render(event);

        assertThat(markdown).contains("Making an HTTP request:");
        assertThat(markdown).contains("https://api.example.com");
    }

    @Test
    void shouldRenderFriendlyLabelAndUrlForHttpPost() {
        AgentStreamEvent event = AgentStreamEvent.toolCall(
                "http_post", "{\"url\":\"https://api.x\",\"body\":\"payload\"}", 1);

        String markdown = renderer.render(event);

        assertThat(markdown).contains("Sending an HTTP request:");
        assertThat(markdown).contains("https://api.x");
        // Only the pre-configured key for each tool is surfaced — body is dropped.
        assertThat(markdown).doesNotContain("payload");
    }

    @Test
    void shouldFallBackToLabelOnlyWhenArgsJsonMalformed() {
        AgentStreamEvent event = AgentStreamEvent.toolCall("web_search", "{not json", 1);

        String markdown = renderer.render(event);

        assertThat(markdown).contains("Searching the web");
        assertThat(markdown).doesNotContain("not json");
    }

    @Test
    void shouldFallBackToLabelOnlyWhenArgKeyMissing() {
        AgentStreamEvent event = AgentStreamEvent.toolCall("web_search", "{\"foo\":\"bar\"}", 1);

        String markdown = renderer.render(event);

        assertThat(markdown).contains("Searching the web");
        assertThat(markdown).doesNotContain("bar");
    }

    @Test
    void shouldFallBackToLabelOnlyWhenUrlBlank() {
        AgentStreamEvent event = AgentStreamEvent.toolCall("fetch_url", "{\"url\":\"\"}", 1);

        String markdown = renderer.render(event);

        assertThat(markdown).contains("Reading a web page");
    }

    @Test
    void shouldTruncateVeryLongToolCallArgument() {
        String longUrl = "https://example.com/" + "a".repeat(300);
        AgentStreamEvent event = AgentStreamEvent.toolCall(
                "fetch_url", "{\"url\":\"" + longUrl + "\"}", 1);

        String markdown = renderer.render(event);

        assertThat(markdown).contains("Reading a web page:");
        assertThat(markdown).doesNotContain(longUrl);
        assertThat(markdown).contains("…");
    }
}
