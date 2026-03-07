package ru.girchev.aibot.rest.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ru.girchev.aibot.common.ai.factory.AICommandFactoryRegistry;
import ru.girchev.aibot.common.ai.command.AICommand;
import ru.girchev.aibot.common.ai.response.AIResponse;
import ru.girchev.aibot.common.ai.ModelCapabilities;
import ru.girchev.aibot.common.command.ICommand;
import ru.girchev.aibot.common.command.ICommandHandler;
import ru.girchev.aibot.common.model.AssistantRole;
import ru.girchev.aibot.common.model.ConversationThread;
import ru.girchev.aibot.common.model.AIBotMessage;
import ru.girchev.aibot.common.model.RequestType;
import ru.girchev.aibot.common.model.ResponseStatus;
import ru.girchev.aibot.common.service.*;
import ru.girchev.aibot.bulkhead.exception.AccessDeniedException;
import ru.girchev.aibot.common.exception.UserMessageTooLongException;
import ru.girchev.aibot.rest.model.RestUser;
import ru.girchev.aibot.rest.service.RestMessageService;
import ru.girchev.aibot.rest.service.RestUserService;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static ru.girchev.aibot.common.ai.command.AICommand.*;
import static ru.girchev.aibot.common.service.AIUtils.*;

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

    @Override
    public boolean canHandle(ICommand<RestChatCommandType> command) {
        return command instanceof RestChatCommand
                && command.commandType() != null
                && command.commandType() == RestChatCommandType.MESSAGE;
    }

    @Override
    public int priority() {
        return 0;
    }

    @Override
    public String handle(RestChatCommand command) {
        AIBotMessage userMessage = null;
        Set<ModelCapabilities> modelCapabilities = Set.of();
        ConversationThread thread;
        
        try {
            // Получаем пользователя по userId из команды (уже авторизован)
            RestUser user = restUserService.findById(command.userId())
                    .orElseThrow(() -> new RuntimeException("Пользователь не найден: " + command.userId()));
            
            // Определяем содержание роли ассистента: из запроса или из проперти
            String assistantRoleContent = command.chatRequestDto().assistantRole() != null
                    ? command.chatRequestDto().assistantRole()
                    : null; // Если null, сервис использует дефолтную из coreCommonProperties

            // Сохраняем запрос пользователя
            // Thread и роль автоматически получаются или создаются внутри saveUserMessage
            userMessage = restMessageService.saveUserMessage(
                    user,
                    command.chatRequestDto().message(),
                    RequestType.TEXT,
                    assistantRoleContent,
                    command.request());
            
            // Получаем thread и роль из сохраненного сообщения для дальнейшего использования
            thread = userMessage.getThread();
            AssistantRole assistantRole = userMessage.getAssistantRole();
            String assistantRoleContentFromRole = assistantRole.getContent();
            Integer assistantRoleVersion = assistantRole.getVersion();
            Long assistantRoleId = assistantRole.getId();
            
            log.info("Using conversation thread: {} with AssistantRole {} (v{})", 
                thread.getThreadKey(), assistantRoleId, assistantRoleVersion);

            // Обрабатываем запрос и получаем ответ
            long startTime = System.currentTimeMillis();
            
            // Передаем в metadata необходимые данные для построения контекста
            // Новая фабрика RestConversationHistoryAiCommandFactory сама использует ContextBuilderService для построения контекста
            // Если в metadata есть threadKey - используется RestConversationHistoryAiCommandFactory
            // Если нет - используется DefaultAiCommandFactory (fallback)
            Map<String, String> metadata = new HashMap<>();
            metadata.put(THREAD_KEY_FIELD, thread.getThreadKey());
            metadata.put(ASSISTANT_ROLE_ID_FIELD, assistantRoleId.toString());
            metadata.put(USER_ID_FIELD, user.getId().toString());
            // Для обратной совместимости также передаем роль (на случай fallback на DefaultAiCommandFactory)
            metadata.put(ROLE_FIELD, assistantRoleContentFromRole);
            
            AICommand aiCommand = aiCommandFactoryRegistry.createCommand(command, metadata);
            modelCapabilities = aiCommand.modelCapabilities();
            
            AIGateway aiGateway = aiGatewayRegistry.getSupportedAiGateways(aiCommand)
                    .stream()
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException("No supported AI gateway found"));
            AIResponse aiResponse = aiGateway.generateResponse(aiCommand);
            
            // Извлекаем полезные данные из ответа AI провайдера ДО попытки получить content
            // Это нужно для обработки ошибок (например, когда finish_reason = "length")
            Map<String, Object> usefulResponseData = AIUtils.extractUsefulData(aiResponse);
            
            // Пытаемся получить content из ответа
            Optional<String> responseOpt = retrieveMessage(aiResponse);
            if (responseOpt.isEmpty()) {
                // Если content пустой, используем ошибку из AIResponse
                String errorMessage = extractError(aiResponse)
                        .orElse("Content is empty");
                
                // Сохраняем ошибку в БД с finish_reason в response_data
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
            
            String response = responseOpt.get();
            long processingTime = System.currentTimeMillis() - startTime;

            log.info("Gateway: [{}]. Model: [{}]", aiResponse.gatewaySource(), usefulResponseData.get("model"));

            // Сохраняем ответ от сервиса
            // Thread и роль автоматически получаются или создаются внутри saveAssistantMessage
            AIBotMessage assistantMessage = messageService.saveAssistantMessage(
                    user,
                    response,
                    modelCapabilities.toString(),
                    assistantRoleContentFromRole,
                    (int) processingTime,
                    usefulResponseData);

            // Обновляем статус ответа на SUCCESS
            messageService.updateMessageStatus(assistantMessage, ResponseStatus.SUCCESS);

            return response;
        } catch (AccessDeniedException e) {
            // Пробрасываем AccessDeniedException без оборачивания для правильной обработки в RestExceptionHandler
            log.warn("Доступ запрещен для пользователя: {}", e.getMessage());
            throw e;
        } catch (UserMessageTooLongException e) {
            log.warn("Сообщение превышает лимит токенов: {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            if (AIUtils.shouldLogWithoutStacktrace(e)) {
                log.error("Error processing REST API request: {}", AIUtils.getRootCauseMessage(e));
            } else {
                log.error("Error processing REST API request", e);
            }

            // Создаем метаданные об ошибке и сериализуем их в JSON
            Map<String, Object> errorMetadata = createErrorMetadata(modelCapabilities.isEmpty() ? Set.of(ModelCapabilities.CHAT) : modelCapabilities, e);
            String errorDataJson = serializeToJson(errorMetadata);

            // Сохраняем информацию об ошибке
            String errorMessage = "Произошла ошибка при обработке запроса: " + e.getMessage();
            if (userMessage != null) {
                // Получаем роль из сохраненного сообщения для сохранения ошибки
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

            throw new RuntimeException("Ошибка при обработке запроса: " + e.getMessage(), e);
        }
    }

    /**
     * Создает метаданные для ошибки
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
     * Сериализует метаданные в JSON
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
