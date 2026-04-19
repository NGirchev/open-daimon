package io.github.ngirchev.opendaimon.ai.springai.agent;

import io.github.ngirchev.opendaimon.common.agent.AgentToolResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies {@link ToolObservationClassifier#classify(AgentToolResult)} recognises the
 * three textual-failure prefixes produced by the project's tool layer and by Spring AI
 * itself. A regression here would cause the Telegram UI to render
 * "📋 Tool result received" instead of "⚠️ Tool failed: …".
 */
class ToolObservationClassifierTest {

    @Test
    void shouldClassifyAsFailedWhenTextStartsWithExceptionOccurredInTool() {
        // Spring AI's DefaultToolCallResultConverter converts an unhandled tool exception
        // into this canonical string and reports success=true. The classifier must still
        // flag it as a failure so the Telegram renderer shows the warning marker.
        String raw = "Exception occurred in tool: web_search (NullPointerException)";

        ToolObservationClassifier.Classification classification =
                ToolObservationClassifier.classify(AgentToolResult.success("web_search", raw));

        assertThat(classification.toolError()).isTrue();
        assertThat(classification.streamContent()).isEqualTo(raw);
        assertThat(classification.observation()).isEqualTo(raw);
    }

    @Test
    void shouldClassifyAsFailedWhenTextStartsWithHttpError() {
        String raw = "HTTP error 403 Forbidden";

        ToolObservationClassifier.Classification classification =
                ToolObservationClassifier.classify(AgentToolResult.success("fetch_url", raw));

        assertThat(classification.toolError()).isTrue();
        assertThat(classification.streamContent()).isEqualTo(raw);
        assertThat(classification.observation()).isEqualTo(raw);
    }

    @Test
    void shouldClassifyAsFailedWhenTextStartsWithErrorPrefix() {
        String raw = "Error: timeout — request exceeded 6s timeout";

        ToolObservationClassifier.Classification classification =
                ToolObservationClassifier.classify(AgentToolResult.success("fetch_url", raw));

        assertThat(classification.toolError()).isTrue();
        assertThat(classification.streamContent()).isEqualTo(raw);
        assertThat(classification.observation()).isEqualTo(raw);
    }

    @Test
    void shouldClassifyAsSuccessWhenResultIsValidJson() {
        // Regression guard: a legitimate tool output (JSON payload, plain text, etc.)
        // must stay classified as success=toolError=false even after the third prefix
        // was added to isTextualToolFailure.
        String raw = "{\"query\":\"cats\",\"hits\":[]}";

        ToolObservationClassifier.Classification classification =
                ToolObservationClassifier.classify(AgentToolResult.success("web_search", raw));

        assertThat(classification.toolError()).isFalse();
        assertThat(classification.streamContent()).isEqualTo(raw);
        assertThat(classification.observation()).isEqualTo(raw);
    }
}
