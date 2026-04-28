package io.github.ngirchev.opendaimon.common.agent;

import io.github.ngirchev.fsm.StateContext;
import io.github.ngirchev.fsm.Transition;
import io.github.ngirchev.opendaimon.common.model.Attachment;
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

    /**
     * Maximum number of retry attempts when the LLM returns an empty response
     * (no tool call, no text, no error) within a single iteration.
     * Resets after a successful tool call or final answer (via {@link #resetIterationState()}).
     */
    public static final int MAX_EMPTY_RESPONSE_RETRIES = 1;

    // --- StateContext fields ---
    private AgentState state;
    private Transition<AgentState> currentTransition;

    // --- Input (immutable after construction) ---
    private final String task;
    private final String conversationId;
    private final Map<String, String> metadata;
    private final int maxIterations;
    private final Set<String> enabledTools;
    /**
     * Multimodal attachments (e.g. images) passed alongside the task. Used by the
     * Spring AI agent path to attach {@code Media} objects to the first user message
     * so vision-capable models actually see the image. Defaults to {@link List#of()}.
     */
    private final List<Attachment> attachments;
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

    // --- Empty-response retry (resets per iteration) ---
    private boolean emptyResponse;
    private int emptyResponseRetryCount;

    // --- Output ---
    private String finalAnswer;
    private String modelName;

    // --- Streaming (optional, set externally for streaming execution) ---
    private java.util.function.Consumer<AgentStreamEvent> streamSink;

    // --- Per-execution transient state (used by AgentLoopActions implementations) ---
    private final Map<String, Object> extras = new java.util.HashMap<>();

    /**
     * Cooperative cancellation flag. Set by the transport layer (e.g. Telegram /cancel,
     * REST DELETE /agent/run/{id}) to signal that the user no longer wants the result.
     * Streaming loops and long-running FSM actions poll {@link #isCancelled()} and exit
     * early. Declared {@code volatile} because set/read happens across thread boundaries
     * (user request thread → reactor scheduler).
     */
    private volatile boolean cancelled;

    public AgentContext(String task, String conversationId, Map<String, String> metadata,
                        int maxIterations, Set<String> enabledTools) {
        this(task, conversationId, metadata, maxIterations, enabledTools, List.of());
    }

    public AgentContext(String task, String conversationId, Map<String, String> metadata,
                        int maxIterations, Set<String> enabledTools,
                        List<Attachment> attachments) {
        this.task = task;
        this.conversationId = conversationId;
        this.metadata = metadata;
        this.maxIterations = maxIterations;
        this.enabledTools = enabledTools;
        this.attachments = attachments == null ? List.of() : List.copyOf(attachments);
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

    /**
     * LLM returned an empty response within the current iteration
     * (no tool call, no text, no error). Cleared by {@link #clearEmptyResponse()}
     * or {@link #resetIterationState()}.
     */
    public boolean hasEmptyResponse() {
        return emptyResponse;
    }

    /**
     * Guard used by the FSM to decide whether to retry a THINKING step
     * after the LLM produced an empty response. True only while
     * {@link #hasEmptyResponse()} is set and the per-iteration retry
     * budget (controlled by {@link #MAX_EMPTY_RESPONSE_RETRIES}) is not exhausted.
     */
    public boolean canRetryEmptyResponse() {
        return emptyResponse && emptyResponseRetryCount < MAX_EMPTY_RESPONSE_RETRIES;
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
        emptyResponse = false;
        emptyResponseRetryCount = 0;
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
        return Map.copyOf(metadata);
    }

    public int getMaxIterations() {
        return maxIterations;
    }

    public Set<String> getEnabledTools() {
        return Set.copyOf(enabledTools);
    }

    /**
     * Returns the multimodal attachments associated with this agent run.
     * The list is unmodifiable and never {@code null}.
     */
    public List<Attachment> getAttachments() {
        return attachments;
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

    // --- Empty-response retry ---

    /**
     * Marks that the current THINKING step produced an empty response.
     * Must be called from {@code think()} when the LLM returns no tool call,
     * no final text, and no error.
     */
    public void markEmptyResponse() {
        this.emptyResponse = true;
    }

    /**
     * Clears the empty-response flag. Called by the retry action before
     * re-invoking {@code think()} so the next response is evaluated fresh.
     */
    public void clearEmptyResponse() {
        this.emptyResponse = false;
    }

    /**
     * Number of empty-response retries consumed within the current iteration.
     * Reset by {@link #resetIterationState()}.
     */
    public int getEmptyResponseRetryCount() {
        return emptyResponseRetryCount;
    }

    public void incrementEmptyResponseRetryCount() {
        this.emptyResponseRetryCount++;
    }

    // --- Output ---

    public String getFinalAnswer() {
        return finalAnswer;
    }

    public void setFinalAnswer(String finalAnswer) {
        this.finalAnswer = finalAnswer;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
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

    // --- Cancellation ---

    /** Signals the agent loop to abort at the next checkpoint. Idempotent. */
    public void cancel() {
        this.cancelled = true;
    }

    /** Returns {@code true} if {@link #cancel()} was invoked on this context. */
    public boolean isCancelled() {
        return cancelled;
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
                getDuration(),
                modelName
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
