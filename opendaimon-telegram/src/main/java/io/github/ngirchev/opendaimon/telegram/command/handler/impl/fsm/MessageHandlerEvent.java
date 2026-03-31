package io.github.ngirchev.opendaimon.telegram.command.handler.impl.fsm;

/**
 * Events that drive the message handler FSM.
 *
 * <p>Only {@link #HANDLE} is fired externally. All subsequent transitions
 * are auto-transitions (null event) driven by conditions on the handler context.
 */
public enum MessageHandlerEvent {

    /** Kick off message processing. */
    HANDLE
}
