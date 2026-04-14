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

                agentFsm.handle(ctx, AgentEvent.START);

                // Emit metadata (model name) before terminal event
                AgentResult result = ctx.toResult();
                if (result.modelName() != null) {
                    sink.tryEmitNext(AgentStreamEvent.metadata(
                            result.modelName(), result.iterationsUsed()));
                }

                // Emit terminal event based on final state
                if (result.isSuccess()) {
                    sink.tryEmitNext(AgentStreamEvent.finalAnswer(
                            result.finalAnswer(), result.iterationsUsed()));
                } else if (result.terminalState() == AgentState.MAX_ITERATIONS) {
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
}
