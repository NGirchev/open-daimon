package io.github.ngirchev.opendaimon.common.agent;

import io.github.ngirchev.fsm.StateContext;
import io.github.ngirchev.fsm.Transition;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Mutable domain object that flows through the agent loop FSM.
 *
 * <p>Implements {@link StateContext} so that {@code ExDomainFsm} can read/write
 * the current state directly on this object. Each FSM action populates
 * intermediate results as the context moves through states.
 *
 * <p>Guard methods ({@link #hasToolCall()}, {@link #isMaxIterationsReached()}, etc.)
 * are used by the FSM factory to determine transition conditions.
 */
public final class AgentContext implements StateContext<AgentState> {

    // --- StateContext fields ---
    private AgentState state;
    private Transition<AgentState> currentTransition;

    // --- Input (immutable after construction) ---
    private final String task;
    private final String conversationId;
    private final Map<String, String> metadata;
    private final int maxIterations;
    private final Set<String> enabledTools;
    private final Instant startTime;

    // --- Iteration tracking ---
    private int currentIteration;
    private final List<AgentStepResult> stepHistory = new ArrayList<>();

    // --- Current iteration state (reset each cycle) ---
    private String currentThought;
    private String currentToolName;
    private String currentToolArguments;
    private String currentTextResponse;
    private AgentToolResult toolResult;

    // --- Error state ---
    private String errorMessage;

    // --- Output ---
    private String finalAnswer;

    // --- Streaming (optional, set externally for streaming execution) ---
    private java.util.function.Consumer<AgentStreamEvent> streamSink;

    // --- Per-execution transient state (used by AgentLoopActions implementations) ---
    private final Map<String, Object> extras = new java.util.HashMap<>();

    public AgentContext(String task, String conversationId, Map<String, String> metadata,
                        int maxIterations, Set<String> enabledTools) {
        this.task = task;
        this.conversationId = conversationId;
        this.metadata = metadata;
        this.maxIterations = maxIterations;
        this.enabledTools = enabledTools;
        this.state = AgentState.INITIALIZED;
        this.startTime = Instant.now();
    }

    // --- StateContext implementation ---

    @Override
    public AgentState getState() {
        return state;
    }

    @Override
    public void setState(AgentState state) {
        this.state = state;
    }

    @Nullable
    @Override
    public Transition<AgentState> getCurrentTransition() {
        return currentTransition;
    }

    @Override
    public void setCurrentTransition(@Nullable Transition<AgentState> transition) {
        this.currentTransition = transition;
    }

    // --- Guard methods (used by FSM conditions) ---

    /**
     * LLM returned a tool call (function calling response).
     */
    public boolean hasToolCall() {
        return currentToolName != null && !currentToolName.isEmpty();
    }

    /**
     * LLM returned a final text answer (no tool call).
     */
    public boolean hasFinalAnswer() {
        return currentTextResponse != null && !currentTextResponse.isEmpty();
    }

    /**
     * Safety limit reached — agent looped too many times without producing a final answer.
     */
    public boolean isMaxIterationsReached() {
        return currentIteration >= maxIterations;
    }

    /**
     * An error occurred during thinking or tool execution.
     */
    public boolean hasError() {
        return errorMessage != null && !errorMessage.isEmpty();
    }

    // --- Iteration management ---

    public void incrementIteration() {
        currentIteration++;
    }

    /**
     * Resets per-iteration fields before the next THINKING phase.
     */
    public void resetIterationState() {
        currentThought = null;
        currentToolName = null;
        currentToolArguments = null;
        currentTextResponse = null;
        toolResult = null;
    }

    /**
     * Records a completed step in the history.
     */
    public void recordStep(AgentStepResult step) {
        stepHistory.add(step);
    }

    // --- Input accessors ---

    public String getTask() {
        return task;
    }

    public String getConversationId() {
        return conversationId;
    }

    public Map<String, String> getMetadata() {
        return metadata;
    }

    public int getMaxIterations() {
        return maxIterations;
    }

    public Set<String> getEnabledTools() {
        return enabledTools;
    }

    // --- Iteration state accessors ---

    public int getCurrentIteration() {
        return currentIteration;
    }

    public List<AgentStepResult> getStepHistory() {
        return List.copyOf(stepHistory);
    }

    public String getCurrentThought() {
        return currentThought;
    }

    public void setCurrentThought(String thought) {
        this.currentThought = thought;
    }

    public String getCurrentToolName() {
        return currentToolName;
    }

    public void setCurrentToolName(String toolName) {
        this.currentToolName = toolName;
    }

    public String getCurrentToolArguments() {
        return currentToolArguments;
    }

    public void setCurrentToolArguments(String toolArguments) {
        this.currentToolArguments = toolArguments;
    }

    public String getCurrentTextResponse() {
        return currentTextResponse;
    }

    public void setCurrentTextResponse(String textResponse) {
        this.currentTextResponse = textResponse;
    }

    public AgentToolResult getToolResult() {
        return toolResult;
    }

    public void setToolResult(AgentToolResult toolResult) {
        this.toolResult = toolResult;
    }

    // --- Error ---

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    // --- Output ---

    public String getFinalAnswer() {
        return finalAnswer;
    }

    public void setFinalAnswer(String finalAnswer) {
        this.finalAnswer = finalAnswer;
    }

    // --- Streaming ---

    public void setStreamSink(java.util.function.Consumer<AgentStreamEvent> streamSink) {
        this.streamSink = streamSink;
    }

    /**
     * Emits a stream event if a sink is configured. No-op otherwise.
     */
    public void emitEvent(AgentStreamEvent event) {
        if (streamSink != null) {
            streamSink.accept(event);
        }
    }

    // --- Extension map for implementation-specific state ---

    /**
     * Stores arbitrary transient state that lives for the duration of a single execution.
     * Used by {@code AgentLoopActions} implementations to avoid ThreadLocal fields.
     */
    @SuppressWarnings("unchecked")
    public <T> T getExtra(String key) {
        return (T) extras.get(key);
    }

    public void putExtra(String key, Object value) {
        extras.put(key, value);
    }

    public void removeExtra(String key) {
        extras.remove(key);
    }

    // --- Derived values ---

    public Duration getDuration() {
        return Duration.between(startTime, Instant.now());
    }

    /**
     * Builds an immutable {@link AgentResult} from the current context state.
     */
    public AgentResult toResult() {
        return new AgentResult(
                finalAnswer,
                getStepHistory(),
                state,
                currentIteration,
                getDuration()
        );
    }

    @Override
    public String toString() {
        return "AgentContext{state=" + state
                + ", iteration=" + currentIteration + "/" + maxIterations
                + ", steps=" + stepHistory.size()
                + ", hasToolCall=" + hasToolCall()
                + ", hasError=" + hasError()
                + '}';
    }
}
