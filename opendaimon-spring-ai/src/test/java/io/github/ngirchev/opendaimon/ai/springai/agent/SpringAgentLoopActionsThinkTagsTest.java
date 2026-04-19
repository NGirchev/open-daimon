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
    void shouldStripOrphanClosingThinkTagWhenAtStart() {
        assertThat(SpringAgentLoopActions.stripThinkTags("</think>actual answer"))
                .isEqualTo("actual answer");
    }

    @Test
    void shouldStripOrphanClosingThinkTagWithReasoningPrefix() {
        String text = "leaked reasoning</think>actual answer";
        assertThat(SpringAgentLoopActions.stripThinkTags(text))
                .isEqualTo("actual answer");
    }

    @Test
    void shouldStripOrphanClosingThinkTagWithTrailingWhitespace() {
        String text = "leaked reasoning</think>\n\n   actual answer   ";
        assertThat(SpringAgentLoopActions.stripThinkTags(text))
                .isEqualTo("actual answer");
    }

    @Test
    void shouldReturnEmptyWhenOnlyOrphanClosingThinkTagBeforeBlankText() {
        assertThat(SpringAgentLoopActions.stripThinkTags("reasoning</think>   "))
                .isEqualTo("");
    }

    @Test
    void shouldDifferFromStreamingFilterOnOrphanClose() {
        // Non-streaming: full text in hand → safely drops the entire reasoning prefix.
        // Streaming: prefix may already have been emitted to the user → filter only strips
        // the orphan tag itself, keeping the prefix as plain text. The two paths
        // intentionally diverge on this edge case.
        String input = "reasoning prefix</think>final answer";
        assertThat(SpringAgentLoopActions.stripThinkTags(input))
                .isEqualTo("final answer");

        StreamingAnswerFilter filter = new StreamingAnswerFilter();
        String streamed = filter.feed(input) + filter.flush();
        assertThat(streamed).isEqualTo("reasoning prefixfinal answer");
    }
}
