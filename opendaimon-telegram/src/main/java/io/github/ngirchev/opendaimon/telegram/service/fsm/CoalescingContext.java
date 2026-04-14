package io.github.ngirchev.opendaimon.telegram.service.fsm;

import io.github.ngirchev.fsm.StateContext;
import io.github.ngirchev.fsm.Transition;
import io.github.ngirchev.opendaimon.telegram.service.TelegramMessageCoalescingService.CoalescingAction;
import org.jetbrains.annotations.Nullable;
import org.telegram.telegrambots.meta.api.objects.Update;

import java.util.function.Consumer;

/**
 * Domain object that flows through the coalescing FSM.
 *
 * <p>Each invocation of {@code onIncomingUpdate} creates a new context.
 * The FSM decision tree populates flags and the final {@link CoalescingAction} result.
 */
public final class CoalescingContext implements StateContext<CoalescingState> {

    // --- StateContext fields ---
    private CoalescingState state;
    private Transition<CoalescingState> currentTransition;

    // --- Input ---
    private final Update update;
    private final Consumer<Update> timeoutFlushConsumer;

    // --- Decision flags (set by actions) ---
    private boolean enabled;
    private boolean hasKey;
    private boolean hasPending;
    private boolean canMerge;
    private boolean firstCandidate;

    // --- Snapshot of pending message captured during checkPending (avoids re-read race) ---
    private Object capturedPending;

    // --- Output ---
    private CoalescingAction result;

    public CoalescingContext(Update update, Consumer<Update> timeoutFlushConsumer) {
        this.update = update;
        this.timeoutFlushConsumer = timeoutFlushConsumer;
        this.state = CoalescingState.RECEIVED;
    }

    // --- StateContext implementation ---

    @Override
    public CoalescingState getState() {
        return state;
    }

    @Override
    public void setState(CoalescingState state) {
        this.state = state;
    }

    @Nullable
    @Override
    public Transition<CoalescingState> getCurrentTransition() {
        return currentTransition;
    }

    @Override
    public void setCurrentTransition(@Nullable Transition<CoalescingState> transition) {
        this.currentTransition = transition;
    }

    // --- Input ---

    public Update getUpdate() {
        return update;
    }

    public Consumer<Update> getTimeoutFlushConsumer() {
        return timeoutFlushConsumer;
    }

    // --- Decision flags ---

    public boolean isDisabled() {
        return !enabled || !hasKey;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean hasKey() {
        return hasKey;
    }

    public void setHasKey(boolean hasKey) {
        this.hasKey = hasKey;
    }

    public boolean hasPending() {
        return hasPending;
    }

    public void setHasPending(boolean hasPending) {
        this.hasPending = hasPending;
    }

    public boolean isCanMerge() {
        return canMerge;
    }

    public void setCanMerge(boolean canMerge) {
        this.canMerge = canMerge;
    }

    public boolean isPendingNoMerge() {
        return hasPending && !canMerge;
    }

    public boolean isFirstCandidate() {
        return firstCandidate;
    }

    public void setFirstCandidate(boolean firstCandidate) {
        this.firstCandidate = firstCandidate;
    }

    public Object getCapturedPending() {
        return capturedPending;
    }

    public void setCapturedPending(Object capturedPending) {
        this.capturedPending = capturedPending;
    }

    // --- Output ---

    public CoalescingAction getResult() {
        return result;
    }

    public void setResult(CoalescingAction result) {
        this.result = result;
    }

    @Override
    public String toString() {
        return "CoalescingContext{state=" + state + ", enabled=" + enabled
                + ", hasPending=" + hasPending + ", canMerge=" + canMerge + '}';
    }
}
