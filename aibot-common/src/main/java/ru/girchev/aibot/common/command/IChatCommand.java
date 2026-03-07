package ru.girchev.aibot.common.command;

import ru.girchev.aibot.common.model.Attachment;

import java.util.List;

public interface IChatCommand<T extends ICommandType> extends ICommand<T> {
    String userText();
    boolean stream();
    
    /**
     * Возвращает список вложений (изображения, PDF документы).
     * По умолчанию возвращает пустой список для обратной совместимости.
     */
    default List<Attachment> attachments() {
        return List.of();
    }
}
