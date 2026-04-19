package io.github.ngirchev.opendaimon.ai.springai.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the static text-processing helpers in {@link AgentTextSanitizer}.
 * No Spring context needed — methods are package-private statics.
 */
class AgentTextSanitizerStripTagsTest {

    // --- stripThinkTags ---

    @Test
    void shouldReturnTextBeforeThinkTagWhenUnclosed() {
        String result = AgentTextSanitizer.stripThinkTags("hello<think>reasoning");
        assertThat(result).isEqualTo("hello");
    }

    @Test
    void shouldReturnEmptyWhenEntireTextIsUnclosedThinkTag() {
        String result = AgentTextSanitizer.stripThinkTags("<think>reasoning");
        assertThat(result).isEmpty();
    }

    @Test
    void shouldStripCompleteThinkBlock() {
        String result = AgentTextSanitizer.stripThinkTags("answer<think>reasoning</think>");
        assertThat(result).isEqualTo("answer");
    }

    @Test
    void shouldReturnTextUnchangedWhenNoThinkTag() {
        String result = AgentTextSanitizer.stripThinkTags("plain answer");
        assertThat(result).isEqualTo("plain answer");
    }

    @Test
    void shouldReturnNullWhenInputIsNull() {
        assertThat(AgentTextSanitizer.stripThinkTags(null)).isNull();
    }

    // --- stripToolCallTags (Fix D) ---

    @Test
    void shouldNotStripNameTagsInNormalXmlWhenNoArgKeyPresent() {
        String input = "<name>foo</name> bar";
        String result = AgentTextSanitizer.stripToolCallTags(input);
        assertThat(result).contains("foo");
    }

    @Test
    void shouldStripInnerTagsWhenArgKeyIsPresent() {
        String input = "<name>tool</name><arg_key>q</arg_key><arg_value>hello</arg_value>";
        String result = AgentTextSanitizer.stripToolCallTags(input);
        assertThat(result).isEmpty();
    }

    // --- normalizeDelta (Fix E) ---

    @Test
    void shouldNormalizeCumulativeSnapshotToDelta() {
        String accumulated = "Hello world";
        String chunk = "Hello world, how are you?";
        String delta = AgentTextSanitizer.normalizeDelta(accumulated, chunk);
        assertThat(delta).isEqualTo(", how are you?");
    }

    @Test
    void shouldReturnChunkUnchangedWhenAccumulatedIsEmpty() {
        String delta = AgentTextSanitizer.normalizeDelta("", "first chunk");
        assertThat(delta).isEqualTo("first chunk");
    }

    @Test
    void shouldReturnChunkUnchangedWhenNotACumulativeSnapshot() {
        String delta = AgentTextSanitizer.normalizeDelta("Hello", "world");
        assertThat(delta).isEqualTo("world");
    }
}
