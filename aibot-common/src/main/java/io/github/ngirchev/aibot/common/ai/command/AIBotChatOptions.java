package io.github.ngirchev.aibot.common.ai.command;

import java.util.Map;

public record AIBotChatOptions(
        double temp,
        int maxTokens,
        String systemRole,
        String userRole,
        boolean stream,
        Map<String, Object> body) implements AICommandOptions {
}
