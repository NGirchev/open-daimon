package io.github.ngirchev.opendaimon.rest.handler;

import io.github.ngirchev.opendaimon.common.command.ICommandType;

public enum RestChatCommandType implements ICommandType {
    MESSAGE,
    STREAM
}
