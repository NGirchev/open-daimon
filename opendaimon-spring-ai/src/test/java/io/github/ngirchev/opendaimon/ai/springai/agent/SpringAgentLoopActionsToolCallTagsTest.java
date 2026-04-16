package io.github.ngirchev.opendaimon.ai.springai.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SpringAgentLoopActionsToolCallTagsTest {

    @Test
    void shouldStripCompleteToolCallBlock() {
        String text = "text before<tool_call><name>web_search</name>"
                + "<arg_key>query</arg_key><arg_value>test</arg_value></tool_call>text after";
        assertThat(SpringAgentLoopActions.stripToolCallTags(text))
                .isEqualTo("text beforetext after");
    }

    @Test
    void shouldStripMultipleToolCallBlocks() {
        String text = "A<tool_call><name>s1</name></tool_call>B<tool_call><name>s2</name></tool_call>C";
        assertThat(SpringAgentLoopActions.stripToolCallTags(text))
                .isEqualTo("ABC");
    }

    @Test
    void shouldStripToolCallBlockWithMultilineContent() {
        String text = "answer\n<tool_call>\n<name>web_search</name>\n"
                + "<arg_key>query</arg_key>\n<arg_value>test</arg_value>\n</tool_call>\nmore";
        assertThat(SpringAgentLoopActions.stripToolCallTags(text))
                .isEqualTo("answer\n\nmore");
    }

    @Test
    void shouldPreserveTextBeforeAndAfterToolCallBlock() {
        String text = "Here is the answer.\n<tool_call><name>search</name></tool_call>\nDone.";
        String result = SpringAgentLoopActions.stripToolCallTags(text);
        assertThat(result).startsWith("Here is the answer.");
        assertThat(result).endsWith("Done.");
    }

    @Test
    void shouldStripOrphanedOpeningToolCallTag() {
        String text = "answer\n<tool_call>\n<name>search</name>\n<arg_key>q</arg_key>";
        assertThat(SpringAgentLoopActions.stripToolCallTags(text))
                .isEqualTo("answer");
    }

    @Test
    void shouldStripOrphanedClosingToolCallTag() {
        String text = "</tool_call>\nactual answer";
        assertThat(SpringAgentLoopActions.stripToolCallTags(text))
                .isEqualTo("actual answer");
    }

    @Test
    void shouldStripLooseArgKeyAndArgValueTags() {
        String text = "text\n<arg_key>query</arg_key>\n<arg_value>test</arg_value>\nmore text";
        assertThat(SpringAgentLoopActions.stripToolCallTags(text))
                .isEqualTo("text\n\n\nmore text");
    }

    @Test
    void shouldStripLooseNameTags() {
        String text = "prefix <name>web_search</name> suffix";
        assertThat(SpringAgentLoopActions.stripToolCallTags(text))
                .isEqualTo("prefix  suffix");
    }

    @Test
    void shouldReturnNullForNullInput() {
        assertThat(SpringAgentLoopActions.stripToolCallTags(null)).isNull();
    }

    @Test
    void shouldReturnTextUnchangedWhenNoToolCallTags() {
        String text = "Just a regular answer with no tool call tags";
        assertThat(SpringAgentLoopActions.stripToolCallTags(text))
                .isEqualTo(text);
    }

    @Test
    void shouldReturnEmptyWhenEntireTextIsToolCallMarkup() {
        String text = "<tool_call><name>web_search</name>"
                + "<arg_key>query</arg_key><arg_value>test</arg_value></tool_call>";
        assertThat(SpringAgentLoopActions.stripToolCallTags(text))
                .isEmpty();
    }

    @Test
    void shouldHandleRealWorldBugReproduction() {
        String text = "Я попытаюсь найти свежие бенчмарки на официальном сайте Quarkus.\n"
                + "web_search\n"
                + "<arg_key>query</arg_key>\n"
                + "<arg_value>Quarkus vs Spring Boot performance benchmarks 2026</arg_value>\n"
                + "</tool_call>";
        String result = SpringAgentLoopActions.stripToolCallTags(text);
        assertThat(result)
                .contains("Я попытаюсь найти свежие бенчмарки")
                .doesNotContain("<arg_key>")
                .doesNotContain("<arg_value>")
                .doesNotContain("</tool_call>");
    }

    @Test
    void shouldNotInterfereWithThinkTags() {
        String text = "<think>reasoning</think>Answer<tool_call><name>s</name></tool_call>";
        assertThat(SpringAgentLoopActions.stripToolCallTags(text))
                .isEqualTo("<think>reasoning</think>Answer");
    }
}
