package io.github.ngirchev.opendaimon.telegram.command.handler.impl.fsm;

import io.github.ngirchev.opendaimon.common.agent.AgentStreamEvent;
import io.github.ngirchev.opendaimon.telegram.command.TelegramCommand;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

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

    @Test
    void shouldHandleConcurrentProgressMerges() throws Exception {
        MessageHandlerContext ctx = new MessageHandlerContext(mock(TelegramCommand.class), null, s -> {});
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> futures = IntStream.range(0, 120)
                .mapToObj(index -> executor.submit(() -> {
                    start.await();
                    AgentStreamEvent event = switch (index % 4) {
                        case 0 -> AgentStreamEvent.thinking("Thinking " + index, index % 3);
                        case 1 -> AgentStreamEvent.toolCall("web_search", "query " + index, index % 3);
                        case 2 -> AgentStreamEvent.observation("Result " + index, index % 3);
                        default -> AgentStreamEvent.error("Error " + index, index % 3);
                    };
                    ctx.mergeAgentProgressEvent(event, "<p>chunk " + index + "</p>", 512);
                    return null;
                }))
                .toList();

        start.countDown();
        try {
            for (Future<Object> future : futures) {
                future.get(5, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        MessageHandlerContext.AgentProgressUpdate terminalUpdate = ctx.mergeAgentProgressEvent(
                AgentStreamEvent.finalAnswer("Done", 1),
                null,
                512
        );

        assertThat(terminalUpdate.html()).hasSizeLessThanOrEqualTo(512);
    }
}
