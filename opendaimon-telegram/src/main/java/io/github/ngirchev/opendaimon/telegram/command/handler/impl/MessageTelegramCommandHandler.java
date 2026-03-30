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
import io.github.ngirchev.opendaimon.common.exception.ModelGuardrailException;
import io.github.ngirchev.opendaimon.common.exception.SummarizationFailedException;
import io.github.ngirchev.opendaimon.common.exception.UnsupportedModelCapabilityException;
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
import io.github.ngirchev.opendaimon.telegram.service.PersistentKeyboardService;
import io.github.ngirchev.opendaimon.telegram.service.ReplyImageAttachmentService;
import io.github.ngirchev.opendaimon.telegram.service.TelegramMessageService;
import io.github.ngirchev.opendaimon.telegram.service.TelegramUserService;
import io.github.ngirchev.opendaimon.telegram.service.TelegramUserSessionService;
import io.github.ngirchev.opendaimon.telegram.service.TypingIndicatorService;
import io.github.ngirchev.opendaimon.telegram.service.UserModelPreferenceService;

import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;

import java.util.Arrays;
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
    private final UserModelPreferenceService userModelPreferenceService;
    private final PersistentKeyboardService persistentKeyboardService;
    private final ReplyImageAttachmentService replyImageAttachmentService;

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
                                         TelegramProperties telegramProperties,
                                         UserModelPreferenceService userModelPreferenceService,
                                         PersistentKeyboardService persistentKeyboardService,
                                         ReplyImageAttachmentService replyImageAttachmentService) {
        super(telegramBotProvider, typingIndicatorService, messageLocalizationService);
        this.telegramUserService = telegramUserService;
        this.telegramUserSessionService = telegramUserSessionService;
        this.telegramMessageService = telegramMessageService;
        this.aiGatewayRegistry = aiGatewayRegistry;
        this.messageService = messageService;
        this.aiCommandFactoryRegistry = aiCommandFactoryRegistry;
        this.telegramProperties = telegramProperties;
        this.userModelPreferenceService = userModelPreferenceService;
        this.persistentKeyboardService = persistentKeyboardService;
        this.replyImageAttachmentService = replyImageAttachmentService;
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

            boolean hasNoText = command.userText() == null || command.userText().isBlank();
            boolean hasNoAttachments = command.attachments() == null || command.attachments().isEmpty();
            if (hasNoText && hasNoAttachments) {
                String emptyRequestText = messageLocalizationService.getMessage(
                        "telegram.message.empty.after.mention",
                        command.languageCode(),
                        formatBotMention());
                sendErrorMessage(command.telegramId(), emptyRequestText, message.getMessageId());
                return null;
            }

            // Save user request (including attachment refs when present)
            // Thread and role are obtained or created inside saveUserMessage
            userMessage = telegramMessageService.saveUserMessage(
                    telegramUser, session, command.userText(),
                    RequestType.TEXT, null, command.attachments(), command.telegramId(),
                    message.getMessageId());

            // Get thread and role from saved message for further use
            thread = userMessage.getThread();
            AssistantRole assistantRole = userMessage.getAssistantRole();
            String assistantRoleContent = assistantRole.getContent();
            Integer assistantRoleVersion = assistantRole.getVersion();
            Long assistantRoleId = assistantRole.getId();

            log.info("Using conversation thread: {} with AssistantRole {} (v{})",
                    thread.getThreadKey(), assistantRoleId, assistantRoleVersion);

            // Resolve image attachments from the message being replied to (if any).
            // Done after save (so reply images are NOT stored as current message's attachments)
            // but before createCommand (so VISION capability is detected).
            Message replyToMessage = message.getReplyToMessage();
            if (replyToMessage != null && !command.hasAttachments()) {
                List<Attachment> replyAttachments = replyImageAttachmentService
                        .resolveReplyImageAttachments(replyToMessage, thread);
                for (Attachment att : replyAttachments) {
                    command.addAttachment(att);
                }
            }

            // Process request and get response
            long startTime = System.currentTimeMillis();

            // Pass metadata required for context building
            // ConversationHistoryAiCommandFactory uses ContextBuilderService for context
            // If metadata has threadKey - ConversationHistoryAiCommandFactory is used
            // Otherwise DefaultAiCommandFactory (fallback)
            Map<String, String> metadata = prepareMetadata(
                    thread, assistantRoleContent, assistantRoleId, telegramUser);

            List<String> ragDocIds = messageService.findRagDocumentIds(thread);
            if (!ragDocIds.isEmpty()) {
                metadata.put(RAG_DOCUMENT_IDS_FIELD, String.join(",", ragDocIds));
            }

            List<Attachment> atts = command.attachments() != null ? command.attachments() : List.of();
            String attachmentTypes = atts.stream().map(a -> a.type().toString()).toList().toString();
            log.info("Creating AI command: threadKey={}, userText='{}', attachmentsCount={}, attachmentTypes={}",
                    thread.getThreadKey(), command.userText(), atts.size(), attachmentTypes);
            AICommand aiCommand = aiCommandFactoryRegistry.createCommand(command, metadata);
            modelCapabilities = aiCommand.modelCapabilities();
            AIGateway aiGateway = aiGatewayRegistry.getSupportedAiGateways(aiCommand)
                    .stream()
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException(AIUtils.NO_SUPPORTED_AI_GATEWAY));
            AIResponse aiResponse;
            ResponseContext ctx;
            try {
                aiResponse = aiGateway.generateResponse(aiCommand);
                ctx = extractResponseContext(aiResponse, command, message);
            } catch (ModelGuardrailException e) {
                log.warn("Fixed model unavailable due to guardrail: model={}, userId={}", e.getModelId(), telegramUser.getId());
                String notifyText = messageLocalizationService.getMessage(
                        "common.error.model.guardrail", command.languageCode(), e.getModelId());
                sendMessage(command.telegramId(), notifyText, message.getMessageId());
                userModelPreferenceService.clearPreference(telegramUser.getId());
                metadata.remove(PREFERRED_MODEL_ID_FIELD);
                aiCommand = aiCommandFactoryRegistry.createCommand(command, metadata);
                modelCapabilities = aiCommand.modelCapabilities();
                aiResponse = aiGateway.generateResponse(aiCommand);
                ctx = extractResponseContext(aiResponse, command, message);
            }

            if (ctx.responseTextOpt().isEmpty()) {
                // One retry on empty content
                log.debug("Empty content from model, retrying once");
                aiResponse = aiGateway.generateResponse(aiCommand);
                ctx = extractResponseContext(aiResponse, command, message);
            }

            if (ctx.responseTextOpt().isPresent()) {
                String newRagDocIds = aiCommand.metadata().get(RAG_DOCUMENT_IDS_FIELD);
                String newRagFilenames = aiCommand.metadata().get(RAG_FILENAMES_FIELD);
                if (newRagFilenames != null) {
                    messageService.updateRagMetadata(userMessage,
                            Arrays.asList(newRagDocIds.split(",")),
                            Arrays.asList(newRagFilenames.split(",")));
                }
                SavedResponse saved = saveSuccessResponse(
                        telegramUser,
                        userMessage.getThread(),
                        aiResponse,
                        ctx,
                        modelCapabilities,
                        assistantRoleContent,
                        startTime);
                // Use thread from saved assistant message — it has up-to-date totalTokens after updateThreadCounters
                ConversationThread updatedThread = saved.thread();
                if (ctx.alreadySentInStream()) {
                    // Streaming: keyboard sent as a separate message (keyboard attached here would go to the wrong message)
                    // Status message text shows the actual model from response; keyboard buttons reflect DB preference.
                    persistentKeyboardService.sendKeyboard(command.telegramId(), telegramUser.getId(), updatedThread, saved.model());
                } else {
                    // Non-streaming: attach keyboard directly to the AI response message for reliable display on Android
                    ReplyKeyboardMarkup keyboard = persistentKeyboardService.buildKeyboardMarkup(
                            telegramUser.getId(), updatedThread);
                    sendMessage(command.telegramId(),
                            AIUtils.convertMarkdownToHtml(ctx.responseTextOpt().get()),
                            message.getMessageId(),
                            keyboard);
                }
            } else {
                sendEmptyContentError(command, telegramUser, userMessage.getThread(), message, ctx, modelCapabilities, assistantRoleContent);
                return null;
            }
        } catch (UserMessageTooLongException e) {
            handleUserMessageTooLong(command, message, e);
            return null;
        } catch (DocumentContentNotExtractableException e) {
            handleDocumentContentNotExtractable(command, message, userMessage, modelCapabilities, e);
            return null;
        } catch (UnsupportedModelCapabilityException e) {
            handleUnsupportedModelCapability(command, message, userMessage, modelCapabilities, e);
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
                    null,
                    userMessage.getThread());
        }
        sendErrorMessage(command.telegramId(), e.getMessage(), replyToMessageId);
    }

    private void handleUnsupportedModelCapability(TelegramCommand command, Message message,
                                                   OpenDaimonMessage userMessage,
                                                   Set<ModelCapabilities> modelCapabilities,
                                                   UnsupportedModelCapabilityException e) {
        log.warn("Model capability mismatch: {}", e.getMessage());
        Integer replyToMessageId = message != null ? message.getMessageId() : null;
        String errorText = e.getModelId() != null
                ? messageLocalizationService.getMessage(
                        "common.error.model.unsupported.capability",
                        command.languageCode(),
                        e.getModelId(),
                        e.getMissingCapabilities())
                : e.getMessage();
        if (userMessage != null && userMessage.getUser() instanceof TelegramUser telegramUser) {
            String errorRoleContent = userMessage.getAssistantRole() != null
                    ? userMessage.getAssistantRole().getContent() : null;
            telegramMessageService.saveAssistantErrorMessage(
                    telegramUser, errorText, modelCapabilities.toString(), errorRoleContent, null, userMessage.getThread());
        }
        sendErrorMessage(command.telegramId(), errorText, replyToMessageId);
    }

    private void handleProcessingException(TelegramCommand command, Message message, OpenDaimonMessage userMessage,
                                           Set<ModelCapabilities> modelCapabilities, Exception e) {
        DocumentContentNotExtractableException docEx = findDocumentContentNotExtractable(e);
        if (docEx != null) {
            handleDocumentContentNotExtractable(command, message, userMessage, modelCapabilities, docEx);
            return;
        }
        SummarizationFailedException sumEx = findCause(e, SummarizationFailedException.class);
        if (sumEx != null) {
            handleSummarizationFailed(command, message);
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
                    null,
                    userMessage.getThread());
        }
        Integer replyToMessageId = message != null ? message.getMessageId() : null;
        sendErrorMessage(command.telegramId(), userFacingMessage, replyToMessageId);
    }

    private void handleSummarizationFailed(TelegramCommand command, Message message) {
        log.warn("Summarization failed for conversationId, notifying user to start new thread");
        Integer replyToMessageId = message != null ? message.getMessageId() : null;
        String errorText = messageLocalizationService.getMessage(
                "telegram.summarization.failed", command.languageCode());
        sendErrorMessage(command.telegramId(), errorText, replyToMessageId);
    }

    private static DocumentContentNotExtractableException findDocumentContentNotExtractable(Throwable t) {
        return findCause(t, DocumentContentNotExtractableException.class);
    }

    private static <T extends Throwable> T findCause(Throwable t, Class<T> type) {
        while (t != null) {
            if (type.isInstance(t)) {
                return type.cast(t);
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

    private record SavedResponse(String model, ConversationThread thread) {}

    private SavedResponse saveSuccessResponse(TelegramUser telegramUser,
                                              ConversationThread thread,
                                              AIResponse aiResponse, ResponseContext ctx,
                                              Set<ModelCapabilities> modelCapabilities, String assistantRoleContent,
                                              long startTime) {
        String responseText = ctx.responseTextOpt().orElseThrow();
        long processingTime = System.currentTimeMillis() - startTime;
        String model = ctx.usefulResponseData() != null && ctx.usefulResponseData().containsKey("model")
                ? String.valueOf(ctx.usefulResponseData().get("model"))
                : null;
        log.info("Gateway: [{}]. Model: [{}]", aiResponse.gatewaySource(), model);
        var assistantMessage = telegramMessageService.saveAssistantMessage(
                telegramUser,
                responseText,
                modelCapabilities.toString(),
                assistantRoleContent,
                (int) processingTime,
                ctx.usefulResponseData(),
                thread);
        messageService.updateMessageStatus(assistantMessage, ResponseStatus.SUCCESS);
        return new SavedResponse(model, assistantMessage.getThread());
    }

    private void sendEmptyContentError(TelegramCommand command, TelegramUser telegramUser, ConversationThread thread, Message message,
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
                        : null,
                thread);
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
        // For backward compatibility also pass role (for fallback to DefaultAiCommandFactory).
        // Telegram-specific bot identity is composed in this module.
        metadata.put(ROLE_FIELD, withTelegramBotIdentity(assistantRoleContent));
        if (telegramUser.getLanguageCode() != null) {
            metadata.put(LANGUAGE_CODE_FIELD, telegramUser.getLanguageCode());
        }
        userModelPreferenceService.getPreferredModel(telegramUser.getId())
                .ifPresent(modelId -> metadata.put(PREFERRED_MODEL_ID_FIELD, modelId));
        return metadata;
    }

    private String withTelegramBotIdentity(String assistantRoleContent) {
        String baseRole = assistantRoleContent != null ? assistantRoleContent.trim() : "";
        String normalizedBotUsername = normalizeBotUsername(telegramProperties.getUsername());
        if (normalizedBotUsername == null) {
            return baseRole;
        }
        String identityClause = "You are bot with name " + normalizedBotUsername;
        if (baseRole.contains(identityClause)) {
            return baseRole;
        }
        if (baseRole.isEmpty()) {
            return identityClause;
        }
        String separator = baseRole.endsWith(".") ? " " : ". ";
        return baseRole + separator + identityClause;
    }

    // createResponseMetadata and serializeToJson were removed;
    // all data is already stored in message table and need not be duplicated in response_data

    @Override
    public String getSupportedCommandText(String languageCode) {
        return null;
    }

    private String formatBotMention() {
        String normalizedBotUsername = normalizeBotUsername(telegramProperties.getUsername());
        if (normalizedBotUsername == null) {
            return "@bot";
        }
        return normalizedBotUsername;
    }

    private String normalizeBotUsername(String username) {
        if (username == null) {
            return null;
        }
        String trimmed = username.trim();
        if (trimmed.isBlank()) {
            return null;
        }
        return trimmed.startsWith("@") ? trimmed : "@" + trimmed;
    }
}
