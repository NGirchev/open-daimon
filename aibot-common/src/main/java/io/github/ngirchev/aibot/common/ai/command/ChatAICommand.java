package io.github.ngirchev.aibot.common.ai.command;

import io.github.ngirchev.aibot.common.ai.ModelCapabilities;
import io.github.ngirchev.aibot.common.model.Attachment;
import io.github.ngirchev.aibot.common.model.AttachmentType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record ChatAICommand(
        Set<ModelCapabilities> modelCapabilities,
        double temp,
        int maxTokens,
        Integer maxReasoningTokens,
        String systemRole,
        String userRole,
        boolean stream,
        Map<String, String> metadata,
        Map<String, Object> body,
        List<Attachment> attachments) implements AICommand {

    /**
     * Constructor without attachments for backward compatibility.
     */
    public ChatAICommand(Set<ModelCapabilities> modelCapabilities, double temp, int maxTokens,
                         String systemRole, String userRole, boolean stream,
                         Map<String, String> metadata, Map<String, Object> body) {
        this(modelCapabilities, temp, maxTokens, null, systemRole, userRole, stream, metadata, body, List.of());
    }

    public ChatAICommand(Set<ModelCapabilities> modelCapabilities, double temp, int maxTokens, String systemRole, String userRole) {
        this(modelCapabilities, temp, maxTokens, null, systemRole, userRole, false, new HashMap<>(), new HashMap<>(), List.of());
    }

    public ChatAICommand(Set<ModelCapabilities> modelCapabilities, double temp, int maxTokens, String systemRole, String userRole, Map<String, String> metadata) {
        this(modelCapabilities, temp, maxTokens, null, systemRole, userRole, false, metadata, new HashMap<>(), List.of());
    }

    /**
     * Checks if there are image attachments.
     */
    public boolean hasImageAttachments() {
        return attachments != null && attachments.stream()
                .anyMatch(a -> a.type() == AttachmentType.IMAGE);
    }

    @Override
    public AIBotChatOptions options() {
        Map<String, Object> optionsBody = body != null ? new HashMap<>(body) : new HashMap<>();
        if (maxReasoningTokens != null) {
            optionsBody.put("reasoning", Map.of("max_tokens", maxReasoningTokens));
        }
        return new AIBotChatOptions(
                temp,
                maxTokens,
                systemRole,
                userRole,
                stream,
                optionsBody
        );
    }
}
