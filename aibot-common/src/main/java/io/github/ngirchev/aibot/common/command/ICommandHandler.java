package io.github.ngirchev.aibot.common.command;

public interface ICommandHandler<T extends ICommandType, C extends ICommand<T>, R> {
    int priority();
    boolean canHandle(ICommand<T> command);
    R handle(C command);
}
