package ru.girchev.aibot.telegram.command.handler.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.telegram.telegrambots.meta.api.objects.Message;
import ru.girchev.aibot.common.ai.AIGateways;
import ru.girchev.aibot.common.ai.command.AICommand;
import ru.girchev.aibot.common.ai.factory.AICommandFactoryRegistry;
import ru.girchev.aibot.common.ai.response.AIResponse;
import ru.girchev.aibot.common.ai.ModelType;
import ru.girchev.aibot.common.ai.response.SpringAIStreamResponse;
import ru.girchev.aibot.common.command.ICommand;
import ru.girchev.aibot.common.model.*;
import ru.girchev.aibot.common.service.*;
import ru.girchev.aibot.telegram.TelegramBot;
import ru.girchev.aibot.telegram.command.TelegramCommand;
import ru.girchev.aibot.telegram.command.TelegramCommandType;
import ru.girchev.aibot.telegram.command.handler.AbstractTelegramCommandHandlerWithResponseSend;
import ru.girchev.aibot.telegram.model.TelegramUser;
import ru.girchev.aibot.telegram.model.TelegramUserSession;
import ru.girchev.aibot.telegram.service.TelegramMessageService;
import ru.girchev.aibot.telegram.service.TelegramUserService;
import ru.girchev.aibot.telegram.service.TelegramUserSessionService;
import ru.girchev.aibot.telegram.service.TypingIndicatorService;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static ru.girchev.aibot.common.ai.command.AICommand.*;
import static ru.girchev.aibot.common.service.AIUtils.extractError;
import static ru.girchev.aibot.common.service.AIUtils.retrieveMessage;

@Slf4j
public class MessageTelegramCommandHandler extends AbstractTelegramCommandHandlerWithResponseSend {

    private final TelegramUserService telegramUserService;
    private final TelegramUserSessionService telegramUserSessionService;
    private final TelegramMessageService telegramMessageService;
    private final AIGatewayRegistry aiGatewayRegistry;
    private final AIBotMessageService messageService;
    private final AICommandFactoryRegistry aiCommandFactoryRegistry;

    @SuppressWarnings("java:S107")
    public MessageTelegramCommandHandler(ObjectProvider<TelegramBot> telegramBotProvider,
                                         TypingIndicatorService typingIndicatorService,
                                         TelegramUserService telegramUserService,
                                         TelegramUserSessionService telegramUserSessionService,
                                         TelegramMessageService telegramMessageService,
                                         AIGatewayRegistry aiGatewayRegistry,
                                         AIBotMessageService messageService,
                                         AICommandFactoryRegistry aiCommandFactoryRegistry) {
        super(telegramBotProvider, typingIndicatorService);
        this.telegramUserService = telegramUserService;
        this.telegramUserSessionService = telegramUserSessionService;
        this.telegramMessageService = telegramMessageService;
        this.aiGatewayRegistry = aiGatewayRegistry;
        this.messageService = messageService;
        this.aiCommandFactoryRegistry = aiCommandFactoryRegistry;
    }

    @Override
    public boolean canHandle(ICommand<TelegramCommandType> command) {
        if (!(command instanceof TelegramCommand)) {
            return false;
        }
        var commandType = command.commandType();
        return commandType != null
                && commandType.command() != null
                && commandType.command().equals(TelegramCommand.MESSAGE);
    }

    @Override
    public String handleInner(TelegramCommand command) {
        AIBotMessage userMessage = null;
        Set<ModelType> modelTypes = Set.of();
        Message message = command.update().getMessage();
        ConversationThread thread;

        try {
            // Получаем пользователя и его роль
            if (message == null) {
                throw new IllegalStateException("Message is required for message command");
            }
            TelegramUser telegramUser = telegramUserService.getOrCreateUser(message.getFrom());

            // Получаем или создаем сессию пользователя
            TelegramUserSession session = telegramUserSessionService.getOrCreateSession(telegramUser);

            // Сохраняем запрос пользователя
            // Thread и роль автоматически получаются или создаются внутри saveUserMessage
            userMessage = telegramMessageService.saveUserMessage(
                    telegramUser, session, command.userText(),
                    RequestType.TEXT, null);

            // Получаем thread и роль из сохраненного сообщения для дальнейшего использования
            thread = userMessage.getThread();
            AssistantRole assistantRole = userMessage.getAssistantRole();
            String assistantRoleContent = assistantRole.getContent();
            Integer assistantRoleVersion = assistantRole.getVersion();
            Long assistantRoleId = assistantRole.getId();

            log.info("Using conversation thread: {} with AssistantRole {} (v{})",
                    thread.getThreadKey(), assistantRoleId, assistantRoleVersion);

            // Обрабатываем запрос и получаем ответ
            long startTime = System.currentTimeMillis();

            // Передаем в metadata необходимые данные для построения контекста
            // Новая фабрика ConversationHistoryAiCommandFactory сама использует ContextBuilderService для построения контекста
            // Если в metadata есть threadKey - используется ConversationHistoryAiCommandFactory
            // Если нет - используется DefaultAiCommandFactory (fallback)
            Map<String, String> metadata = prepareMetadata(thread, assistantRoleContent, assistantRoleId, telegramUser);

            AICommand aiCommand = aiCommandFactoryRegistry.createCommand(command, metadata);
            modelTypes = aiCommand.modelTypes();
            AIGateway aiGateway = aiGatewayRegistry.getSupportedAiGateways(aiCommand)
                    .stream()
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException("No supported AI gateway found for AI Command " + aiCommand));
            AIResponse aiResponse = aiGateway.generateResponse(aiCommand);

            Map<String, Object> usefulResponseData;
            Optional<String> responseTextOpt;
            Optional<String> errorOpt;
            boolean alreadySentInStream = false;

            if (aiResponse.gatewaySource() == AIGateways.SPRINGAI && aiResponse instanceof SpringAIStreamResponse aiStreamResponse) {
                alreadySentInStream = true;
                Integer[] replyToMessageId = {message.getMessageId()};
                ChatResponse chatResponse = AIUtils.processStreamingResponseByParagraphs(aiStreamResponse.chatResponse(),
                        s -> {
                            sendMessage(command.telegramId(), AIUtils.convertMarkdownToHtml(s), replyToMessageId[0]);
                            replyToMessageId[0] = null; // После первого сообщения reply-to не нужен
                        }
                );

                // Извлекаем полезные данные из ответа AI провайдера ДО попытки получить content
                // Это нужно для обработки ошибок (например, когда finish_reason = "length")
                usefulResponseData = AIUtils.extractSpringAiUsefulData(chatResponse);

                // Пытаемся получить content из ответа
                responseTextOpt = AIUtils.extractText(chatResponse);
                errorOpt = extractError(chatResponse);
            } else {
                // Извлекаем полезные данные из ответа AI провайдера ДО попытки получить content
                // Это нужно для обработки ошибок (например, когда finish_reason = "length")
                usefulResponseData = AIUtils.extractUsefulData(aiResponse);

                // Пытаемся получить content из ответа
                responseTextOpt = retrieveMessage(aiResponse);
                errorOpt = extractError(aiResponse);
            }

            if (responseTextOpt.isPresent()) {
                String responseText = responseTextOpt.get();
                long processingTime = System.currentTimeMillis() - startTime;

                String model = usefulResponseData != null && usefulResponseData.containsKey("model") 
                    ? String.valueOf(usefulResponseData.get("model")) 
                    : "unknown";
                log.info("Gateway: [{}]. Model: [{}]", aiResponse.gatewaySource(), model);

                // Сохраняем ответ от сервиса
                // Thread и роль автоматически получаются или создаются внутри saveAssistantMessage
                var assistantMessage = telegramMessageService.saveAssistantMessage(
                        telegramUser,
                        responseText,
                        modelTypes.toString(),
                        assistantRoleContent,
                        (int) processingTime,
                        usefulResponseData);

                // Отправляем ответ пользователю с reply-to на исходное сообщение если не стрим
                // Конвертируем Markdown в HTML для корректного отображения форматирования
                if (!alreadySentInStream) {
                    Integer replyToMessageId = message.getMessageId();
                    String htmlFormattedText = AIUtils.convertMarkdownToHtml(responseText);
                    sendMessage(command.telegramId(), htmlFormattedText, replyToMessageId);
                }

                // Обновляем статус ответа на SUCCESS
                messageService.updateMessageStatus(assistantMessage, ResponseStatus.SUCCESS);
            } else {
                // Если content пустой, используем ошибку из AIResponse
                String errorMessage = errorOpt.orElse("Content is empty");

                // Сохраняем ошибку в БД с finish_reason в response_data
                telegramMessageService.saveAssistantErrorMessage(
                        telegramUser,
                        errorMessage,
                        modelTypes.toString(),
                        assistantRoleContent,
                        usefulResponseData != null && !usefulResponseData.isEmpty()
                                ? usefulResponseData.toString()
                                : null);

                Integer replyToMessageId = message.getMessageId();
                sendErrorMessage(command.telegramId(), errorMessage, replyToMessageId);
                return null;
            }
        } catch (Exception e) {
            log.error("Error processing message", e);
            if (userMessage != null && userMessage.getUser() instanceof TelegramUser telegramUser) {
                // Получаем роль из сохраненного сообщения для сохранения ошибки
                String errorRoleContent = userMessage.getAssistantRole() != null
                        ? userMessage.getAssistantRole().getContent()
                        : null;
                telegramMessageService.saveAssistantErrorMessage(
                        telegramUser,
                        "Произошла ошибка при обработке запроса",
                        modelTypes.toString(),
                        errorRoleContent,
                        null);
            }
            Integer replyToMessageId = message != null ? message.getMessageId() : null;
            sendErrorMessage(command.telegramId(), "Произошла ошибка при обработке запроса", replyToMessageId);
        }
        return null;
    }

    private Map<String, String> prepareMetadata(
            ConversationThread thread,
            String assistantRoleContent,
            Long assistantRoleId,
            TelegramUser telegramUser
    ) {
        Map<String, String> metadata = new HashMap<>();
        metadata.put(THREAD_KEY_FIELD, thread.getThreadKey());
        metadata.put(ASSISTANT_ROLE_ID_FIELD, assistantRoleId.toString());
        metadata.put(USER_ID_FIELD, telegramUser.getId().toString());
        // Для обратной совместимости также передаем роль (на случай fallback на DefaultAiCommandFactory)
        metadata.put(ROLE_FIELD, assistantRoleContent);
        return metadata;
    }

    // Методы createResponseMetadata и serializeToJson удалены,
    // так как все данные уже сохраняются в таблице message и не нужно дублировать их в response_data

    @Override
    public String getSupportedCommandText() {
        return null;
    }
}
