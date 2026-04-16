package io.github.ngirchev.opendaimon.ai.springai.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SpringAgentLoopActionsThinkTagsTest {

    @Test
    void shouldExtractThinkingContent() {
        String text = "<think>I need to search the web for this</think>Here is the answer";
        assertThat(SpringAgentLoopActions.extractThinkTags(text))
                .isEqualTo("I need to search the web for this");
    }

    @Test
    void shouldReturnNullWhenNoThinkTags() {
        assertThat(SpringAgentLoopActions.extractThinkTags("Just a regular answer")).isNull();
    }

    @Test
    void shouldReturnNullForNullInput() {
        assertThat(SpringAgentLoopActions.extractThinkTags(null)).isNull();
    }

    @Test
    void shouldReturnNullForEmptyThinkTags() {
        assertThat(SpringAgentLoopActions.extractThinkTags("<think></think>Answer")).isNull();
    }

    @Test
    void shouldReturnNullForBlankThinkTags() {
        assertThat(SpringAgentLoopActions.extractThinkTags("<think>   </think>Answer")).isNull();
    }

    @Test
    void shouldStripThinkTagsFromText() {
        String text = "<think>reasoning here</think>The actual answer";
        assertThat(SpringAgentLoopActions.stripThinkTags(text))
                .isEqualTo("The actual answer");
    }

    @Test
    void shouldReturnTextUnchangedWhenNoThinkTags() {
        assertThat(SpringAgentLoopActions.stripThinkTags("Just a regular answer"))
                .isEqualTo("Just a regular answer");
    }

    @Test
    void shouldReturnNullForNullStripInput() {
        assertThat(SpringAgentLoopActions.stripThinkTags(null)).isNull();
    }

    @Test
    void shouldHandleThinkTagsAtEnd() {
        String text = "Answer first<think>thinking after</think>";
        assertThat(SpringAgentLoopActions.stripThinkTags(text)).isEqualTo("Answer first");
    }

    @Test
    void shouldHandleMultilineThinking() {
        String text = "<think>\nStep 1: Search\nStep 2: Analyze\n</think>\nHere is what I found";
        assertThat(SpringAgentLoopActions.extractThinkTags(text))
                .contains("Step 1: Search")
                .contains("Step 2: Analyze");
        assertThat(SpringAgentLoopActions.stripThinkTags(text))
                .isEqualTo("Here is what I found");
    }

    @Test
    void shouldSanitizeFinalAnswerByRemovingThinkTagsAndTrimming() {
        String text = "  <think>internal reasoning</think>\nFinal answer for user.  ";
        assertThat(SpringAgentLoopActions.sanitizeFinalAnswerText(text))
                .isEqualTo("Final answer for user.");
    }

    @Test
    void shouldDetectRawToolPayloadXmlTags() {
        String text = """
                Я получил доступ к двум статьям.
                <tool_call>
                <tool_name>http_get</tool_name>
                <arg_key>url</arg_key>
                <arg_value>https://example.com</arg_value>
                </tool_call>
                """;
        assertThat(SpringAgentLoopActions.containsToolPayloadMarkers(text)).isTrue();
    }

    @Test
    void shouldDetectStandaloneToolNameLine() {
        String text = """
                I will fetch this now.
                http_get
                url=https://example.com
                """;
        assertThat(SpringAgentLoopActions.containsToolPayloadMarkers(text)).isTrue();
    }

    @Test
    void shouldNotDetectToolPayloadForNormalAnswer() {
        String text = "Quarkus startup is faster, but Spring Boot has broader ecosystem support.";
        assertThat(SpringAgentLoopActions.containsToolPayloadMarkers(text)).isFalse();
    }

    @Test
    void shouldRecoverToolCallFromMixedPayloadWithArgTags() {
        String text = """
                Я получил доступ к двум статьям с полезной информацией.
                http_get
                <arg_key>url</arg_key>
                <arg_value>https://azeynalli1990.medium.com/quarkus-versus-spring-full-comparison-f294803332d0</arg_value>
                </tool_call>
                """;

        SpringAgentLoopActions.RecoveredToolCall recovered = SpringAgentLoopActions.recoverToolCallFromText(text);

        assertThat(recovered).isNotNull();
        assertThat(recovered.toolName()).isEqualTo("http_get");
        assertThat(recovered.argumentsJson())
                .contains("\"url\"")
                .contains("https://azeynalli1990.medium.com/quarkus-versus-spring-full-comparison-f294803332d0");
        assertThat(recovered.leadingText()).isEqualTo("Я получил доступ к двум статьям с полезной информацией.");
    }

    @Test
    void shouldFallbackToUnknownToolWhenMarkersExistWithoutToolName() {
        String text = """
                Подготовил промежуточный результат.
                <arg_key>url</arg_key>
                <arg_value>https://example.com</arg_value>
                </tool_call>
                """;

        SpringAgentLoopActions.RecoveredToolCall recovered = SpringAgentLoopActions.recoverToolCallFromText(text);

        assertThat(recovered).isNotNull();
        assertThat(recovered.toolName()).isEqualTo("unknown_tool");
        assertThat(recovered.argumentsJson()).contains("\"url\":\"https://example.com\"");
        assertThat(recovered.leadingText()).isEqualTo("Подготовил промежуточный результат.");
    }

    @Test
    void shouldExtractUserTextBeforeFirstToolPayloadMarker() {
        String text = """
                Prefix answer text.
                web_search
                <arg_key>query</arg_key>
                <arg_value>benchmark</arg_value>
                """;

        assertThat(SpringAgentLoopActions.extractUserTextBeforeToolPayload(text))
                .isEqualTo("Prefix answer text.");
        assertThat(SpringAgentLoopActions.findFirstToolPayloadIndex(text)).isGreaterThanOrEqualTo(0);
    }
}
