package io.github.ngirchev.opendaimon.common.ai.pipeline;

import io.github.ngirchev.fsm.impl.extended.ExDomainFsm;
import io.github.ngirchev.opendaimon.common.ai.command.AICommand;
import io.github.ngirchev.opendaimon.common.ai.factory.AICommandFactoryRegistry;
import io.github.ngirchev.opendaimon.common.ai.pipeline.fsm.AIRequestContext;
import io.github.ngirchev.opendaimon.common.ai.pipeline.fsm.AIRequestEvent;
import io.github.ngirchev.opendaimon.common.ai.pipeline.fsm.AIRequestState;
import io.github.ngirchev.opendaimon.common.command.ICommand;
import io.github.ngirchev.opendaimon.common.exception.DocumentContentNotExtractableException;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * Orchestrates the full request preparation pipeline using a Finite State Machine.
 *
 * <p>Handlers call {@link #prepareCommand} instead of {@code AICommandFactoryRegistry.createCommand()}
 * directly. This ensures document processing (RAG indexing, vision OCR) happens BEFORE
 * the command factory determines model capabilities.
 *
 * <p>Flow (via FSM):
 * <ol>
 *   <li>VALIDATE — check command type, parse attachments</li>
 *   <li>CLASSIFY — route to passthrough / follow-up RAG / document processing / error</li>
 *   <li>Process through the appropriate path</li>
 *   <li>Build AICommand with correct capabilities</li>
 * </ol>
 *
 * <p>When no FSM is available (RAG disabled), falls through to factory directly.
 *
 * @see AIRequestContext
 * @see io.github.ngirchev.opendaimon.common.ai.pipeline.fsm.AIRequestPipelineFsmFactory
 */
@Slf4j
public class AIRequestPipeline {

    private final ExDomainFsm<AIRequestContext, AIRequestState, AIRequestEvent> requestFsm;
    private final AICommandFactoryRegistry factoryRegistry;

    public AIRequestPipeline(
            ExDomainFsm<AIRequestContext, AIRequestState, AIRequestEvent> requestFsm,
            AICommandFactoryRegistry factoryRegistry) {
        this.requestFsm = requestFsm;
        this.factoryRegistry = factoryRegistry;
    }

    /**
     * Prepares an AICommand by running the request pipeline FSM.
     *
     * @param command  original chat command from handler
     * @param metadata mutable metadata map (stores ragDocumentIds, pdfAsImageFilenames)
     * @return AICommand with correct capabilities and augmented query
     */
    public AICommand prepareCommand(ICommand<?> command, Map<String, String> metadata) {
        if (requestFsm == null) {
            return factoryRegistry.createCommand(command, metadata);
        }

        AIRequestContext ctx = new AIRequestContext(command, metadata);
        requestFsm.handle(ctx, AIRequestEvent.PREPARE);

        if (ctx.isError()) {
            throw new DocumentContentNotExtractableException(ctx.getErrorMessage());
        }

        return ctx.getResult();
    }
}
