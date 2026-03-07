package ru.girchev.aibot.common.ai.command;

import ru.girchev.aibot.common.ai.ModelType;
import ru.girchev.aibot.common.model.Attachment;
import ru.girchev.aibot.common.model.AttachmentType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record ChatAICommand(
        Set<ModelType> modelTypes,
        double temp,
        int maxTokens,
        String systemRole,
        String userRole,
        boolean stream,
        Map<String, String> metadata,
        Map<String, Object> body,
        List<Attachment> attachments) implements AICommand {

    /**
     * Конструктор без attachments для обратной совместимости.
     */
    public ChatAICommand(Set<ModelType> modelTypes, double temp, int maxTokens, 
                         String systemRole, String userRole, boolean stream,
                         Map<String, String> metadata, Map<String, Object> body) {
        this(modelTypes, temp, maxTokens, systemRole, userRole, stream, metadata, body, List.of());
    }

    public ChatAICommand(Set<ModelType> modelTypes, double temp, int maxTokens, String systemRole, String userRole) {
        this(modelTypes, temp, maxTokens, systemRole, userRole, false, new HashMap<>(), new HashMap<>(), List.of());
    }

    public ChatAICommand(Set<ModelType> modelTypes, double temp, int maxTokens, String systemRole, String userRole, Map<String, String> metadata) {
        this(modelTypes, temp, maxTokens, systemRole, userRole, false, metadata, new HashMap<>(), List.of());
    }

    /**
     * Проверяет, есть ли вложения с изображениями.
     */
    public boolean hasImageAttachments() {
        return attachments != null && attachments.stream()
                .anyMatch(a -> a.type() == AttachmentType.IMAGE);
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
