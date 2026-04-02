package io.github.ngirchev.opendaimon.common.ai.pipeline.fsm;

import io.github.ngirchev.fsm.Action;
import io.github.ngirchev.fsm.FsmFactory;
import io.github.ngirchev.fsm.Guard;
import io.github.ngirchev.fsm.StateContext;
import io.github.ngirchev.fsm.impl.extended.ExDomainFsm;

import java.util.function.Consumer;
import java.util.function.Predicate;

import static io.github.ngirchev.opendaimon.common.ai.pipeline.fsm.AttachmentEvent.PROCESS;
import static io.github.ngirchev.opendaimon.common.ai.pipeline.fsm.AttachmentState.*;

/**
 * Creates the document processing FSM with all transitions defined declaratively.
 *
 * <p>The FSM uses auto-transitions: a single {@link AttachmentEvent#PROCESS} event
 * triggers the initial transition, then the FSM automatically chains through states
 * based on conditions (guards) until reaching a terminal state.
 *
 * <p>Transition graph:
 * <pre>
 * RECEIVED ──[PROCESS]──▶ CLASSIFIED
 *     action: classify()
 *
 * CLASSIFIED ──[auto]──┬─[isImage]──────▶ IMAGE_PASSTHROUGH (terminal)
 *                      ├─[isDocument]───▶ ANALYZED
 *                      │   action: analyzeContent()
 *                      └─[else]─────────▶ ERROR
 *                          action: handleUnsupported()
 *
 * ANALYZED ──[auto]──┬─[textExtractable]──▶ TEXT_EXTRACTED
 *                    │   action: extractText()
 *                    ├─[imageOnly]────────▶ VISION_OCR_COMPLETE
 *                    │   action: runVisionOcr()
 *                    └─[else]─────────────▶ ERROR
 *                        action: handleUnsupported()
 *
 * TEXT_EXTRACTED ──[auto]──┬─[hasChunks]──▶ RAG_INDEXED (terminal)
 *                          │   action: confirmIndexed()
 *                          └─[noChunks]───▶ VISION_OCR_COMPLETE
 *                              action: runVisionOcr()  (fallback)
 *
 * VISION_OCR_COMPLETE ──[auto]──┬─[ocrSucceeded]──▶ RAG_INDEXED (terminal)
 *                               │   action: confirmIndexed()
 *                               └─[ocrFailed]─────▶ IMAGE_FALLBACK (terminal)
 * </pre>
 */
public final class DocumentPipelineFsmFactory {

    private DocumentPipelineFsmFactory() {
    }

    /**
     * Creates a stateless domain FSM that processes {@link AttachmentProcessingContext} objects.
     *
     * <p>The returned FSM is thread-safe and can be shared as a singleton Spring bean.
     * Each {@code handle(context, PROCESS)} call creates an internal FSM instance
     * scoped to that context.
     *
     * @param actions implementation of processing actions (injected by Spring)
     * @return domain FSM ready to process attachment contexts
     */
    public static ExDomainFsm<AttachmentProcessingContext, AttachmentState, AttachmentEvent> create(
            DocumentPipelineActions actions) {

        var table = FsmFactory.INSTANCE.<AttachmentState, AttachmentEvent>statesWithEvents()
                .autoTransitionEnabled(true)

                // === RECEIVED → CLASSIFIED (event-driven: PROCESS) ===
                .from(RECEIVED).onEvent(PROCESS).to(CLASSIFIED)
                    .action(action(actions::classify))
                    .end()

                // === CLASSIFIED → branch (auto-transition) ===
                .from(CLASSIFIED).toMultiple()
                    .to(IMAGE_PASSTHROUGH)
                        .onCondition(guard(AttachmentProcessingContext::isImage))
                        .end()
                    .to(ANALYZED)
                        .onCondition(guard(AttachmentProcessingContext::isDocument))
                        .action(action(actions::analyzeContent))
                        .end()
                    .to(ERROR)
                        .action(action(actions::handleUnsupported))
                        .end()
                    .endMultiple()

                // === ANALYZED → branch by content type (auto-transition) ===
                .from(ANALYZED).toMultiple()
                    .to(TEXT_EXTRACTED)
                        .onCondition(guard(AttachmentProcessingContext::isTextExtractable))
                        .action(action(actions::extractText))
                        .end()
                    .to(VISION_OCR_COMPLETE)
                        .onCondition(guard(AttachmentProcessingContext::isImageOnly))
                        .action(action(actions::runVisionOcr))
                        .end()
                    .to(ERROR)
                        .action(action(actions::handleUnsupported))
                        .end()
                    .endMultiple()

                // === TEXT_EXTRACTED → branch: has chunks or fallback to OCR (auto-transition) ===
                .from(TEXT_EXTRACTED).toMultiple()
                    .to(RAG_INDEXED)
                        .onCondition(guard(AttachmentProcessingContext::hasExtractedChunks))
                        .action(action(actions::confirmIndexed))
                        .end()
                    .to(VISION_OCR_COMPLETE)
                        .action(action(actions::runVisionOcr))
                        .end()
                    .endMultiple()

                // === VISION_OCR_COMPLETE → branch: OCR succeeded or image fallback (auto-transition) ===
                .from(VISION_OCR_COMPLETE).toMultiple()
                    .to(RAG_INDEXED)
                        .onCondition(guard(AttachmentProcessingContext::isVisionOcrSucceeded))
                        .action(action(actions::confirmIndexed))
                        .end()
                    .to(IMAGE_FALLBACK)
                        .end()
                    .endMultiple()

                .build();

        return table.createDomainFsm();
    }

    /**
     * Adapts a typed predicate on {@link AttachmentProcessingContext} to a
     * {@link Guard} on {@code StateContext<AttachmentState>} required by the FSM library.
     */
    private static Guard<StateContext<AttachmentState>> guard(
            Predicate<AttachmentProcessingContext> predicate) {
        return ctx -> predicate.test((AttachmentProcessingContext) ctx);
    }

    /**
     * Adapts a typed consumer on {@link AttachmentProcessingContext} to an
     * {@link Action} on {@code StateContext<AttachmentState>} required by the FSM library.
     */
    private static Action<StateContext<AttachmentState>> action(
            Consumer<AttachmentProcessingContext> consumer) {
        return ctx -> consumer.accept((AttachmentProcessingContext) ctx);
    }
}
