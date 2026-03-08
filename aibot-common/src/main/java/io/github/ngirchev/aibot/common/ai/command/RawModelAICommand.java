package io.github.ngirchev.aibot.common.ai.command;

import io.github.ngirchev.aibot.common.ai.ModelCapabilities;

import java.util.Map;
import java.util.Set;

public record RawModelAICommand(
        String modelTypeRaw,
        double temp,
        int maxTokens,
        String systemRole,
        String userRole,
        boolean stream,
        Map<String, String> metadata,
        Map<String, Object> body) implements AICommand {

    @Override
    public Set<ModelCapabilities> modelCapabilities() {
        return Set.of(ModelCapabilities.RAW_TYPE);
    }

    @Override
    public AIBotChatOptions options() {
        return new AIBotChatOptions(
                temp,
                maxTokens,
                systemRole,
                userRole,
                stream,
                body
        );
    }
}
