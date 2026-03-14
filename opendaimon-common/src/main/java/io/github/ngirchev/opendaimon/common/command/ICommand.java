package io.github.ngirchev.opendaimon.common.command;

public interface ICommand<T extends ICommandType> {
    Long userId();
    T commandType();
}
