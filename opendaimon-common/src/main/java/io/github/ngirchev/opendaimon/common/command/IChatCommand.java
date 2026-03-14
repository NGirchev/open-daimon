package io.github.ngirchev.opendaimon.common.command;

import io.github.ngirchev.opendaimon.common.model.Attachment;

import java.util.List;

public interface IChatCommand<T extends ICommandType> extends ICommand<T> {
    String userText();
    boolean stream();
    
    /**
     * Returns list of attachments (images, PDF documents).
     * Default returns empty list for backward compatibility.
     */
    default List<Attachment> attachments() {
        return List.of();
    }
}
