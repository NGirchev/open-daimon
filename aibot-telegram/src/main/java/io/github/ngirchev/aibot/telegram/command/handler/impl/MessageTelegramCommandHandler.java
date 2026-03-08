package io.github.ngirchev.aibot.telegram.command.handler.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.telegram.telegrambots.meta.api.objects.Message;
import io.github.ngirchev.aibot.common.ai.AIGateways;
import io.github.ngirchev.aibot.common.ai.command.AICommand;
import io.github.ngirchev.aibot.common.ai.factory.AICommandFactoryRegistry;
import io.github.ngirchev.aibot.common.ai.response.AIResponse;
import io.github.ngirchev.aibot.common.ai.ModelCapabilities;
import io.github.ngirchev.aibot.common.ai.response.SpringAIStreamResponse;
import io.github.ngirchev.aibot.common.command.ICommand;
import io.github.ngirchev.aibot.common.exception.DocumentContentNotExtractableException;
import io.github.ngirchev.aibot.common.exception.UserMessageTooLongException;
import io.github.ngirchev.aibot.common.model.*;
import io.github.ngirchev.aibot.common.service.*;
import io.github.ngirchev.aibot.telegram.TelegramBot;
import io.github.ngirchev.aibot.telegram.command.TelegramCommand;
import io.github.ngirchev.aibot.telegram.command.TelegramCommandType;
import io.github.ngirchev.aibot.telegram.command.handler.AbstractTelegramCommandHandlerWithResponseSend;
import io.github.ngirchev.aibot.telegram.model.TelegramUser;
import io.github.ngirchev.aibot.telegram.model.TelegramUserSession;
import io.github.ngirchev.aibot.telegram.config.TelegramProperties;
import io.github.ngirchev.aibot.telegram.service.TelegramMessageService;
import io.github.ngirchev.aibot.telegram.service.TelegramUserService;
import io.github.ngirchev.aibot.telegram.service.TelegramUserSessionService;
import io.github.ngirchev.aibot.telegram.service.TypingIndicatorService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static io.github.ngirchev.aibot.common.ai.command.AICommand.*;
import static io.github.ngirchev.aibot.common.service.AIUtils.extractError;
import static io.github.ngirchev.aibot.common.service.AIUtils.retrieveMessage;

@Slf4j
public class MessageTelegramCommandHandler extends AbstractTelegramCommandHandlerWithResponseSend {

    private final TelegramUserService telegramUserService;
    private final TelegramUserSessionService telegramUserSessionService;
    private final TelegramMessageService telegramMessageService;
    private final AIGatewayRegistry aiGatewayRegistry;
    private final AIBotMessageService messageService;
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
                                         AIBotMessageService messageService,
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
                    .orElseThrow(() -> new RuntimeException("No supported AI gateway found for AI Command " + aiCommand));
            AIResponse aiResponse = aiGateway.generateResponse(aiCommand);

            Map<String, Object> usefulResponseData;
            Optional<String> responseTextOpt;
            Optional<String> errorOpt;
            boolean alreadySentInStream = false;

            if (aiResponse.gatewaySource() == AIGateways.SPRINGAI && aiResponse instanceof SpringAIStreamResponse aiStreamResponse) {
                alreadySentInStream = true;
                Integer[] replyToMessageId = { message.getMessageId() };
                int maxMessageLength = telegramProperties.getMaxMessageLength();
                ChatResponse chatResponse = AIUtils.processStreamingResponseByParagraphs(
                        aiStreamResponse.chatResponse(),
                        maxMessageLength,
                        s -> {
                            log.debug("Sending message: {}", s);
                            sendMessage(command.telegramId(), AIUtils.convertMarkdownToHtml(s), replyToMessageId[0]);
                            replyToMessageId[0] = null; // After first message reply-to is not needed
                        }
                );

                // Extract useful data from AI provider response BEFORE getting content
                // Needed for error handling (e.g. when finish_reason = "length")
                usefulResponseData = AIUtils.extractSpringAiUsefulData(chatResponse);

                // Try to get content from response
                responseTextOpt = AIUtils.extractText(chatResponse);
                errorOpt = extractError(chatResponse);
            } else {
                // Extract useful data from AI provider response BEFORE getting content
                // Needed for error handling (e.g. when finish_reason = "length")
                usefulResponseData = AIUtils.extractUsefulData(aiResponse);

                // Try to get content from response
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

                // Save service response
                // Thread and role are obtained or created inside saveAssistantMessage
                var assistantMessage = telegramMessageService.saveAssistantMessage(
                        telegramUser,
                        responseText,
                        modelCapabilities.toString(),
                        assistantRoleContent,
                        (int) processingTime,
                        usefulResponseData);

                // Send response to user with reply-to original message if not streaming
                // Convert Markdown to HTML for proper formatting
                if (!alreadySentInStream) {
                    Integer replyToMessageId = message.getMessageId();
                    String htmlFormattedText = AIUtils.convertMarkdownToHtml(responseText);
                    sendMessage(command.telegramId(), htmlFormattedText, replyToMessageId);
                }

                // Update response status to SUCCESS
                messageService.updateMessageStatus(assistantMessage, ResponseStatus.SUCCESS);
            } else {
                // If content is empty, use error from AIResponse
                String errorMessage = errorOpt.orElse("Content is empty");

                // Save error to DB with finish_reason in response_data
                telegramMessageService.saveAssistantErrorMessage(
                        telegramUser,
                        errorMessage,
                        modelCapabilities.toString(),
                        assistantRoleContent,
                        usefulResponseData != null && !usefulResponseData.isEmpty()
                                ? usefulResponseData.toString()
                                : null);

                Integer replyToMessageId = message.getMessageId();
                sendErrorMessage(command.telegramId(), errorMessage, replyToMessageId);
                return null;
            }
        } catch (UserMessageTooLongException e) {
            log.warn("Message exceeds token limit: {}", e.getMessage());
            Integer replyToMessageId = message != null ? message.getMessageId() : null;
            String errorText = e.getEstimatedTokens() > 0 && e.getMaxAllowed() > 0
                    ? messageLocalizationService.getMessage("common.error.message.too.long", command.languageCode(), e.getEstimatedTokens(), e.getMaxAllowed())
                    : e.getMessage();
            sendErrorMessage(command.telegramId(), errorText, replyToMessageId);
            return null;
        } catch (DocumentContentNotExtractableException e) {
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
            return null;
        } catch (Exception e) {
            DocumentContentNotExtractableException docEx = findDocumentContentNotExtractable(e);
            if (docEx != null) {
                log.warn("Could not extract text from document: {}", docEx.getMessage());
                Integer replyToMessageId = message != null ? message.getMessageId() : null;
                if (userMessage != null && userMessage.getUser() instanceof TelegramUser telegramUser) {
                    String errorRoleContent = userMessage.getAssistantRole() != null
                            ? userMessage.getAssistantRole().getContent()
                            : null;
                    telegramMessageService.saveAssistantErrorMessage(
                            telegramUser,
                            docEx.getMessage(),
                            modelCapabilities.toString(),
                            errorRoleContent,
                            null);
                }
                sendErrorMessage(command.telegramId(), docEx.getMessage(), replyToMessageId);
                return null;
            }
            if (AIUtils.shouldLogWithoutStacktrace(e)) {
                log.error("Error processing message: {}", AIUtils.getRootCauseMessage(e));
            } else {
                log.error("Error processing message", e);
            }
            if (userMessage != null && userMessage.getUser() instanceof TelegramUser telegramUser) {
                // Get role from saved message for saving error
                String errorRoleContent = userMessage.getAssistantRole() != null
                        ? userMessage.getAssistantRole().getContent()
                        : null;
                telegramMessageService.saveAssistantErrorMessage(
                        telegramUser,
                        "An error occurred while processing your request",
                        modelCapabilities.toString(),
                        errorRoleContent,
                        null);
            }
            Integer replyToMessageId = message != null ? message.getMessageId() : null;
            sendErrorMessage(command.telegramId(), "An error occurred while processing your request", replyToMessageId);
        }
        return null;
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
        return metadata;
    }

    // createResponseMetadata and serializeToJson were removed;
    // all data is already stored in message table and need not be duplicated in response_data

    @Override
    public String getSupportedCommandText(String languageCode) {
        return null;
    }
}
