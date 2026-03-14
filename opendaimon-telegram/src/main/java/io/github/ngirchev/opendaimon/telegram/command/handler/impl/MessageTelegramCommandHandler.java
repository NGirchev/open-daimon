package io.github.ngirchev.opendaimon.telegram.command.handler.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.telegram.telegrambots.meta.api.objects.Message;
import io.github.ngirchev.opendaimon.common.ai.AIGateways;
import io.github.ngirchev.opendaimon.common.ai.command.AICommand;
import io.github.ngirchev.opendaimon.common.ai.factory.AICommandFactoryRegistry;
import io.github.ngirchev.opendaimon.common.ai.response.AIResponse;
import io.github.ngirchev.opendaimon.common.ai.ModelCapabilities;
import io.github.ngirchev.opendaimon.common.ai.response.SpringAIStreamResponse;
import io.github.ngirchev.opendaimon.common.command.ICommand;
import io.github.ngirchev.opendaimon.common.exception.DocumentContentNotExtractableException;
import io.github.ngirchev.opendaimon.common.exception.UserMessageTooLongException;
import io.github.ngirchev.opendaimon.common.model.*;
import io.github.ngirchev.opendaimon.common.service.*;
import io.github.ngirchev.opendaimon.telegram.TelegramBot;
import io.github.ngirchev.opendaimon.telegram.command.TelegramCommand;
import io.github.ngirchev.opendaimon.telegram.command.TelegramCommandType;
import io.github.ngirchev.opendaimon.telegram.command.handler.AbstractTelegramCommandHandler;
import io.github.ngirchev.opendaimon.telegram.command.handler.AbstractTelegramCommandHandlerWithResponseSend;
import io.github.ngirchev.opendaimon.telegram.model.TelegramUser;
import io.github.ngirchev.opendaimon.telegram.model.TelegramUserSession;
import io.github.ngirchev.opendaimon.telegram.config.TelegramProperties;
import io.github.ngirchev.opendaimon.telegram.service.TelegramMessageService;
import io.github.ngirchev.opendaimon.telegram.service.TelegramUserService;
import io.github.ngirchev.opendaimon.telegram.service.TelegramUserSessionService;
import io.github.ngirchev.opendaimon.telegram.service.TypingIndicatorService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static io.github.ngirchev.opendaimon.common.ai.command.AICommand.*;
import static io.github.ngirchev.opendaimon.common.service.AIUtils.extractError;
import static io.github.ngirchev.opendaimon.common.service.AIUtils.retrieveMessage;

@Slf4j
public class MessageTelegramCommandHandler extends AbstractTelegramCommandHandlerWithResponseSend {

    private final TelegramUserService telegramUserService;
    private final TelegramUserSessionService telegramUserSessionService;
    private final TelegramMessageService telegramMessageService;
    private final AIGatewayRegistry aiGatewayRegistry;
    private final OpenDaimonMessageService messageService;
    private final AICommandFactoryRegistry aiCommandFactoryRegistry;
    private final TelegramProperties telegramProperties;

    @SuppressWarnings("java:S107")
    public MessageTelegramCommandHandler(ObjectProvider<TelegramBot> telegramBotProvider,
                                         TypingIndicatorService typingIndicatorService,
                                         MessageLocalizationService messageLocalizationService,
                                         TelegramUserService telegramUserService,
                                         TelegramUserSessionService telegramUserSessionService,
                                         TelegramMessageService telegramMessageService,
                                         AIGatewayRegistry aiGatewayRegistry,
                                         OpenDaimonMessageService messageService,
                                         AICommandFactoryRegistry aiCommandFactoryRegistry,
                                         TelegramProperties telegramProperties) {
        super(telegramBotProvider, typingIndicatorService, messageLocalizationService);
        this.telegramUserService = telegramUserService;
        this.telegramUserSessionService = telegramUserSessionService;
        this.telegramMessageService = telegramMessageService;
        this.aiGatewayRegistry = aiGatewayRegistry;
        this.messageService = messageService;
        this.aiCommandFactoryRegistry = aiCommandFactoryRegistry;
        this.telegramProperties = telegramProperties;
    }

    @Override
    public boolean canHandle(ICommand<TelegramCommandType> command) {
        if (!(command instanceof TelegramCommand)) return false;
        var commandType = command.commandType();
        if (commandType == null || commandType.command() == null) return false;
        return commandType.command().equals(TelegramCommand.MESSAGE);
    }

    @Override
    public String handleInner(TelegramCommand command) {
        OpenDaimonMessage userMessage = null;
        Set<ModelCapabilities> modelCapabilities = Set.of();
        Message message = command.update().getMessage();
        ConversationThread thread;

        try {
            // Get user and their role
            if (message == null) {
                throw new IllegalStateException("Message is required for message command");
            }
            TelegramUser telegramUser = telegramUserService.getOrCreateUser(message.getFrom());

            // Get or create user session
            TelegramUserSession session = telegramUserSessionService.getOrCreateSession(telegramUser);

            // Save user request (including attachment refs when present)
            // Thread and role are obtained or created inside saveUserMessage
            userMessage = telegramMessageService.saveUserMessage(
                    telegramUser, session, command.userText(),
                    RequestType.TEXT, null, command.attachments());

            // Get thread and role from saved message for further use
            thread = userMessage.getThread();
            AssistantRole assistantRole = userMessage.getAssistantRole();
            String assistantRoleContent = assistantRole.getContent();
            Integer assistantRoleVersion = assistantRole.getVersion();
            Long assistantRoleId = assistantRole.getId();

            log.info("Using conversation thread: {} with AssistantRole {} (v{})",
                    thread.getThreadKey(), assistantRoleId, assistantRoleVersion);

            // Process request and get response
            long startTime = System.currentTimeMillis();

            // Pass metadata required for context building
            // ConversationHistoryAiCommandFactory uses ContextBuilderService for context
            // If metadata has threadKey - ConversationHistoryAiCommandFactory is used
            // Otherwise DefaultAiCommandFactory (fallback)
            Map<String, String> metadata = prepareMetadata(thread, assistantRoleContent, assistantRoleId, telegramUser);

            List<Attachment> atts = command.attachments() != null ? command.attachments() : List.of();
            String attachmentTypes = atts.stream().map(a -> a.type().toString()).toList().toString();
            log.info("Creating AI command: threadKey={}, userText='{}', attachmentsCount={}, attachmentTypes={}",
                    thread.getThreadKey(), command.userText(), atts.size(), attachmentTypes);
            AICommand aiCommand = aiCommandFactoryRegistry.createCommand(command, metadata);
            modelCapabilities = aiCommand.modelCapabilities();
            AIGateway aiGateway = aiGatewayRegistry.getSupportedAiGateways(aiCommand)
                    .stream()
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException(AIUtils.NO_SUPPORTED_AI_GATEWAY + " for AI Command " + aiCommand));
            AIResponse aiResponse = aiGateway.generateResponse(aiCommand);
            ResponseContext ctx = extractResponseContext(aiResponse, command, message);

            if (ctx.responseTextOpt().isEmpty()) {
                // One retry on empty content
                log.debug("Empty content from model, retrying once");
                aiResponse = aiGateway.generateResponse(aiCommand);
                ctx = extractResponseContext(aiResponse, command, message);
            }

            if (ctx.responseTextOpt().isPresent()) {
                saveAndSendSuccessResponse(command, telegramUser, message, aiResponse, ctx, modelCapabilities,
                        assistantRoleContent, startTime);
            } else {
                sendEmptyContentError(command, telegramUser, message, ctx, modelCapabilities, assistantRoleContent);
                return null;
            }
        } catch (UserMessageTooLongException e) {
            handleUserMessageTooLong(command, message, e);
            return null;
        } catch (DocumentContentNotExtractableException e) {
            handleDocumentContentNotExtractable(command, message, userMessage, modelCapabilities, e);
            return null;
        } catch (Exception e) {
            handleProcessingException(command, message, userMessage, modelCapabilities, e);
            return null;
        }
        return null;
    }

    private void handleUserMessageTooLong(TelegramCommand command, Message message, UserMessageTooLongException e) {
        log.warn("Message exceeds token limit: {}", e.getMessage());
        Integer replyToMessageId = message != null ? message.getMessageId() : null;
        String errorText = e.getEstimatedTokens() > 0 && e.getMaxAllowed() > 0
                ? messageLocalizationService.getMessage("common.error.message.too.long", command.languageCode(), e.getEstimatedTokens(), e.getMaxAllowed())
                : e.getMessage();
        sendErrorMessage(command.telegramId(), errorText, replyToMessageId);
    }

    private void handleDocumentContentNotExtractable(TelegramCommand command, Message message, OpenDaimonMessage userMessage,
                                                    Set<ModelCapabilities> modelCapabilities,
                                                    DocumentContentNotExtractableException e) {
        log.warn("Could not extract text from document: {}", e.getMessage());
        Integer replyToMessageId = message != null ? message.getMessageId() : null;
        if (userMessage != null && userMessage.getUser() instanceof TelegramUser telegramUser) {
            String errorRoleContent = userMessage.getAssistantRole() != null
                    ? userMessage.getAssistantRole().getContent()
                    : null;
            telegramMessageService.saveAssistantErrorMessage(
                    telegramUser,
                    e.getMessage(),
                    modelCapabilities.toString(),
                    errorRoleContent,
                    null);
        }
        sendErrorMessage(command.telegramId(), e.getMessage(), replyToMessageId);
    }

    private void handleProcessingException(TelegramCommand command, Message message, OpenDaimonMessage userMessage,
                                           Set<ModelCapabilities> modelCapabilities, Exception e) {
        DocumentContentNotExtractableException docEx = findDocumentContentNotExtractable(e);
        if (docEx != null) {
            handleDocumentContentNotExtractable(command, message, userMessage, modelCapabilities, docEx);
            return;
        }
        if (AIUtils.shouldLogWithoutStacktrace(e)) {
            log.error(AbstractTelegramCommandHandler.LOG_ERROR_PROCESSING_MESSAGE, AIUtils.getRootCauseMessage(e));
        } else {
            log.error(AbstractTelegramCommandHandler.LOG_ERROR_PROCESSING_MESSAGE, e);
        }
        String userFacingMessage = messageLocalizationService.getMessage("common.error.processing", command.languageCode());
        if (userMessage != null && userMessage.getUser() instanceof TelegramUser telegramUser) {
            String errorRoleContent = userMessage.getAssistantRole() != null
                    ? userMessage.getAssistantRole().getContent()
                    : null;
            telegramMessageService.saveAssistantErrorMessage(
                    telegramUser,
                    userFacingMessage,
                    modelCapabilities.toString(),
                    errorRoleContent,
                    null);
        }
        Integer replyToMessageId = message != null ? message.getMessageId() : null;
        sendErrorMessage(command.telegramId(), userFacingMessage, replyToMessageId);
    }

    private static DocumentContentNotExtractableException findDocumentContentNotExtractable(Throwable t) {
        while (t != null) {
            if (t instanceof DocumentContentNotExtractableException e) {
                return e;
            }
            t = t.getCause();
        }
        return null;
    }

    private record ResponseContext(
            Map<String, Object> usefulResponseData,
            Optional<String> responseTextOpt,
            Optional<String> errorOpt,
            boolean alreadySentInStream
    ) {}

    private ResponseContext extractResponseContext(AIResponse aiResponse, TelegramCommand command, Message message) {
        if (aiResponse.gatewaySource() == AIGateways.SPRINGAI && aiResponse instanceof SpringAIStreamResponse aiStreamResponse) {
            Integer[] replyToMessageId = { message.getMessageId() };
            int maxMessageLength = telegramProperties.getMaxMessageLength();
            ChatResponse chatResponse = AIUtils.processStreamingResponseByParagraphs(
                    aiStreamResponse.chatResponse(),
                    maxMessageLength,
                    s -> {
                        log.debug("Sending message: {}", s);
                        sendMessage(command.telegramId(), AIUtils.convertMarkdownToHtml(s), replyToMessageId[0]);
                        replyToMessageId[0] = null;
                    }
            );
            Map<String, Object> usefulResponseData = AIUtils.extractSpringAiUsefulData(chatResponse);
            return new ResponseContext(usefulResponseData, AIUtils.extractText(chatResponse), extractError(chatResponse), true);
        }
        Map<String, Object> usefulResponseData = AIUtils.extractUsefulData(aiResponse);
        return new ResponseContext(usefulResponseData, retrieveMessage(aiResponse), extractError(aiResponse), false);
    }

    private void saveAndSendSuccessResponse(TelegramCommand command, TelegramUser telegramUser, Message message,
                                            AIResponse aiResponse, ResponseContext ctx,
                                            Set<ModelCapabilities> modelCapabilities, String assistantRoleContent,
                                            long startTime) {
        String responseText = ctx.responseTextOpt().orElseThrow();
        long processingTime = System.currentTimeMillis() - startTime;
        String model = ctx.usefulResponseData() != null && ctx.usefulResponseData().containsKey("model")
                ? String.valueOf(ctx.usefulResponseData().get("model"))
                : "unknown";
        log.info("Gateway: [{}]. Model: [{}]", aiResponse.gatewaySource(), model);
        var assistantMessage = telegramMessageService.saveAssistantMessage(
                telegramUser,
                responseText,
                modelCapabilities.toString(),
                assistantRoleContent,
                (int) processingTime,
                ctx.usefulResponseData());
        if (!ctx.alreadySentInStream()) {
            sendMessage(command.telegramId(), AIUtils.convertMarkdownToHtml(responseText), message.getMessageId());
        }
        messageService.updateMessageStatus(assistantMessage, ResponseStatus.SUCCESS);
    }

    private void sendEmptyContentError(TelegramCommand command, TelegramUser telegramUser, Message message,
                                       ResponseContext ctx, Set<ModelCapabilities> modelCapabilities,
                                       String assistantRoleContent) {
        String detailedError = ctx.errorOpt().orElse(AIUtils.CONTENT_IS_EMPTY);
        log.warn("Empty content from model: {}. usefulResponseData={}",
                detailedError,
                ctx.usefulResponseData() != null ? ctx.usefulResponseData() : "null");
        telegramMessageService.saveAssistantErrorMessage(
                telegramUser,
                detailedError,
                modelCapabilities.toString(),
                assistantRoleContent,
                ctx.usefulResponseData() != null && !ctx.usefulResponseData().isEmpty()
                        ? ctx.usefulResponseData().toString()
                        : null);
        String userMessage = messageLocalizationService.getMessage("common.error.processing", command.languageCode());
        sendErrorMessage(command.telegramId(), userMessage, message.getMessageId());
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
        // For backward compatibility also pass role (for fallback to DefaultAiCommandFactory)
        metadata.put(ROLE_FIELD, assistantRoleContent);
        if (telegramUser.getLanguageCode() != null) {
            metadata.put(LANGUAGE_CODE_FIELD, telegramUser.getLanguageCode());
        }
        return metadata;
    }

    // createResponseMetadata and serializeToJson were removed;
    // all data is already stored in message table and need not be duplicated in response_data

    @Override
    public String getSupportedCommandText(String languageCode) {
        return null;
    }
}
