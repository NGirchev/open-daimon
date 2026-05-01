package io.github.ngirchev.opendaimon.rest.command;

import io.github.ngirchev.opendaimon.common.command.ICommandType;

public enum RestChatCommandType implements ICommandType {
    MESSAGE,
    STREAM
}
