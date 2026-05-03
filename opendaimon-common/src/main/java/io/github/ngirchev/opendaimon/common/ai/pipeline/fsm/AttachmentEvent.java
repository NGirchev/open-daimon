package io.github.ngirchev.opendaimon.common.ai.pipeline.fsm;

/**
 * Events that drive the document processing FSM.
 *
 * <p>Only {@link #PROCESS} is fired externally. All subsequent transitions
 * are auto-transitions (null event) driven by conditions on the processing context.
 */
public enum AttachmentEvent {

    /** Kick off processing for a single attachment. */
    PROCESS
}
