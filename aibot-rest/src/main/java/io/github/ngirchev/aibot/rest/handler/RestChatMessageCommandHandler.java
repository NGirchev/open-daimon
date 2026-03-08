package io.github.ngirchev.aibot.rest.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import io.github.ngirchev.aibot.common.ai.factory.AICommandFactoryRegistry;
import io.github.ngirchev.aibot.common.ai.command.AICommand;
import io.github.ngirchev.aibot.common.ai.response.AIResponse;
import io.github.ngirchev.aibot.common.ai.ModelCapabilities;
import io.github.ngirchev.aibot.common.command.ICommand;
import io.github.ngirchev.aibot.common.command.ICommandHandler;
import io.github.ngirchev.aibot.common.model.AssistantRole;
import io.github.ngirchev.aibot.common.model.ConversationThread;
import io.github.ngirchev.aibot.common.model.AIBotMessage;
import io.github.ngirchev.aibot.common.model.RequestType;
import io.github.ngirchev.aibot.common.model.ResponseStatus;
import io.github.ngirchev.aibot.common.service.*;
import io.github.ngirchev.aibot.bulkhead.exception.AccessDeniedException;
import io.github.ngirchev.aibot.common.exception.UserMessageTooLongException;
import io.github.ngirchev.aibot.rest.model.RestUser;
import io.github.ngirchev.aibot.rest.service.RestMessageService;
import io.github.ngirchev.aibot.rest.service.RestUserService;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static io.github.ngirchev.aibot.common.ai.command.AICommand.*;
import static io.github.ngirchev.aibot.common.service.AIUtils.*;

@Slf4j
@RequiredArgsConstructor
public class RestChatMessageCommandHandler implements
        ICommandHandler<RestChatCommandType, RestChatCommand, String> {

    private final RestMessageService restMessageService;
    private final RestUserService restUserService;
    private final AIBotMessageService messageService;
    private final AIGatewayRegistry aiGatewayRegistry;
    private final AICommandFactoryRegistry aiCommandFactoryRegistry;
    private final ObjectMapper objectMapper;
    private final MessageLocalizationService messageLocalizationService;

    @Override
    public boolean canHandle(ICommand<RestChatCommandType> command) {
        if (!(command instanceof RestChatCommand) || command.commandType() == null) {
            return false;
        }
        return command.commandType() == RestChatCommandType.MESSAGE;
    }

    @Override
    public int priority() {
        return 0;
    }

    @Override
    public String handle(RestChatCommand command) {
        AIBotMessage userMessage = null;
        Set<ModelCapabilities> modelCapabilities = Set.of();
        try {
            String lang = getRequestLanguage(command);
            RestUser user = restUserService.findById(command.userId())
                    .orElseThrow(() -> new RuntimeException(messageLocalizationService.getMessage("rest.user.not.found", lang, command.userId())));
            String assistantRoleContent = command.chatRequestDto().assistantRole() != null
                    ? command.chatRequestDto().assistantRole()
                    : null;
            userMessage = restMessageService.saveUserMessage(
                    user,
                    command.chatRequestDto().message(),
                    RequestType.TEXT,
                    assistantRoleContent,
                    command.request());
            ConversationThread thread = userMessage.getThread();
            AssistantRole assistantRole = userMessage.getAssistantRole();
            String assistantRoleContentFromRole = assistantRole.getContent();
            Long assistantRoleId = assistantRole.getId();
            Integer assistantRoleVersion = assistantRole.getVersion();
            log.info("Using conversation thread: {} with AssistantRole {} (v{})",
                    thread.getThreadKey(), assistantRoleId, assistantRoleVersion);
            long startTime = System.currentTimeMillis();
            Map<String, String> metadata = buildMetadata(thread, assistantRoleContentFromRole, assistantRoleId, user.getId());
            AICommand aiCommand = aiCommandFactoryRegistry.createCommand(command, metadata);
            modelCapabilities = aiCommand.modelCapabilities();
            AIGateway aiGateway = aiGatewayRegistry.getSupportedAiGateways(aiCommand)
                    .stream()
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException("No supported AI gateway found"));
            AIResponse aiResponse = aiGateway.generateResponse(aiCommand);
            return processSuccessResponse(user, aiResponse, modelCapabilities, assistantRole, assistantRoleContentFromRole, startTime);
        } catch (AccessDeniedException e) {
            log.warn("Access denied for user: {}", e.getMessage());
            throw e;
        } catch (UserMessageTooLongException e) {
            log.warn("Message exceeds token limit: {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            throw handleProcessingError(command, userMessage, modelCapabilities, e);
        }
    }

    private static String getRequestLanguage(RestChatCommand command) {
        return command.request() != null && command.request().getLocale() != null
                ? command.request().getLocale().getLanguage() : "ru";
    }

    private static Map<String, String> buildMetadata(ConversationThread thread, String assistantRoleContentFromRole,
                                                     Long assistantRoleId, Long userId) {
        Map<String, String> metadata = new HashMap<>();
        metadata.put(THREAD_KEY_FIELD, thread.getThreadKey());
        metadata.put(ASSISTANT_ROLE_ID_FIELD, assistantRoleId.toString());
        metadata.put(USER_ID_FIELD, userId.toString());
        metadata.put(ROLE_FIELD, assistantRoleContentFromRole);
        return metadata;
    }

    private String processSuccessResponse(RestUser user, AIResponse aiResponse, Set<ModelCapabilities> modelCapabilities,
                                          AssistantRole assistantRole, String assistantRoleContentFromRole, long startTime) {
        Map<String, Object> usefulResponseData = AIUtils.extractUsefulData(aiResponse);
        Optional<String> responseOpt = retrieveMessage(aiResponse);
        if (responseOpt.isEmpty()) {
            String errorMessage = extractError(aiResponse).orElse("Content is empty");
            messageService.saveAssistantErrorMessage(
                    user,
                    errorMessage,
                    modelCapabilities.toString(),
                    assistantRole,
                    usefulResponseData != null && !usefulResponseData.isEmpty() ? usefulResponseData.toString() : null);
            throw new RuntimeException(errorMessage);
        }
        String response = responseOpt.get();
        long processingTime = System.currentTimeMillis() - startTime;
        log.info("Gateway: [{}]. Model: [{}]", aiResponse.gatewaySource(), usefulResponseData.get("model"));
        AIBotMessage assistantMessage = messageService.saveAssistantMessage(
                user,
                response,
                modelCapabilities.toString(),
                assistantRoleContentFromRole,
                (int) processingTime,
                usefulResponseData);
        messageService.updateMessageStatus(assistantMessage, ResponseStatus.SUCCESS);
        return response;
    }

    private RuntimeException handleProcessingError(RestChatCommand command, AIBotMessage userMessage,
                                                    Set<ModelCapabilities> modelCapabilities, Exception e) {
        if (AIUtils.shouldLogWithoutStacktrace(e)) {
            log.error("Error processing REST API request: {}", AIUtils.getRootCauseMessage(e));
        } else {
            log.error("Error processing REST API request", e);
        }
        Set<ModelCapabilities> caps = modelCapabilities.isEmpty() ? Set.of(ModelCapabilities.CHAT) : modelCapabilities;
        Map<String, Object> errorMetadata = createErrorMetadata(caps, e);
        String errorDataJson = serializeToJson(errorMetadata);
        String lang = getRequestLanguage(command);
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
        return new RuntimeException(errorMessage, e);
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
