package io.github.ngirchev.opendaimon.common.command;

import io.github.ngirchev.opendaimon.common.model.Attachment;

import java.util.List;

/**
 * Wrapper over an original {@link IChatCommand} that substitutes the user text
 * and attachments with data produced by document orchestration (RAG preprocessing).
 *
 * <p>Used by {@link io.github.ngirchev.opendaimon.common.ai.pipeline.AIRequestPipeline}
 * to pass orchestrated data to the command factory without modifying the original command.
 *
 * @param <T> command type
 */
public class OrchestratedChatCommand<T extends ICommandType> implements IChatCommand<T> {

    private final IChatCommand<T> delegate;
    private final String augmentedUserText;
    private final List<Attachment> modifiedAttachments;

    public OrchestratedChatCommand(IChatCommand<T> delegate,
                                    String augmentedUserText,
                                    List<Attachment> modifiedAttachments) {
        this.delegate = delegate;
        this.augmentedUserText = augmentedUserText;
        this.modifiedAttachments = modifiedAttachments;
    }

    @Override
    public String userText() {
        return augmentedUserText;
    }

    @Override
    public List<Attachment> attachments() {
        return modifiedAttachments;
    }

    @Override
    public boolean stream() {
        return delegate.stream();
    }

    @Override
    public Long userId() {
        return delegate.userId();
    }

    @Override
    public T commandType() {
        return delegate.commandType();
    }
}
