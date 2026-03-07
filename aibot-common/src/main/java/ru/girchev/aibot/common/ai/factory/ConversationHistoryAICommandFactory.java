package ru.girchev.aibot.common.ai.factory;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ru.girchev.aibot.common.ai.LlmParamNames;
import ru.girchev.aibot.common.ai.ModelType;
import ru.girchev.aibot.common.ai.command.AICommand;
import ru.girchev.aibot.common.ai.command.ChatAICommand;
import ru.girchev.aibot.common.command.ICommand;
import ru.girchev.aibot.common.command.IChatCommand;
import ru.girchev.aibot.common.model.Attachment;
import ru.girchev.aibot.common.model.AssistantRole;
import ru.girchev.aibot.common.model.AttachmentType;
import ru.girchev.aibot.common.model.ConversationThread;
import ru.girchev.aibot.common.service.AssistantRoleService;
import ru.girchev.aibot.common.service.ConversationContextBuilderService;
import ru.girchev.aibot.common.service.ConversationThreadService;
import ru.girchev.aibot.common.service.SummarizationService;

import java.util.*;

import static ru.girchev.aibot.common.ai.LlmParamNames.CONVERSATION_ID;
import static ru.girchev.aibot.common.ai.LlmParamNames.MESSAGES;
import static ru.girchev.aibot.common.ai.command.AICommand.*;

/**
 * Фабрика для создания AI команд с поддержкой conversation history.
 * Использует ContextBuilderService для построения контекста с историей диалога.
 * <p>
 * Поддерживает команды, если в metadata есть threadKey.
 * Если threadKey отсутствует, используется DefaultAiCommandFactory (fallback).
 */
@RequiredArgsConstructor
@Slf4j
public class ConversationHistoryAICommandFactory implements AICommandFactory<AICommand, IChatCommand<?>> {

    private final ConversationContextBuilderService contextBuilder;
    private final ConversationThreadService threadService;
    private final AssistantRoleService assistantRoleService;
    private final SummarizationService summarizationService;

    @Override
    public int priority() {
        return 0;
    }

    /**
     * Поддерживает команды с conversation history (если в metadata есть threadKey)
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

            // Проверяем необходимость суммаризации ДО построения контекста
            // Фабрика работает только когда conversation-context.enabled=true (не Spring AI),
            // поэтому здесь всегда нужно проверять суммаризацию
            if (summarizationService.shouldTriggerSummarization(thread)) {
                log.info("Thread {} reached summarization threshold, triggering async summary before building context",
                        thread.getThreadKey());
                summarizationService.summarizeThreadAsync(thread);
            }

            // Строим контекст с историей
            List<Map<String, String>> messages = contextBuilder.buildContext(
                    thread,
                    userText,
                    assistantRole
            );

            log.debug("Built context with {} messages for thread {}", messages.size(), threadKey);

            // Передаем messages через body
            // ВАЖНО: В текущей реализации SpringAIGateway.generateResponse(AICommand command) 
            // body.messages НЕ используется - messages создаются только из chatOptions.
            // Это означает, что история из ConversationContextBuilderService не попадает в запрос
            // через этот путь. История должна передаваться через другой механизм (например, Spring AI ChatMemory).
            Map<String, Object> body = new HashMap<>();
            body.put(MESSAGES, messages);

            body.put(CONVERSATION_ID, threadKey);

            List<Attachment> attachments = command.attachments() != null 
                    ? command.attachments() 
                    : List.of();

            // Динамически определяем modelTypes - добавляем VISION если есть изображения
            Set<ModelType> modelTypes = determineModelTypes(attachments);

            // TODO add vip/regular logic
            // Температура 0.35 для бытового ассистента (рекомендуемый диапазон: 0.3-0.4)
            return new ChatAICommand(
                    modelTypes,
                    0.35,
                    1000,
                    assistantRole.getContent(),
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

    /**
     * Определяет ModelTypes для команды.
     * Добавляет VISION если есть image attachments.
     */
    private Set<ModelType> determineModelTypes(List<Attachment> attachments) {
        boolean hasImages = attachments.stream()
                .anyMatch(a -> a.type() == AttachmentType.IMAGE);
        
        if (hasImages) {
            return Set.of(ModelType.CHAT, ModelType.VISION);
        }
        return Set.of(ModelType.CHAT);
    }
}

