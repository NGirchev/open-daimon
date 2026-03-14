package io.github.ngirchev.opendaimon.rest.handler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import io.github.ngirchev.opendaimon.common.ai.factory.AICommandFactoryRegistry;
import io.github.ngirchev.opendaimon.common.ai.command.AICommand;
import io.github.ngirchev.opendaimon.common.ai.response.AIResponse;
import io.github.ngirchev.opendaimon.common.ai.ModelCapabilities;
import io.github.ngirchev.opendaimon.common.command.ICommand;
import io.github.ngirchev.opendaimon.common.command.ICommandHandler;
import io.github.ngirchev.opendaimon.common.model.AssistantRole;
import io.github.ngirchev.opendaimon.common.model.ConversationThread;
import io.github.ngirchev.opendaimon.common.model.OpenDaimonMessage;
import io.github.ngirchev.opendaimon.common.model.RequestType;
import io.github.ngirchev.opendaimon.common.model.ResponseStatus;
import io.github.ngirchev.opendaimon.common.service.*;
import io.github.ngirchev.opendaimon.bulkhead.exception.AccessDeniedException;
import io.github.ngirchev.opendaimon.common.exception.UserMessageTooLongException;
import io.github.ngirchev.opendaimon.rest.model.RestUser;
import io.github.ngirchev.opendaimon.rest.service.RestMessageService;
import io.github.ngirchev.opendaimon.rest.service.RestUserService;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static io.github.ngirchev.opendaimon.common.service.AIUtils.*;

@Slf4j
@RequiredArgsConstructor
public class RestChatMessageCommandHandler implements
        ICommandHandler<RestChatCommandType, RestChatCommand, String> {

    private final RestMessageService restMessageService;
    private final RestUserService restUserService;
    private final OpenDaimonMessageService messageService;
    private final AIGatewayRegistry aiGatewayRegistry;
    private final AICommandFactoryRegistry aiCommandFactoryRegistry;
    private final RestChatHandlerSupport support;

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
        OpenDaimonMessage userMessage = null;
        Set<ModelCapabilities> modelCapabilities = Set.of();
        try {
            String lang = RestChatHandlerSupport.getRequestLanguage(command);
            RestUser user = restUserService.findById(command.userId())
                    .orElseThrow(() -> new RuntimeException(support.getMessageLocalizationService().getMessage("rest.user.not.found", lang, command.userId())));
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
            Map<String, String> metadata = RestChatHandlerSupport.buildMetadata(thread, assistantRoleContentFromRole, assistantRoleId, user.getId());
            AICommand aiCommand = aiCommandFactoryRegistry.createCommand(command, metadata);
            modelCapabilities = aiCommand.modelCapabilities();
            AIGateway aiGateway = aiGatewayRegistry.getSupportedAiGateways(aiCommand)
                    .stream()
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException(AIUtils.NO_SUPPORTED_AI_GATEWAY));
            AIResponse aiResponse = aiGateway.generateResponse(aiCommand);
            return processSuccessResponse(user, aiResponse, modelCapabilities, assistantRole, assistantRoleContentFromRole, startTime);
        } catch (AccessDeniedException e) {
            log.warn("Access denied for user: {}", e.getMessage());
            throw e;
        } catch (UserMessageTooLongException e) {
            log.warn("Message exceeds token limit: {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            throw support.handleProcessingError(command, userMessage, modelCapabilities, e);
        }
    }

    private String processSuccessResponse(RestUser user, AIResponse aiResponse, Set<ModelCapabilities> modelCapabilities,
                                          AssistantRole assistantRole, String assistantRoleContentFromRole, long startTime) {
        Map<String, Object> usefulResponseData = AIUtils.extractUsefulData(aiResponse);
        Optional<String> responseOpt = retrieveMessage(aiResponse);
        if (responseOpt.isEmpty()) {
            String errorMessage = extractError(aiResponse).orElse(AIUtils.CONTENT_IS_EMPTY);
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
        OpenDaimonMessage assistantMessage = messageService.saveAssistantMessage(
                user,
                response,
                modelCapabilities.toString(),
                assistantRoleContentFromRole,
                (int) processingTime,
                usefulResponseData);
        messageService.updateMessageStatus(assistantMessage, ResponseStatus.SUCCESS);
        return response;
    }

}
