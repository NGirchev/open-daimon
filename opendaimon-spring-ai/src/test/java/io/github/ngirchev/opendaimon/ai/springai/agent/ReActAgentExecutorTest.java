package io.github.ngirchev.opendaimon.ai.springai.agent;

import io.github.ngirchev.fsm.impl.extended.ExDomainFsm;
import io.github.ngirchev.opendaimon.common.agent.AgentContext;
import io.github.ngirchev.opendaimon.common.agent.AgentEvent;
import io.github.ngirchev.opendaimon.common.agent.AgentRequest;
import io.github.ngirchev.opendaimon.common.agent.AgentResult;
import io.github.ngirchev.opendaimon.common.agent.AgentState;
import io.github.ngirchev.opendaimon.common.agent.AgentStreamEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.withSettings;

/**
 * Unit tests for {@link ReActAgentExecutor}.
 *
 * <p>{@link ExDomainFsm} is a Kotlin class whose {@code handle} method is {@code final}.
 * The module-level {@code mock-maker-subclass} cannot intercept final methods, so the
 * mock is created programmatically with the inline mock maker for this dependency only.
 */
class ReActAgentExecutorTest {

    @SuppressWarnings("unchecked")
    private ExDomainFsm<AgentContext, AgentState, AgentEvent> agentFsm;

    private ReActAgentExecutor executor;

    @BeforeEach
    void setUp() {
        agentFsm = mock(ExDomainFsm.class, withSettings().mockMaker("mock-maker-inline"));
        executor = new ReActAgentExecutor(agentFsm);
    }

    // --- Helpers ---

    /**
     * Stubs {@code agentFsm.handle()} to apply {@code ctxModifier} on the received context.
     * Uses {@link AtomicReference} pattern for context capture where assertions are needed.
     */
    private void stubFsmHandle(Consumer<AgentContext> ctxModifier) {
        doAnswer(invocation -> {
            AgentContext ctx = invocation.getArgument(0);
            ctxModifier.accept(ctx);
            return null;
        }).when(agentFsm).handle(any(AgentContext.class), eq(AgentEvent.START));
    }

    // --- execute() ---

    @Test
    @DisplayName("execute() delegates to FSM with START event")
    void shouldDelegateToFsmWithStartEventWhenExecuteCalled() {
        // Arrange
        AgentRequest request = new AgentRequest(
                "What is 2+2?", "conv-42", Map.of("userId", "u1"), 5, Set.of("calculator")
        );
        AtomicReference<AgentContext> capturedCtx = new AtomicReference<>();
        stubFsmHandle(ctx -> {
            capturedCtx.set(ctx);
            ctx.setFinalAnswer("4");
            ctx.setState(AgentState.COMPLETED);
        });

        // Act
        AgentResult result = executor.execute(request);

        // Assert
        verify(agentFsm).handle(any(AgentContext.class), eq(AgentEvent.START));
        assertThat(result.finalAnswer()).isEqualTo("4");
        assertThat(capturedCtx.get()).isNotNull();
    }

    @Test
    @DisplayName("execute() maps all request fields into AgentContext")
    void shouldMapRequestFieldsToContextCorrectly() {
        // Arrange
        Set<String> tools = Set.of("search", "calculator");
        Map<String, String> meta = Map.of("channel", "telegram");
        AgentRequest request = new AgentRequest("Summarize this", "conv-99", meta, 7, tools);
        AtomicReference<AgentContext> capturedCtx = new AtomicReference<>();

        stubFsmHandle(ctx -> {
            capturedCtx.set(ctx);
            ctx.setState(AgentState.COMPLETED);
            ctx.setFinalAnswer("Summary done");
        });

        // Act
        executor.execute(request);

        // Assert
        AgentContext captured = capturedCtx.get();
        assertThat(captured).isNotNull();
        assertThat(captured.getTask()).isEqualTo("Summarize this");
        assertThat(captured.getConversationId()).isEqualTo("conv-99");
        assertThat(captured.getMaxIterations()).isEqualTo(7);
        assertThat(captured.getEnabledTools()).containsExactlyInAnyOrderElementsOf(tools);
        assertThat(captured.getMetadata()).containsEntry("channel", "telegram");
    }

    @Test
    @DisplayName("execute() returns success result when FSM sets COMPLETED state")
    void shouldReturnSuccessResultWhenFsmSetsFinalAnswer() {
        // Arrange
        AgentRequest request = new AgentRequest("Answer me", "conv-1", Map.of(), 10, Set.of());
        stubFsmHandle(ctx -> {
            ctx.setFinalAnswer("42");
            ctx.setState(AgentState.COMPLETED);
        });

        // Act
        AgentResult result = executor.execute(request);

        // Assert
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.terminalState()).isEqualTo(AgentState.COMPLETED);
        assertThat(result.finalAnswer()).isEqualTo("42");
    }

    @Test
    @DisplayName("execute() returns failed result when FSM sets FAILED state")
    void shouldReturnFailedResultWhenFsmSetsErrorState() {
        // Arrange
        AgentRequest request = new AgentRequest("Fail me", "conv-2", Map.of(), 10, Set.of());
        stubFsmHandle(ctx -> {
            ctx.setErrorMessage("LLM call timed out");
            ctx.setState(AgentState.FAILED);
        });

        // Act
        AgentResult result = executor.execute(request);

        // Assert
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.terminalState()).isEqualTo(AgentState.FAILED);
    }

    @Test
    @DisplayName("execute() returns MAX_ITERATIONS result when iteration limit is reached")
    void shouldReturnMaxIterationsResultWhenLimitReached() {
        // Arrange
        AgentRequest request = new AgentRequest("Loop forever", "conv-3", Map.of(), 3, Set.of());
        stubFsmHandle(ctx -> ctx.setState(AgentState.MAX_ITERATIONS));

        // Act
        AgentResult result = executor.execute(request);

        // Assert
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.terminalState()).isEqualTo(AgentState.MAX_ITERATIONS);
    }

    @Test
    @DisplayName("execute() result carries a non-negative duration")
    void shouldReturnResultWithNonNegativeDuration() {
        // Arrange
        AgentRequest request = new AgentRequest("Quick task", "conv-4", Map.of(), 5, Set.of());
        stubFsmHandle(ctx -> {
            ctx.setFinalAnswer("Done");
            ctx.setState(AgentState.COMPLETED);
        });

        // Act
        AgentResult result = executor.execute(request);

        // Assert
        assertThat(result.totalDuration()).isNotNull();
        assertThat(result.totalDuration().toMillis()).isGreaterThanOrEqualTo(0);
    }

    // --- executeStream() ---

    @Test
    @DisplayName("executeStream() emits FINAL_ANSWER event and completes on success")
    void shouldEmitFinalAnswerEventWhenFsmCompletesSuccessfully() {
        // Arrange
        AgentRequest request = new AgentRequest("Stream me", "conv-5", Map.of(), 5, Set.of());
        stubFsmHandle(ctx -> {
            ctx.setFinalAnswer("Streamed answer");
            ctx.setState(AgentState.COMPLETED);
        });

        // Act
        List<AgentStreamEvent> events = executor.executeStream(request)
                .collectList()
                .block();

        // Assert
        assertThat(events).hasSize(1);
        assertThat(events.get(0).type()).isEqualTo(AgentStreamEvent.EventType.FINAL_ANSWER);
        assertThat(events.get(0).content()).isEqualTo("Streamed answer");
    }

    @Test
    @DisplayName("executeStream() emits ERROR event when FSM sets FAILED state")
    void shouldEmitErrorEventWhenFsmSetsFailedState() {
        // Arrange
        AgentRequest request = new AgentRequest("Stream error", "conv-6", Map.of(), 5, Set.of());
        stubFsmHandle(ctx -> {
            ctx.setErrorMessage("Something went wrong");
            ctx.setState(AgentState.FAILED);
        });

        // Act
        List<AgentStreamEvent> events = executor.executeStream(request)
                .collectList()
                .block();

        // Assert
        assertThat(events).hasSize(1);
        assertThat(events.get(0).type()).isEqualTo(AgentStreamEvent.EventType.ERROR);
        assertThat(events.get(0).content()).isEqualTo("Something went wrong");
    }

    @Test
    @DisplayName("executeStream() emits MAX_ITERATIONS event when limit is reached")
    void shouldEmitMaxIterationsEventWhenLimitReached() {
        // Arrange
        AgentRequest request = new AgentRequest("Exhaust iterations", "conv-7", Map.of(), 2, Set.of());
        stubFsmHandle(ctx -> {
            ctx.setState(AgentState.MAX_ITERATIONS);
            ctx.setFinalAnswer("I reached the maximum number of iterations. Here is what I found so far: ...");
        });

        // Act
        List<AgentStreamEvent> events = executor.executeStream(request)
                .collectList()
                .block();

        // Assert — both the status marker and the FINAL_ANSWER from handleMaxIterations
        assertThat(events).hasSize(2);
        assertThat(events.get(0).type()).isEqualTo(AgentStreamEvent.EventType.MAX_ITERATIONS);
        assertThat(events.get(1).type()).isEqualTo(AgentStreamEvent.EventType.FINAL_ANSWER);
        assertThat(events.get(1).content()).startsWith("I reached the maximum number of iterations");
    }

    @Test
    @DisplayName("executeStream() emits FINAL_ANSWER with safety text when MAX_ITERATIONS leaves finalAnswer blank")
    void shouldEmitFinalAnswerWithSafetyTextWhenResultFinalAnswerBlank() {
        // Safety-net: if a future regression in handleMaxIterations lets ctx.finalAnswer
        // slip through as null/blank, the user must still receive a textual answer in the
        // Telegram chat — not just an orphan "⚠️ reached iteration limit" status line.
        AgentRequest request = new AgentRequest("Exhaust iterations", "conv-7b", Map.of(), 2, Set.of());
        stubFsmHandle(ctx -> ctx.setState(AgentState.MAX_ITERATIONS)); // no finalAnswer set

        List<AgentStreamEvent> events = executor.executeStream(request)
                .collectList()
                .block();

        assertThat(events).hasSize(2);
        assertThat(events.get(0).type()).isEqualTo(AgentStreamEvent.EventType.MAX_ITERATIONS);
        assertThat(events.get(1).type()).isEqualTo(AgentStreamEvent.EventType.FINAL_ANSWER);
        assertThat(events.get(1).content())
                .as("Safety-net text must be emitted when finalAnswer is blank")
                .isNotBlank();
        assertThat(events.get(1).content()).contains("iteration limit");
    }

    @Test
    @DisplayName("executeStream() forwards intermediate events emitted via context sink")
    void shouldForwardIntermediateEventsEmittedViaContextSink() {
        // Arrange
        AgentRequest request = new AgentRequest("Multi-step task", "conv-8", Map.of(), 5, Set.of());
        stubFsmHandle(ctx -> {
            ctx.emitEvent(AgentStreamEvent.thinking(1));
            ctx.emitEvent(AgentStreamEvent.observation("Tool returned: 42", 1));
            ctx.setFinalAnswer("The answer is 42");
            ctx.setState(AgentState.COMPLETED);
        });

        // Act
        List<AgentStreamEvent> events = executor.executeStream(request)
                .collectList()
                .block();

        // Assert — intermediate events followed by terminal FINAL_ANSWER
        assertThat(events).hasSize(3);
        assertThat(events.get(0).type()).isEqualTo(AgentStreamEvent.EventType.THINKING);
        assertThat(events.get(1).type()).isEqualTo(AgentStreamEvent.EventType.OBSERVATION);
        assertThat(events.get(2).type()).isEqualTo(AgentStreamEvent.EventType.FINAL_ANSWER);
    }

    @Test
    @DisplayName("executeStream() emits ERROR event when FSM throws an exception")
    void shouldEmitErrorEventWhenFsmThrowsException() {
        // Arrange
        AgentRequest request = new AgentRequest("Explode", "conv-9", Map.of(), 5, Set.of());
        doAnswer(invocation -> {
            throw new RuntimeException("Unexpected FSM failure");
        }).when(agentFsm).handle(any(AgentContext.class), eq(AgentEvent.START));

        // Act — collect events, suppress any terminal error signal from the sink
        List<AgentStreamEvent> events = executor.executeStream(request)
                .onErrorResume(e -> Flux.empty())
                .collectList()
                .block();

        // Assert
        assertThat(events).isNotNull().hasSize(1);
        assertThat(events.get(0).type()).isEqualTo(AgentStreamEvent.EventType.ERROR);
        assertThat(events.get(0).content()).contains("Unexpected FSM failure");
    }
}
