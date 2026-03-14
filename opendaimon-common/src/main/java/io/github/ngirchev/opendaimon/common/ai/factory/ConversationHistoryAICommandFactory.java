package io.github.ngirchev.opendaimon.common.ai.factory;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import io.github.ngirchev.opendaimon.common.ai.ModelCapabilities;
import io.github.ngirchev.opendaimon.common.ai.command.AICommand;
import io.github.ngirchev.opendaimon.common.ai.command.ChatAICommand;
import io.github.ngirchev.opendaimon.common.command.ICommand;
import io.github.ngirchev.opendaimon.common.command.IChatCommand;
import io.github.ngirchev.opendaimon.common.model.Attachment;
import io.github.ngirchev.opendaimon.common.model.AssistantRole;
import io.github.ngirchev.opendaimon.common.model.AttachmentType;
import io.github.ngirchev.opendaimon.common.model.ConversationThread;
import io.github.ngirchev.opendaimon.common.service.AssistantRoleService;
import io.github.ngirchev.opendaimon.common.service.ConversationContextBuilderService;
import io.github.ngirchev.opendaimon.common.service.ConversationThreadService;
import io.github.ngirchev.opendaimon.common.service.SummarizationService;

import java.util.*;

import static io.github.ngirchev.opendaimon.common.ai.LlmParamNames.CONVERSATION_ID;
import static io.github.ngirchev.opendaimon.common.ai.LlmParamNames.MESSAGES;
import static io.github.ngirchev.opendaimon.common.ai.ModelCapabilities.*;
import static io.github.ngirchev.opendaimon.common.ai.command.AICommand.*;

/**
 * Factory for creating AI commands with conversation history support.
 * Uses ContextBuilderService to build context with dialog history.
 * <p>
 * Supports commands when metadata contains threadKey.
 * When threadKey is absent, DefaultAiCommandFactory is used (fallback).
 */
@RequiredArgsConstructor
@Slf4j
public class ConversationHistoryAICommandFactory implements AICommandFactory<AICommand, IChatCommand<?>> {

    private final int maxOutputTokens;
    private final Integer maxReasoningTokens;
    private final ConversationContextBuilderService contextBuilder;
    private final ConversationThreadService threadService;
    private final AssistantRoleService assistantRoleService;
    private final SummarizationService summarizationService;

    @Override
    public int priority() {
        return 0;
    }

    /**
     * Supports commands with conversation history (when metadata has threadKey)
     */
    @Override
    public boolean supports(ICommand<?> input, Map<String, String> metadata) {
        return metadata != null
                && metadata.containsKey(THREAD_KEY_FIELD);
    }

    @Override
    public AICommand createCommand(IChatCommand<?> command, Map<String, String> metadata) {
        if (command.userText() == null) {
            throw new IllegalStateException("User text is required for message command");
        }

        String userText = command.userText();
        String threadKey = metadata.get(THREAD_KEY_FIELD);
        String assistantRoleIdStr = metadata.get(ASSISTANT_ROLE_ID_FIELD);
        String userIdStr = metadata.get(USER_ID_FIELD);

        if (threadKey == null || assistantRoleIdStr == null || userIdStr == null) {
            throw new IllegalStateException(
                    "Conversation history requires threadKey, assistantRoleId and userId in metadata");
        }

        try {
            Long assistantRoleId = Long.parseLong(assistantRoleIdStr);
            AssistantRole assistantRole = assistantRoleService.findById(assistantRoleId)
                    .orElseThrow(() -> new IllegalStateException("AssistantRole not found: " + assistantRoleId));
            ConversationThread thread = threadService.findByThreadKey(threadKey)
                    .orElseThrow(() -> new IllegalStateException("Thread not found: " + threadKey));

            // Check if summarization is needed BEFORE building context
            // Factory runs only when manual-conversation-history.enabled=true (not Spring AI),
            // so we always need to check summarization here
            if (summarizationService.shouldTriggerSummarization(thread)) {
                log.info("Thread {} reached summarization threshold, triggering async summary before building context",
                        thread.getThreadKey());
                summarizationService.summarizeThreadAsync(thread);
            }

            // Build context with history (content may be String or List of content parts for multimodal)
            List<Map<String, Object>> messages = contextBuilder.buildContext(
                    thread,
                    userText,
                    assistantRole
            );

            log.debug("Built context with {} messages for thread {}", messages.size(), threadKey);

            // Pass messages via body
            // IMPORTANT: In current implementation SpringAIGateway.generateResponse(AICommand command)
            // body.messages is NOT used - messages are created only from chatOptions.
            // So history from ConversationContextBuilderService does not reach the request
            // via this path. History must be passed through another mechanism (e.g. Spring AI ChatMemory).
            Map<String, Object> body = new HashMap<>();
            body.put(MESSAGES, messages);

            body.put(CONVERSATION_ID, threadKey);

            List<Attachment> attachments = command.attachments() != null 
                    ? command.attachments() 
                    : List.of();

            // Determine modelTypes dynamically - add VISION if there are images
            Set<ModelCapabilities> modelCapabilities = determineModelTypes(attachments);

            // TODO add vip/regular logic
            // Temperature 0.35 for general assistant (recommended range: 0.3-0.4)
            String systemRole = buildSystemRole(assistantRole.getContent(), metadata.get(LANGUAGE_CODE_FIELD));
            return new ChatAICommand(
                    modelCapabilities,
                    0.35,
                    maxOutputTokens,
                    maxReasoningTokens,
                    systemRole,
                    command.userText(),
                    command.stream(),
                    metadata,
                    body,
                    attachments
            );
        } catch (Exception e) {
            log.error("Failed to build context with history for thread {}: {}", threadKey, e.getMessage(), e);
            throw new RuntimeException("Failed to create AI command with conversation history", e);
        }
    }

    private static String buildSystemRole(String roleContent, String languageCode) {
        if (languageCode == null || languageCode.isBlank()) {
            return roleContent;
        }
        String languageName = switch (languageCode.toLowerCase()) {
            case "ru" -> "Russian";
            case "en" -> "English";
            case "de" -> "German";
            case "fr" -> "French";
            case "es" -> "Spanish";
            case "zh" -> "Chinese";
            default -> languageCode;
        };
        return roleContent + "\nIMPORTANT: Always respond in " + languageName + " (" + languageCode + ").";
    }

    /**
     * Determines ModelTypes for the command.
     * Adds VISION if there are image attachments.
     */
    private Set<ModelCapabilities> determineModelTypes(List<Attachment> attachments) {
        boolean hasImages = attachments.stream()
                .anyMatch(a -> a.type() == AttachmentType.IMAGE);
        
        if (hasImages) {
            return Set.of(CHAT, VISION);
        }
        return Set.of(CHAT);
    }
}

