package io.github.ngirchev.opendaimon.ai.springai.agent;

import io.github.ngirchev.opendaimon.ai.springai.agent.SpringAgentLoopActions.RawToolCall;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for the raw tool call fallback parser in {@link SpringAgentLoopActions}.
 *
 * <p>Covers the scenario where LLM models emit tool calls as XML tags in text
 * instead of using the structured function calling API.
 */
class SpringAgentLoopActionsRawToolCallTest {

    private SpringAgentLoopActions actions;

    @BeforeEach
    void setUp() {
        ToolCallback httpGetCallback = mockToolCallback("http_get");
        ToolCallback webSearchCallback = mockToolCallback("web_search");
        actions = new SpringAgentLoopActions(
                null, null, List.of(httpGetCallback, webSearchCallback), null);
    }

    // --- tryParseRawToolCall: successful parsing ---

    @Nested
    @DisplayName("tryParseRawToolCall — successful parsing")
    class SuccessfulParsing {

        @Test
        @DisplayName("should parse complete <tool_call> block with <name> tag")
        void shouldParseCompleteToolCallBlock() {
            String text = "Some reasoning text.\n"
                    + "<tool_call>\n"
                    + "<name>http_get</name>\n"
                    + "<arg_key>url</arg_key>\n"
                    + "<arg_value>https://example.com</arg_value>\n"
                    + "</tool_call>";

            RawToolCall result = actions.tryParseRawToolCall(text);

            assertThat(result).isNotNull();
            assertThat(result.name()).isEqualTo("http_get");
            assertThat(result.arguments()).isEqualTo("{\"url\":\"https://example.com\"}");
        }

        @Test
        @DisplayName("should parse partial tool call without opening <tool_call> tag")
        void shouldParsePartialToolCallWithoutOpeningTag() {
            String text = "Я получил доступ к двум статьям.\n"
                    + "http_get\n"
                    + "<arg_key>url</arg_key>\n"
                    + "<arg_value>https://example.com/article</arg_value>\n"
                    + "</tool_call>";

            RawToolCall result = actions.tryParseRawToolCall(text);

            assertThat(result).isNotNull();
            assertThat(result.name()).isEqualTo("http_get");
            assertThat(result.arguments()).isEqualTo("{\"url\":\"https://example.com/article\"}");
        }

        @Test
        @DisplayName("should parse multiple argument pairs")
        void shouldParseMultipleArgPairs() {
            String text = "<tool_call>\n"
                    + "<name>http_get</name>\n"
                    + "<arg_key>url</arg_key>\n"
                    + "<arg_value>https://api.example.com</arg_value>\n"
                    + "<arg_key>method</arg_key>\n"
                    + "<arg_value>POST</arg_value>\n"
                    + "</tool_call>";

            RawToolCall result = actions.tryParseRawToolCall(text);

            assertThat(result).isNotNull();
            assertThat(result.name()).isEqualTo("http_get");
            assertThat(result.arguments())
                    .isEqualTo("{\"url\":\"https://api.example.com\",\"method\":\"POST\"}");
        }

        @Test
        @DisplayName("should detect tool name from registered callbacks when no <name> tag")
        void shouldDetectToolNameFromRegisteredCallbacks() {
            String text = "Let me search for that.\n"
                    + "web_search\n"
                    + "<arg_key>query</arg_key>\n"
                    + "<arg_value>Quarkus benchmarks 2026</arg_value>";

            RawToolCall result = actions.tryParseRawToolCall(text);

            assertThat(result).isNotNull();
            assertThat(result.name()).isEqualTo("web_search");
        }

        @Test
        @DisplayName("should handle inline arg tags without newlines")
        void shouldHandleInlineArgTags() {
            String text = "<tool_call><name>http_get</name>"
                    + "<arg_key>url</arg_key><arg_value>https://example.com</arg_value>"
                    + "</tool_call>";

            RawToolCall result = actions.tryParseRawToolCall(text);

            assertThat(result).isNotNull();
            assertThat(result.name()).isEqualTo("http_get");
            assertThat(result.arguments()).isEqualTo("{\"url\":\"https://example.com\"}");
        }

        @Test
        @DisplayName("should prefer <name> tag over registered tool name matching")
        void shouldPreferNameTagOverRegisteredToolName() {
            String text = "I will use web_search but actually calling http_get.\n"
                    + "<name>http_get</name>\n"
                    + "<arg_key>url</arg_key>\n"
                    + "<arg_value>https://example.com</arg_value>";

            RawToolCall result = actions.tryParseRawToolCall(text);

            assertThat(result).isNotNull();
            assertThat(result.name()).isEqualTo("http_get");
        }
    }

    // --- tryParseRawToolCall: should return null ---

    @Nested
    @DisplayName("tryParseRawToolCall — should return null")
    class ShouldReturnNull {

        @Test
        @DisplayName("should return null for null input")
        void shouldReturnNullForNullInput() {
            assertThat(actions.tryParseRawToolCall(null)).isNull();
        }

        @Test
        @DisplayName("should return null for plain text without arg tags")
        void shouldReturnNullForPlainText() {
            String text = "This is a regular answer with no tool call markup.";
            assertThat(actions.tryParseRawToolCall(text)).isNull();
        }

        @Test
        @DisplayName("should return null when arg tags present but no registered tool name found")
        void shouldReturnNullWhenNoToolNameFound() {
            String text = "<arg_key>param</arg_key><arg_value>value</arg_value>";
            assertThat(actions.tryParseRawToolCall(text)).isNull();
        }

        @Test
        @DisplayName("should return null when <name> tag references unregistered tool")
        void shouldReturnNullWhenToolNotRegistered() {
            String text = "<name>unknown_tool</name>\n"
                    + "<arg_key>key</arg_key><arg_value>value</arg_value>";
            assertThat(actions.tryParseRawToolCall(text)).isNull();
        }

        @Test
        @DisplayName("should return null for text with tool name but no arg pairs")
        void shouldReturnNullWhenNoArgPairs() {
            String text = "I want to call http_get but forgot the arguments.";
            assertThat(actions.tryParseRawToolCall(text)).isNull();
        }
    }

    // --- tryParseRawToolCall: real-world reproduction ---

    @Nested
    @DisplayName("tryParseRawToolCall — real-world cases")
    class RealWorldCases {

        @Test
        @DisplayName("should parse the exact bug-triggering output from user report")
        void shouldParseExactBugTriggeringOutput() {
            String text = "Я получил доступ к двум статьям с полезной информацией. "
                    + "Теперь у меня есть достаточно данных, чтобы дать полноценный ответ "
                    + "о сравнении производительности Quarkus и Spring Boot в 2026 году "
                    + "с конкретными цифрами из последних исследований.\n"
                    + "http_get\n"
                    + "<arg_key>url</arg_key>\n"
                    + "<arg_value>https://azeynalli1990.medium.com/quarkus-versus-spring-full-comparison-f294803332d0</arg_value>\n"
                    + "</tool_call>";

            RawToolCall result = actions.tryParseRawToolCall(text);

            assertThat(result).isNotNull();
            assertThat(result.name()).isEqualTo("http_get");
            assertThat(result.arguments()).contains("azeynalli1990.medium.com");
        }

        @Test
        @DisplayName("should parse web_search with Cyrillic reasoning prefix")
        void shouldParseWebSearchWithCyrillicPrefix() {
            String text = "Я попытаюсь найти свежие бенчмарки на официальном сайте Quarkus.\n"
                    + "web_search\n"
                    + "<arg_key>query</arg_key>\n"
                    + "<arg_value>Quarkus vs Spring Boot performance benchmarks 2026</arg_value>\n"
                    + "</tool_call>";

            RawToolCall result = actions.tryParseRawToolCall(text);

            assertThat(result).isNotNull();
            assertThat(result.name()).isEqualTo("web_search");
            assertThat(result.arguments()).contains("Quarkus vs Spring Boot");
        }
    }

    // --- escapeJson ---

    @Nested
    @DisplayName("escapeJson")
    class EscapeJsonTests {

        @Test
        void shouldEscapeQuotes() {
            assertThat(SpringAgentLoopActions.escapeJson("say \"hello\""))
                    .isEqualTo("say \\\"hello\\\"");
        }

        @Test
        void shouldEscapeBackslashes() {
            assertThat(SpringAgentLoopActions.escapeJson("path\\to\\file"))
                    .isEqualTo("path\\\\to\\\\file");
        }

        @Test
        void shouldEscapeNewlinesAndTabs() {
            assertThat(SpringAgentLoopActions.escapeJson("line1\nline2\ttab"))
                    .isEqualTo("line1\\nline2\\ttab");
        }

        @Test
        void shouldReturnEmptyForNull() {
            assertThat(SpringAgentLoopActions.escapeJson(null)).isEmpty();
        }

        @Test
        void shouldLeaveCleanStringUnchanged() {
            assertThat(SpringAgentLoopActions.escapeJson("https://example.com/path?q=test"))
                    .isEqualTo("https://example.com/path?q=test");
        }
    }

    // --- Helpers ---

    private static ToolCallback mockToolCallback(String name) {
        ToolCallback callback = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn(name);
        when(callback.getToolDefinition()).thenReturn(definition);
        return callback;
    }
}
