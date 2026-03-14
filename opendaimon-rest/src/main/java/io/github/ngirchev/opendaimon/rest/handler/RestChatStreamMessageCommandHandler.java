package io.github.ngirchev.opendaimon.rest.handler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatResponse;
import reactor.core.publisher.Flux;
import io.github.ngirchev.opendaimon.bulkhead.exception.AccessDeniedException;
import io.github.ngirchev.opendaimon.common.exception.UserMessageTooLongException;
import io.github.ngirchev.opendaimon.common.ai.AIGateways;
import io.github.ngirchev.opendaimon.common.ai.ModelCapabilities;
import io.github.ngirchev.opendaimon.common.ai.command.AICommand;
import io.github.ngirchev.opendaimon.common.ai.factory.AICommandFactoryRegistry;
import io.github.ngirchev.opendaimon.common.ai.response.AIResponse;
import io.github.ngirchev.opendaimon.common.ai.response.SpringAIStreamResponse;
import io.github.ngirchev.opendaimon.common.command.ICommand;
import io.github.ngirchev.opendaimon.common.command.ICommandHandler;
import io.github.ngirchev.opendaimon.common.model.*;
import io.github.ngirchev.opendaimon.common.service.*;
import io.github.ngirchev.opendaimon.rest.model.RestUser;
import io.github.ngirchev.opendaimon.rest.service.RestMessageService;
import io.github.ngirchev.opendaimon.rest.service.RestUserService;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.ngirchev.opendaimon.common.service.AIUtils.*;

@Slf4j
@RequiredArgsConstructor
public class RestChatStreamMessageCommandHandler implements
        ICommandHandler<RestChatCommandType, RestChatCommand, Flux<String>> {

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
        return command.commandType() == RestChatCommandType.STREAM;
    }

    @Override
    public int priority() {
        return 0;
    }

    @Override
    public Flux<String> handle(RestChatCommand command) {
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
            log.info("Using conversation thread: {} with AssistantRole {} (v{})",
                    thread.getThreadKey(), assistantRoleId, assistantRole.getVersion());
            long startTime = System.currentTimeMillis();
            Map<String, String> metadata = RestChatHandlerSupport.buildMetadata(thread, assistantRoleContentFromRole, assistantRoleId, user.getId());
            AICommand aiCommand = aiCommandFactoryRegistry.createCommand(command, metadata);
            modelCapabilities = aiCommand.modelCapabilities();
            AIGateway aiGateway = aiGatewayRegistry.getSupportedAiGateways(aiCommand)
                    .stream()
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException(AIUtils.NO_SUPPORTED_AI_GATEWAY));
            AIResponse aiResponse = aiGateway.generateResponse(aiCommand);
            return buildStreamFlux(aiResponse, user, modelCapabilities, assistantRole, assistantRoleContentFromRole, startTime);
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

    private Flux<String> buildStreamFlux(AIResponse aiResponse, RestUser user, Set<ModelCapabilities> modelCapabilities,
                                         AssistantRole assistantRole, String assistantRoleContentFromRole, long startTime) {
        if (aiResponse.gatewaySource() != AIGateways.SPRINGAI || !(aiResponse instanceof SpringAIStreamResponse aiStreamResponse)) {
            throw new IllegalStateException("Expected streaming message");
        }
        AtomicReference<ChatResponse> lastResponse = new AtomicReference<>(null);
        StringBuilder fullResponse = new StringBuilder();
        return aiStreamResponse.chatResponse()
                .doOnNext(lastResponse::set)
                .map(AIUtils::extractText)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .doOnNext(fullResponse::append)
                .flatMap(chunk -> Flux.fromStream(chunk.codePoints().mapToObj(cp -> new String(Character.toChars(cp)))))
                .doOnComplete(() -> saveToDatabase(user, modelCapabilities, assistantRole, assistantRoleContentFromRole, startTime, fullResponse.toString(), lastResponse.get()))
                .doOnCancel(() -> saveToDatabase(user, modelCapabilities, assistantRole, assistantRoleContentFromRole, startTime, fullResponse.toString(), lastResponse.get()));
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
            String errorMessage = extractError(chatResponse).orElse(AIUtils.CONTENT_IS_EMPTY);

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
        OpenDaimonMessage assistantMessage = messageService.saveAssistantMessage(
                user,
                fullMessage,
                modelCapabilities.toString(),
                assistantRoleContentFromRole,
                (int) processingTime,
                usefulResponseData);

        // Update response status to SUCCESS
        messageService.updateMessageStatus(assistantMessage, ResponseStatus.SUCCESS);
    }

}
