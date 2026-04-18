package io.github.ngirchev.opendaimon.telegram.command.handler.impl.fsm;

import io.github.ngirchev.opendaimon.common.agent.AgentStreamEvent;
import io.github.ngirchev.opendaimon.telegram.command.TelegramCommand;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class MessageHandlerContextAgentProgressTest {

    private static final int MAX_LENGTH = 4096;

    @Test
    void shouldReplaceThinkingWithinSameIteration() {
        MessageHandlerContext ctx = new MessageHandlerContext(mock(TelegramCommand.class), null, s -> {});

        MessageHandlerContext.AgentProgressUpdate first = ctx.mergeAgentProgressEvent(
                AgentStreamEvent.thinking(0),
                "<i>Thinking...</i>",
                MAX_LENGTH
        );

        MessageHandlerContext.AgentProgressUpdate second = ctx.mergeAgentProgressEvent(
                AgentStreamEvent.thinking("Need to search", 0),
                "<i>Need to search</i>",
                MAX_LENGTH
        );

        assertThat(first.html()).contains("Thinking...");
        assertThat(second.html())
                .contains("Need to search")
                .doesNotContain("Thinking...");
    }

    @Test
    void shouldRemoveTransientThinkingBeforePersistentChunks() {
        MessageHandlerContext ctx = new MessageHandlerContext(mock(TelegramCommand.class), null, s -> {});

        ctx.mergeAgentProgressEvent(AgentStreamEvent.thinking(0), "<i>Thinking...</i>", MAX_LENGTH);
        ctx.mergeAgentProgressEvent(AgentStreamEvent.thinking("Need to search", 0), "<i>Need to search</i>", MAX_LENGTH);

        MessageHandlerContext.AgentProgressUpdate toolUpdate = ctx.mergeAgentProgressEvent(
                AgentStreamEvent.toolCall("web_search", "query", 0),
                "<b>Tool:</b> <code>web_search</code>",
                MAX_LENGTH
        );
        MessageHandlerContext.AgentProgressUpdate observationUpdate = ctx.mergeAgentProgressEvent(
                AgentStreamEvent.observation("No result", 0),
                "<blockquote>No result</blockquote>",
                MAX_LENGTH
        );

        assertThat(toolUpdate.html())
                .contains("web_search")
                .doesNotContain("Thinking");
        assertThat(observationUpdate.html())
                .contains("web_search")
                .contains("No result")
                .doesNotContain("Thinking");
    }

    @Test
    void shouldRemoveTransientThinkingOnTerminalEvent() {
        MessageHandlerContext ctx = new MessageHandlerContext(mock(TelegramCommand.class), null, s -> {});

        MessageHandlerContext.AgentProgressUpdate thinkingUpdate = ctx.mergeAgentProgressEvent(
                AgentStreamEvent.thinking(0),
                "<i>Thinking...</i>",
                MAX_LENGTH
        );
        MessageHandlerContext.AgentProgressUpdate terminalUpdate = ctx.mergeAgentProgressEvent(
                AgentStreamEvent.finalAnswer("Done", 0),
                null,
                MAX_LENGTH
        );

        assertThat(thinkingUpdate.html()).contains("Thinking...");
        assertThat(terminalUpdate.changed()).isTrue();
        assertThat(terminalUpdate.html()).doesNotContain("Thinking...");
        assertThat(terminalUpdate.isEmpty()).isTrue();
    }

    @Test
    void shouldIgnoreFinalAnswerChunkInProgressMessage() {
        MessageHandlerContext ctx = new MessageHandlerContext(mock(TelegramCommand.class), null, s -> {});

        MessageHandlerContext.AgentProgressUpdate update = ctx.mergeAgentProgressEvent(
                AgentStreamEvent.finalAnswerChunk("partial final text", 1),
                null,
                MAX_LENGTH
        );

        assertThat(update.changed()).isFalse();
        assertThat(update.isEmpty()).isTrue();
    }
}
