package io.github.ngirchev.aibot.rest.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatResponse;
import reactor.core.publisher.Flux;
import io.github.ngirchev.aibot.bulkhead.exception.AccessDeniedException;
import io.github.ngirchev.aibot.common.exception.UserMessageTooLongException;
import io.github.ngirchev.aibot.common.ai.AIGateways;
import io.github.ngirchev.aibot.common.ai.ModelCapabilities;
import io.github.ngirchev.aibot.common.ai.command.AICommand;
import io.github.ngirchev.aibot.common.ai.factory.AICommandFactoryRegistry;
import io.github.ngirchev.aibot.common.ai.response.AIResponse;
import io.github.ngirchev.aibot.common.ai.response.SpringAIStreamResponse;
import io.github.ngirchev.aibot.common.command.ICommand;
import io.github.ngirchev.aibot.common.command.ICommandHandler;
import io.github.ngirchev.aibot.common.model.*;
import io.github.ngirchev.aibot.common.service.*;
import io.github.ngirchev.aibot.rest.model.RestUser;
import io.github.ngirchev.aibot.rest.service.RestMessageService;
import io.github.ngirchev.aibot.rest.service.RestUserService;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.ngirchev.aibot.common.ai.command.AICommand.*;
import static io.github.ngirchev.aibot.common.service.AIUtils.extractError;

@Slf4j
@RequiredArgsConstructor
public class RestChatStreamMessageCommandHandler implements
        ICommandHandler<RestChatCommandType, RestChatCommand, Flux<String>> {

    private final RestMessageService restMessageService;
    private final RestUserService restUserService;
    private final AIBotMessageService messageService;
    private final AIGatewayRegistry aiGatewayRegistry;
    private final AICommandFactoryRegistry aiCommandFactoryRegistry;
    private final ObjectMapper objectMapper;
    private final MessageLocalizationService messageLocalizationService;

    @Override
    public boolean canHandle(ICommand<RestChatCommandType> command) {
        return command instanceof RestChatCommand
                && command.commandType() != null
                && command.commandType() == RestChatCommandType.STREAM;
    }

    @Override
    public int priority() {
        return 0;
    }

    @Override
    public Flux<String> handle(RestChatCommand command) {
        AIBotMessage userMessage = null;
        Set<ModelCapabilities> modelCapabilities = Set.of();
        ConversationThread thread;

        try {
            String lang = command.request() != null && command.request().getLocale() != null
                    ? command.request().getLocale().getLanguage() : "ru";
            RestUser user = restUserService.findById(command.userId())
                    .orElseThrow(() -> new RuntimeException(messageLocalizationService.getMessage("rest.user.not.found", lang, command.userId())));

            // Determine assistant role content: from request or from property
            String assistantRoleContent = command.chatRequestDto().assistantRole() != null
                    ? command.chatRequestDto().assistantRole()
                    : null; // If null, service uses default from coreCommonProperties

            // Save user request
            // Thread and role are obtained or created inside saveUserMessage
            userMessage = restMessageService.saveUserMessage(
                    user,
                    command.chatRequestDto().message(),
                    RequestType.TEXT,
                    assistantRoleContent,
                    command.request());

            // Get thread and role from saved message for further use
            thread = userMessage.getThread();
            AssistantRole assistantRole = userMessage.getAssistantRole();
            String assistantRoleContentFromRole = assistantRole.getContent();
            Integer assistantRoleVersion = assistantRole.getVersion();
            Long assistantRoleId = assistantRole.getId();

            log.info("Using conversation thread: {} with AssistantRole {} (v{})",
                    thread.getThreadKey(), assistantRoleId, assistantRoleVersion);

            // Process request and get response
            long startTime = System.currentTimeMillis();

            // Pass metadata required for context building
            // RestConversationHistoryAiCommandFactory uses ContextBuilderService for context
            // If metadata has threadKey - RestConversationHistoryAiCommandFactory is used
            // Otherwise DefaultAiCommandFactory (fallback)
            Map<String, String> metadata = new HashMap<>();
            metadata.put(THREAD_KEY_FIELD, thread.getThreadKey());
            metadata.put(ASSISTANT_ROLE_ID_FIELD, assistantRoleId.toString());
            metadata.put(USER_ID_FIELD, user.getId().toString());
            // For backward compatibility also pass role (for fallback to DefaultAiCommandFactory)
            metadata.put(ROLE_FIELD, assistantRoleContentFromRole);

            AICommand aiCommand = aiCommandFactoryRegistry.createCommand(command, metadata);
            modelCapabilities = aiCommand.modelCapabilities();

            AIGateway aiGateway = aiGatewayRegistry.getSupportedAiGateways(aiCommand)
                    .stream()
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException("No supported AI gateway found"));
            AIResponse aiResponse = aiGateway.generateResponse(aiCommand);
            if (aiResponse.gatewaySource() == AIGateways.SPRINGAI && aiResponse instanceof SpringAIStreamResponse aiStreamResponse) {

                AtomicReference<ChatResponse> lastResponse = new AtomicReference<>(null);
                StringBuilder fullResponse = new StringBuilder();

                Set<ModelCapabilities> finalModelCapabilities = modelCapabilities;
                return aiStreamResponse.chatResponse()
                        .doOnNext(lastResponse::set)
                        .map(AIUtils::extractText)
                        .filter(Optional::isPresent)
                        .map(Optional::get)
                        .doOnNext(fullResponse::append)
                        // Split each chunk into characters (codepoints, preserves emoji)
                        .flatMap(chunk -> Flux.fromStream(chunk.codePoints().mapToObj(cp -> new String(Character.toChars(cp)))))
                        // DB save is done once at end of stream (not per chunk)
                        .doOnComplete(() -> saveToDatabase(user, finalModelCapabilities, assistantRole, assistantRoleContent, startTime, fullResponse.toString(), lastResponse.get()))
                        .doOnCancel(() -> saveToDatabase(user, finalModelCapabilities, assistantRole, assistantRoleContent, startTime, fullResponse.toString(), lastResponse.get()));
            } else {
                throw new IllegalStateException("Expected streaming message");
            }
        } catch (AccessDeniedException e) {
            // Re-throw AccessDeniedException without wrapping for proper handling in RestExceptionHandler
            log.warn("Access denied for user: {}", e.getMessage());
            throw e;
        } catch (UserMessageTooLongException e) {
            log.warn("Message exceeds token limit: {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            if (AIUtils.shouldLogWithoutStacktrace(e)) {
                log.error("Error processing REST API request: {}", AIUtils.getRootCauseMessage(e));
            } else {
                log.error("Error processing REST API request", e);
            }

            // Create error metadata and serialize to JSON
            Map<String, Object> errorMetadata = createErrorMetadata(modelCapabilities.isEmpty() ? Set.of(ModelCapabilities.CHAT) : modelCapabilities, e);
            String errorDataJson = serializeToJson(errorMetadata);

            String lang = command.request() != null && command.request().getLocale() != null
                    ? command.request().getLocale().getLanguage() : "ru";
            String errorMessage = messageLocalizationService.getMessage("rest.error.processing", lang, e.getMessage());
            if (userMessage != null) {
                String errorRoleContent = userMessage.getAssistantRole() != null
                        ? userMessage.getAssistantRole().getContent()
                        : null;
                messageService.saveAssistantErrorMessage(
                        userMessage.getUser(),
                        errorMessage,
                        modelCapabilities.toString(),
                        errorRoleContent,
                        errorDataJson);
            }
            throw new RuntimeException(errorMessage, e);
        }
    }

    private void saveToDatabase(RestUser user,
                                Set<ModelCapabilities> modelCapabilities,
                                AssistantRole assistantRole,
                                String assistantRoleContentFromRole,
                                long startTime,
                                String fullMessage,
                                ChatResponse chatResponse) {
        Map<String, Object> usefulResponseData = AIUtils.extractSpringAiUsefulData(chatResponse);

        // Try to get content from response
        if (fullMessage.isEmpty()) {
            // If content is empty, use error from AIResponse
            String errorMessage = extractError(chatResponse).orElse("Content is empty");

            // Save error to DB with finish_reason in response_data
            messageService.saveAssistantErrorMessage(
                    user,
                    errorMessage,
                    modelCapabilities.toString(),
                    assistantRole,
                    usefulResponseData != null && !usefulResponseData.isEmpty()
                            ? usefulResponseData.toString()
                            : null);

            throw new RuntimeException(errorMessage);
        }

        long processingTime = System.currentTimeMillis() - startTime;

        log.info("Gateway: [{}]. Model: [{}]", AIGateways.SPRINGAI, chatResponse.getMetadata().getModel());

        // Save service response
        // Thread and role are obtained or created inside saveAssistantMessage
        AIBotMessage assistantMessage = messageService.saveAssistantMessage(
                user,
                fullMessage,
                modelCapabilities.toString(),
                assistantRoleContentFromRole,
                (int) processingTime,
                usefulResponseData);

        // Update response status to SUCCESS
        messageService.updateMessageStatus(assistantMessage, ResponseStatus.SUCCESS);
    }

    /**
     * Creates metadata for error
     */
    private Map<String, Object> createErrorMetadata(Set<ModelCapabilities> modelCapabilities, Exception error) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("model", modelCapabilities.toString());
        metadata.put("errorType", error.getClass().getSimpleName());
        metadata.put("errorMessage", error.getMessage());
        metadata.put("timestamp", System.currentTimeMillis());
        return metadata;
    }

    /**
     * Serializes metadata to JSON
     */
    private String serializeToJson(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (Exception e) {
            log.warn("Failed to serialize metadata: {}", e.getMessage());
            return null;
        }
    }
}
