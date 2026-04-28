package io.github.ngirchev.opendaimon.common.ai.pipeline.fsm;

/**
 * Events that drive the AI request pipeline FSM.
 *
 * <p>Only {@link #PREPARE} is fired externally. All subsequent transitions
 * are auto-transitions (null event) driven by conditions on the request context.
 */
public enum AIRequestEvent {

    /** Kick off request preparation pipeline. */
    PREPARE
}
