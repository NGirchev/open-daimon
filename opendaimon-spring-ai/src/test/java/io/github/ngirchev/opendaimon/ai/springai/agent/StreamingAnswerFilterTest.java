package io.github.ngirchev.opendaimon.ai.springai.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StreamingAnswerFilterTest {

    @Test
    void shouldEmitCleanTextWithoutTags() {
        StreamingAnswerFilter filter = new StreamingAnswerFilter();
        StringBuilder out = new StringBuilder();
        out.append(filter.feed("Hello, "));
        out.append(filter.feed("world!"));
        out.append(filter.flush());
        assertThat(out.toString()).isEqualTo("Hello, world!");
    }

    @Test
    void shouldSkipThinkBlock() {
        StreamingAnswerFilter filter = new StreamingAnswerFilter();
        String out = filter.feed("Hello <think>I am reasoning</think> world") + filter.flush();
        assertThat(out).isEqualTo("Hello  world");
    }

    @Test
    void shouldSkipToolCallBlock() {
        StreamingAnswerFilter filter = new StreamingAnswerFilter();
        String out = filter.feed("text <tool_call>name args</tool_call> more") + filter.flush();
        assertThat(out).isEqualTo("text  more");
    }

    @Test
    void shouldHandleThinkTagSplitAcrossChunks() {
        StreamingAnswerFilter filter = new StreamingAnswerFilter();
        StringBuilder out = new StringBuilder();
        out.append(filter.feed("answer <th"));
        out.append(filter.feed("ink>secret</thi"));
        out.append(filter.feed("nk> end"));
        out.append(filter.flush());
        assertThat(out.toString()).isEqualTo("answer  end");
    }

    @Test
    void shouldHandleToolCallTagSplitAcrossChunks() {
        StreamingAnswerFilter filter = new StreamingAnswerFilter();
        StringBuilder out = new StringBuilder();
        out.append(filter.feed("text <to"));
        out.append(filter.feed("ol_call>body</tool"));
        out.append(filter.feed("_call> end"));
        out.append(filter.flush());
        assertThat(out.toString()).isEqualTo("text  end");
    }

    @Test
    void shouldHandleMultipleBlocksInOneChunk() {
        StreamingAnswerFilter filter = new StreamingAnswerFilter();
        String out = filter.feed("a<think>x</think>b<tool_call>y</tool_call>c") + filter.flush();
        assertThat(out).isEqualTo("abc");
    }

    @Test
    void shouldEmitNothingForEmptyInput() {
        StreamingAnswerFilter filter = new StreamingAnswerFilter();
        assertThat(filter.feed("") + filter.flush()).isEmpty();
    }

    @Test
    void shouldEmitNothingWhenStreamConsistsOnlyOfToolCallMarkup() {
        StreamingAnswerFilter filter = new StreamingAnswerFilter();
        String text = "<tool_call><name>x</name><arg_key>k</arg_key><arg_value>v</arg_value></tool_call>";
        String out = filter.feed(text) + filter.flush();
        assertThat(out).isEmpty();
    }

    @Test
    void shouldEmitNothingWhenStreamConsistsOnlyOfThinkMarkup() {
        StreamingAnswerFilter filter = new StreamingAnswerFilter();
        String out = filter.feed("<think>just thinking out loud</think>") + filter.flush();
        assertThat(out).isEmpty();
    }

    @Test
    void shouldEmitTextBeforeFirstBlock() {
        StreamingAnswerFilter filter = new StreamingAnswerFilter();
        String out = filter.feed("prefix text<tool_call>x</tool_call>") + filter.flush();
        assertThat(out).isEqualTo("prefix text");
    }

    @Test
    void shouldEmitTextAfterLastBlock() {
        StreamingAnswerFilter filter = new StreamingAnswerFilter();
        String out = filter.feed("<think>x</think>suffix text") + filter.flush();
        assertThat(out).isEqualTo("suffix text");
    }

    @Test
    void shouldEmitTailWhenStreamEndsWithDanglingTagPrefix() {
        StreamingAnswerFilter filter = new StreamingAnswerFilter();
        StringBuilder out = new StringBuilder();
        out.append(filter.feed("answer <th"));
        out.append(filter.flush());
        assertThat(out.toString()).isEqualTo("answer <th");
    }

    @Test
    void shouldDropContentWhenStreamEndsInsideThinkBlock() {
        StreamingAnswerFilter filter = new StreamingAnswerFilter();
        String out = filter.feed("answer <think>not closed") + filter.flush();
        assertThat(out).isEqualTo("answer ");
    }

    @Test
    void shouldDropContentWhenStreamEndsInsideToolCallBlock() {
        StreamingAnswerFilter filter = new StreamingAnswerFilter();
        String out = filter.feed("answer <tool_call>not closed") + filter.flush();
        assertThat(out).isEqualTo("answer ");
    }

    @Test
    void shouldHandleSingleCharacterChunks() {
        StreamingAnswerFilter filter = new StreamingAnswerFilter();
        String input = "ab<think>x</think>cd";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            out.append(filter.feed(String.valueOf(input.charAt(i))));
        }
        out.append(filter.flush());
        assertThat(out.toString()).isEqualTo("abcd");
    }

    @Test
    void shouldHandleAdjacentBlocks() {
        StreamingAnswerFilter filter = new StreamingAnswerFilter();
        String out = filter.feed("a<think>t1</think><tool_call>tc</tool_call>b") + filter.flush();
        assertThat(out).isEqualTo("ab");
    }

    @Test
    void shouldNotConfuseLessThanWithTagOpening() {
        StreamingAnswerFilter filter = new StreamingAnswerFilter();
        String out = filter.feed("if a < b then c") + filter.flush();
        assertThat(out).isEqualTo("if a < b then c");
    }

    @Test
    void shouldHandleAngleBracketFollowedByUnrelatedText() {
        StreamingAnswerFilter filter = new StreamingAnswerFilter();
        String out = filter.feed("type <List<Integer>> for collections") + filter.flush();
        assertThat(out).isEqualTo("type <List<Integer>> for collections");
    }
}
