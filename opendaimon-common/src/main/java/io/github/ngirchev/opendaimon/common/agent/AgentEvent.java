package io.github.ngirchev.opendaimon.common.agent;

/**
 * Events for the ReAct agent loop FSM.
 *
 * <p>Only {@link #START} is an external event. All subsequent state transitions
 * are auto-transitions driven by guards on {@link AgentContext}.
 */
public enum AgentEvent {

    /** Kicks off the agent loop: INITIALIZED -> THINKING. */
    START
}
