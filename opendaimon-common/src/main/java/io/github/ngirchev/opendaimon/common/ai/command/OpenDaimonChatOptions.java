package io.github.ngirchev.opendaimon.common.ai.command;

import java.util.Map;

public record OpenDaimonChatOptions(
        double temp,
        int maxTokens,
        String systemRole,
        String userRole,
        boolean stream,
        Map<String, Object> body) implements AICommandOptions {
}
