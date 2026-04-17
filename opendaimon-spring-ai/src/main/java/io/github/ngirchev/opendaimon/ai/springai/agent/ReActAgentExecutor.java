package io.github.ngirchev.opendaimon.ai.springai.agent;

import io.github.ngirchev.fsm.impl.extended.ExDomainFsm;
import io.github.ngirchev.opendaimon.common.agent.AgentContext;
import io.github.ngirchev.opendaimon.common.agent.AgentEvent;
import io.github.ngirchev.opendaimon.common.agent.AgentExecutor;
import io.github.ngirchev.opendaimon.common.agent.AgentRequest;
import io.github.ngirchev.opendaimon.common.agent.AgentResult;
import io.github.ngirchev.opendaimon.common.agent.AgentState;
import io.github.ngirchev.opendaimon.common.agent.AgentStreamEvent;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;

/**
 * ReAct agent executor that uses an FSM to drive the think-act-observe loop.
 *
 * <p>The FSM is a stateless singleton — each execution creates a fresh
 * {@link AgentContext} that carries all mutable state. The FSM reads/writes
 * state directly on the context object via {@link io.github.ngirchev.fsm.StateContext}.
 *
 * <p>A single {@link AgentEvent#START} event kicks off the loop. Auto-transitions
 * chain through THINKING → TOOL_EXECUTING → OBSERVING → THINKING until
 * the LLM produces a final answer or a terminal condition is reached.
 *
 * <p>Supports streaming via {@link #executeStream(AgentRequest)} — events are
 * emitted as the agent progresses through iterations.
 */
@Slf4j
public class ReActAgentExecutor implements AgentExecutor {

    private static final int FINAL_ANSWER_CHUNK_MAX_CHARS = 320;

    private final ExDomainFsm<AgentContext, AgentState, AgentEvent> agentFsm;

    public ReActAgentExecutor(ExDomainFsm<AgentContext, AgentState, AgentEvent> agentFsm) {
        this.agentFsm = agentFsm;
    }

    @Override
    public AgentResult execute(AgentRequest request) {
        log.info("Agent execution started: task='{}', maxIterations={}, tools={}",
                request.task(), request.maxIterations(), request.enabledTools());

        AgentContext ctx = new AgentContext(
                request.task(),
                request.conversationId(),
                request.metadata(),
                request.maxIterations(),
                request.enabledTools()
        );

        agentFsm.handle(ctx, AgentEvent.START);

        AgentResult result = ctx.toResult();
        log.info("Agent execution finished: state={}, iterations={}, duration={}ms",
                result.terminalState(), result.iterationsUsed(), result.totalDuration().toMillis());

        return result;
    }

    @Override
    public Flux<AgentStreamEvent> executeStream(AgentRequest request) {
        Sinks.Many<AgentStreamEvent> sink = Sinks.many().unicast().onBackpressureBuffer();

        Flux<AgentStreamEvent> eventFlux = sink.asFlux();

        // Run FSM in a bounded elastic thread to avoid blocking the caller
        Flux.defer(() -> {
            try {
                AgentContext ctx = new AgentContext(
                        request.task(),
                        request.conversationId(),
                        request.metadata(),
                        request.maxIterations(),
                        request.enabledTools()
                );

                // Install an event listener on the context
                ctx.setStreamSink(sink::tryEmitNext);
                SpringAgentLoopActions.markStreamingExecution(ctx);

                agentFsm.handle(ctx, AgentEvent.START);

                // Emit metadata (model name) before terminal event
                AgentResult result = ctx.toResult();
                String streamedVisibleAnswer = SpringAgentLoopActions.getStreamedFinalVisibleAnswer(ctx);
                if (result.modelName() != null) {
                    sink.tryEmitNext(AgentStreamEvent.metadata(
                            result.modelName(), result.iterationsUsed()));
                }

                // Emit terminal event based on final state
                if (result.isSuccess()) {
                    emitFinalAnswerChunks(
                            sink, result.finalAnswer(), streamedVisibleAnswer, result.iterationsUsed());
                    sink.tryEmitNext(AgentStreamEvent.finalAnswer(
                            result.finalAnswer(), result.iterationsUsed()));
                } else if (result.terminalState() == AgentState.MAX_ITERATIONS) {
                    emitFinalAnswerChunks(
                            sink, result.finalAnswer(), streamedVisibleAnswer, result.iterationsUsed());
                    sink.tryEmitNext(AgentStreamEvent.maxIterations(
                            result.finalAnswer(), result.iterationsUsed()));
                } else {
                    sink.tryEmitNext(AgentStreamEvent.error(
                            ctx.getErrorMessage(), result.iterationsUsed()));
                }

                sink.tryEmitComplete();
            } catch (Exception e) {
                log.error("Agent stream execution failed: {}", e.getMessage(), e);
                sink.tryEmitNext(AgentStreamEvent.error(e.getMessage(), 0));
                sink.tryEmitError(e);
            }
            return Flux.empty();
        }).subscribeOn(Schedulers.boundedElastic()).subscribe();

        return eventFlux;
    }

    private static void emitFinalAnswerChunks(
            Sinks.Many<AgentStreamEvent> sink,
            String terminalAnswer,
            String alreadyStreamedVisibleAnswer,
            int iteration
    ) {
        String safeAnswer = sanitizeFinalAnswerForChunkStream(terminalAnswer);
        String safeAlreadyStreamed = sanitizeFinalAnswerForChunkStream(alreadyStreamedVisibleAnswer);
        if (safeAnswer.isBlank()) {
            return;
        }
        String tail = determineTailToEmit(safeAnswer, safeAlreadyStreamed);
        if (tail.isBlank()) {
            return;
        }
        for (String chunk : splitIntoChunks(tail, FINAL_ANSWER_CHUNK_MAX_CHARS)) {
            if (!chunk.isBlank()) {
                sink.tryEmitNext(AgentStreamEvent.finalAnswerChunk(chunk, iteration));
            }
        }
    }

    private static String determineTailToEmit(String safeAnswer, String safeAlreadyStreamed) {
        if (safeAlreadyStreamed == null || safeAlreadyStreamed.isBlank()) {
            return safeAnswer;
        }
        if (safeAnswer.equals(safeAlreadyStreamed)) {
            return "";
        }
        if (safeAnswer.startsWith(safeAlreadyStreamed)) {
            return safeAnswer.substring(safeAlreadyStreamed.length());
        }
        // If prefixes diverged, avoid duplicate burst chunks; terminal FINAL_ANSWER remains authoritative.
        return "";
    }

    private static String sanitizeFinalAnswerForChunkStream(String terminalAnswer) {
        String sanitized = SpringAgentLoopActions.sanitizeFinalAnswerText(terminalAnswer);
        if (sanitized == null || sanitized.isBlank()) {
            return "";
        }
        return SpringAgentLoopActions.extractUserTextBeforeToolPayload(sanitized).trim();
    }

    private static List<String> splitIntoChunks(String text, int maxChunkChars) {
        List<String> chunks = new ArrayList<>();
        String normalized = text == null ? "" : text.trim();
        if (normalized.isBlank()) {
            return chunks;
        }
        String[] tokens = normalized.split("(?<=\\s)");
        StringBuilder current = new StringBuilder();

        for (String token : tokens) {
            if (token == null || token.isEmpty()) {
                continue;
            }
            if (current.length() + token.length() > maxChunkChars && !current.isEmpty()) {
                chunks.add(current.toString().trim());
                current.setLength(0);
            }
            if (token.length() > maxChunkChars && current.isEmpty()) {
                int start = 0;
                while (start < token.length()) {
                    int end = Math.min(start + maxChunkChars, token.length());
                    chunks.add(token.substring(start, end).trim());
                    start = end;
                }
                continue;
            }
            current.append(token);
        }

        if (!current.isEmpty()) {
            chunks.add(current.toString().trim());
        }
        return chunks;
    }
}
