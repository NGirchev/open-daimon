package io.github.ngirchev.opendaimon.telegram.command;

import io.github.ngirchev.opendaimon.common.command.ICommandType;

public record TelegramCommandType(String command) implements ICommandType {
} 