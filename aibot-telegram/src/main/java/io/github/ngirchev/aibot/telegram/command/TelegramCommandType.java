package io.github.ngirchev.aibot.telegram.command;

import io.github.ngirchev.aibot.common.command.ICommandType;

public record TelegramCommandType(String command) implements ICommandType {
} 