package io.github.ngirchev.aibot.common.ai.factory;

import io.github.ngirchev.aibot.common.ai.command.AICommand;
import io.github.ngirchev.aibot.common.command.ICommand;

import java.util.Map;

public interface AICommandFactory<A extends AICommand, C extends ICommand<?>> {
    int priority();
    boolean supports(ICommand<?> input, Map<String, String> metadata);
    A createCommand(C command, Map<String, String> metadata);
}
