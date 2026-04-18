package io.github.ngirchev.opendaimon.ai.springai.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the static text-processing helpers in {@link SpringAgentLoopActions}.
 * No Spring context needed — methods are package-private statics.
 */
class SpringAgentLoopActionsStripTagsTest {

    // --- stripThinkTags ---

    @Test
    void shouldReturnTextBeforeThinkTagWhenUnclosed() {
        String result = SpringAgentLoopActions.stripThinkTags("hello<think>reasoning");
        assertThat(result).isEqualTo("hello");
    }

    @Test
    void shouldReturnEmptyWhenEntireTextIsUnclosedThinkTag() {
        String result = SpringAgentLoopActions.stripThinkTags("<think>reasoning");
        assertThat(result).isEmpty();
    }

    @Test
    void shouldStripCompleteThinkBlock() {
        String result = SpringAgentLoopActions.stripThinkTags("answer<think>reasoning</think>");
        assertThat(result).isEqualTo("answer");
    }

    @Test
    void shouldReturnTextUnchangedWhenNoThinkTag() {
        String result = SpringAgentLoopActions.stripThinkTags("plain answer");
        assertThat(result).isEqualTo("plain answer");
    }

    @Test
    void shouldReturnNullWhenInputIsNull() {
        assertThat(SpringAgentLoopActions.stripThinkTags(null)).isNull();
    }

    // --- stripToolCallTags (Fix D) ---

    @Test
    void shouldNotStripNameTagsInNormalXmlWhenNoArgKeyPresent() {
        String input = "<name>foo</name> bar";
        String result = SpringAgentLoopActions.stripToolCallTags(input);
        assertThat(result).contains("foo");
    }

    @Test
    void shouldStripInnerTagsWhenArgKeyIsPresent() {
        String input = "<name>tool</name><arg_key>q</arg_key><arg_value>hello</arg_value>";
        String result = SpringAgentLoopActions.stripToolCallTags(input);
        assertThat(result).isEmpty();
    }

    // --- normalizeDelta (Fix E) ---

    @Test
    void shouldNormalizeCumulativeSnapshotToDelta() {
        String accumulated = "Hello world";
        String chunk = "Hello world, how are you?";
        String delta = SpringAgentLoopActions.normalizeDelta(accumulated, chunk);
        assertThat(delta).isEqualTo(", how are you?");
    }

    @Test
    void shouldReturnChunkUnchangedWhenAccumulatedIsEmpty() {
        String delta = SpringAgentLoopActions.normalizeDelta("", "first chunk");
        assertThat(delta).isEqualTo("first chunk");
    }

    @Test
    void shouldReturnChunkUnchangedWhenNotACumulativeSnapshot() {
        String delta = SpringAgentLoopActions.normalizeDelta("Hello", "world");
        assertThat(delta).isEqualTo("world");
    }
}
