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

    // --- Unclosed inner tag tests ---

    @Test
    void shouldStripUnclosedArgValueTag() {
        String text = "some answer\n<arg_value>https://example.com/page";
        assertThat(SpringAgentLoopActions.stripToolCallTags(text))
                .isEqualTo("some answer");
    }

    @Test
    void shouldStripUnclosedNameTag() {
        String text = "answer text\n<name>web_search";
        assertThat(SpringAgentLoopActions.stripToolCallTags(text))
                .isEqualTo("answer text");
    }

    @Test
    void shouldStripUnclosedArgKeyTag() {
        String text = "response here\n<arg_key>query";
        assertThat(SpringAgentLoopActions.stripToolCallTags(text))
                .isEqualTo("response here");
    }

    @Test
    void shouldStripMultipleUnclosedInnerTags() {
        String text = "answer\n<arg_key>url\n<arg_value>https://example.com";
        assertThat(SpringAgentLoopActions.stripToolCallTags(text))
                .isEqualTo("answer");
    }

    @Test
    void shouldStripMixOfClosedAndUnclosedInnerTags() {
        String text = "text\n<arg_key>query</arg_key>\n<arg_value>some value without closing";
        String result = SpringAgentLoopActions.stripToolCallTags(text);
        assertThat(result)
                .doesNotContain("<arg_key>")
                .doesNotContain("<arg_value>")
                .startsWith("text");
    }

    @Test
    void shouldHandleEmptyUnclosedArgValueTag() {
        String text = "answer\n<arg_value>";
        assertThat(SpringAgentLoopActions.stripToolCallTags(text))
                .isEqualTo("answer");
    }

    @Test
    void shouldStripArgValueWithUrlAndNoCloseTag() {
        String text = "Here is info.\n<arg_value>https://quarkus.io/blog/new-benchmarks/";
        assertThat(SpringAgentLoopActions.stripToolCallTags(text))
                .isEqualTo("Here is info.");
    }

    // --- Bare tool name tests ---

    @Test
    void shouldStripBareToolNameOnOwnLine() {
        String text = "some answer\nhttp_get\n\nmore text";
        assertThat(SpringAgentLoopActions.stripToolCallTags(text))
                .doesNotContain("http_get");
    }

    @Test
    void shouldNotStripWordWithoutUnderscoreOnOwnLine() {
        String text = "Hello\nQuarkus\nDone";
        assertThat(SpringAgentLoopActions.stripToolCallTags(text))
                .isEqualTo("Hello\nQuarkus\nDone");
    }

    @Test
    void shouldNotStripToolNameEmbeddedInSentence() {
        String text = "I used http_get to fetch the data";
        assertThat(SpringAgentLoopActions.stripToolCallTags(text))
                .isEqualTo("I used http_get to fetch the data");
    }

    @Test
    void shouldStripMultipleBareToolNamesOnSeparateLines() {
        String text = "result\nhttp_get\nweb_search\nfetch_url\nend";
        String result = SpringAgentLoopActions.stripToolCallTags(text);
        assertThat(result)
                .doesNotContain("http_get")
                .doesNotContain("web_search")
                .doesNotContain("fetch_url")
                .contains("result")
                .contains("end");
    }

    @Test
    void shouldPreserveLegitimateTextWithUnderscores() {
        String text = "Use snake_case naming convention in your code";
        assertThat(SpringAgentLoopActions.stripToolCallTags(text))
                .isEqualTo("Use snake_case naming convention in your code");
    }

    // --- Combined real-world scenarios ---

    @Test
    void shouldStripExactBugFromLogs() {
        // Exact reproduction of the bug from opendaimon.log iteration 6
        String text = "Я собрал достаточно информации из различных источников "
                + "о производительности Quarkus и Spring Boot в 2026 году. "
                + "Теперь я могу предоставить вам сравнительный анализ с конкретными цифрами.\n"
                + "http_get\n"
                + "<arg_key>url</arg_key>\n"
                + "<arg_value>https://quarkus.io/blog/new-benchmarks/\n"
                + "</tool_call>";
        String result = SpringAgentLoopActions.stripToolCallTags(text);
        assertThat(result)
                .contains("Я собрал достаточно информации")
                .contains("конкретными цифрами")
                .doesNotContain("http_get")
                .doesNotContain("<arg_key>")
                .doesNotContain("<arg_value>")
                .doesNotContain("</tool_call>")
                .doesNotContain("quarkus.io");
    }

    @Test
    void shouldStripUnclosedArgValueFollowedByToolCallClose() {
        // Model skips </arg_value> and goes straight to </tool_call>
        String text = "answer\n<arg_value>some content\n</tool_call>";
        String result = SpringAgentLoopActions.stripToolCallTags(text);
        assertThat(result)
                .isEqualTo("answer")
                .doesNotContain("<arg_value>")
                .doesNotContain("</tool_call>");
    }

    @Test
    void shouldHandleBareToolNameWithUnclosedTagsAndOrphanedClose() {
        String text = "Let me search.\nweb_search\n<arg_key>query</arg_key>\n"
                + "<arg_value>test query\n</tool_call>";
        String result = SpringAgentLoopActions.stripToolCallTags(text);
        assertThat(result)
                .startsWith("Let me search.")
                .doesNotContain("web_search")
                .doesNotContain("<arg_key>")
                .doesNotContain("<arg_value>")
                .doesNotContain("</tool_call>");
    }
}
