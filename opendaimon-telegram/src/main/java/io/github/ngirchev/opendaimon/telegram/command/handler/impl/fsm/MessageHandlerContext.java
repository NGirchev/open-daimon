package io.github.ngirchev.opendaimon.telegram.command.handler.impl.fsm;

import io.github.ngirchev.fsm.StateContext;
import io.github.ngirchev.fsm.Transition;
import io.github.ngirchev.opendaimon.common.ai.ModelCapabilities;
import io.github.ngirchev.opendaimon.common.ai.command.AICommand;
import io.github.ngirchev.opendaimon.common.ai.response.AIResponse;
import io.github.ngirchev.opendaimon.common.model.AssistantRole;
import io.github.ngirchev.opendaimon.common.model.ConversationThread;
import io.github.ngirchev.opendaimon.common.model.OpenDaimonMessage;
import io.github.ngirchev.opendaimon.common.service.AIGateway;
import io.github.ngirchev.opendaimon.telegram.command.TelegramCommand;
import io.github.ngirchev.opendaimon.telegram.model.TelegramUser;
import io.github.ngirchev.opendaimon.telegram.model.TelegramUserSession;
import org.jetbrains.annotations.Nullable;
import org.telegram.telegrambots.meta.api.objects.Message;

import io.github.ngirchev.opendaimon.common.exception.DocumentContentNotExtractableException;
import io.github.ngirchev.opendaimon.common.exception.SummarizationFailedException;
import io.github.ngirchev.opendaimon.common.exception.UnsupportedModelCapabilityException;
import io.github.ngirchev.opendaimon.common.exception.UserMessageTooLongException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Domain object that flows through the message handler FSM.
 *
 * <p>Implements {@link StateContext} so that {@code ExDomainFsm} can read/write
 * the current state directly on this object.
 *
 * <p>Mutable by design — FSM actions populate intermediate results as the context
 * moves through states. Error info is stored for the handler to dispatch after FSM completes.
 */
public final class MessageHandlerContext implements StateContext<MessageHandlerState> {

    // --- StateContext fields ---
    private MessageHandlerState state;
    private Transition<MessageHandlerState> currentTransition;

    // --- Input (immutable after construction) ---
    private final TelegramCommand command;
    private final Message message;

    /**
     * Callback for streaming response paragraphs.
     * Set by the handler before FSM.handle() — allows streaming to send
     * text to user in real-time during the generateResponse action.
     */
    private final Consumer<String> streamingParagraphSender;
    private Integer nextReplyToMessageId;

    // --- Intermediate results ---
    private TelegramUser telegramUser;
    private TelegramUserSession session;
    private boolean hasInput;
    private OpenDaimonMessage userMessage;
    private ConversationThread thread;
    private AssistantRole assistantRole;
    private Map<String, String> metadata;
    private AICommand aiCommand;
    private Set<ModelCapabilities> modelCapabilities = Set.of();
    private AIGateway aiGateway;
    private long startTime;

    // --- Response data ---
    private AIResponse aiResponse;
    private Map<String, Object> usefulResponseData;
    private String responseText;
    private String responseError;
    private boolean alreadySentInStream;
    private String responseModel;
    private Integer agentProgressMessageId;
    private final List<String> agentProgressChunks = new ArrayList<>();
    private boolean lastChunkIsThinking;

    // --- Error handling ---
    private Exception exception;
    private MessageHandlerErrorType errorType;

    public MessageHandlerContext(TelegramCommand command, Message message,
                                 Consumer<String> streamingParagraphSender) {
        this.command = command;
        this.message = message;
        this.streamingParagraphSender = streamingParagraphSender;
        this.nextReplyToMessageId = message != null ? message.getMessageId() : null;
        this.state = MessageHandlerState.RECEIVED;
    }

    // --- StateContext implementation ---

    @Override
    public MessageHandlerState getState() {
        return state;
    }

    @Override
    public void setState(MessageHandlerState state) {
        this.state = state;
    }

    @Nullable
    @Override
    public Transition<MessageHandlerState> getCurrentTransition() {
        return currentTransition;
    }

    @Override
    public void setCurrentTransition(@Nullable Transition<MessageHandlerState> transition) {
        this.currentTransition = transition;
    }

    // --- Input accessors ---

    public TelegramCommand getCommand() {
        return command;
    }

    public Message getMessage() {
        return message;
    }

    public Consumer<String> getStreamingParagraphSender() {
        return streamingParagraphSender;
    }

    public Integer consumeNextReplyToMessageId() {
        Integer value = nextReplyToMessageId;
        nextReplyToMessageId = null;
        return value;
    }

    public void clearNextReplyToMessageId() {
        nextReplyToMessageId = null;
    }

    // --- Intermediate accessors ---

    public TelegramUser getTelegramUser() {
        return telegramUser;
    }

    public void setTelegramUser(TelegramUser telegramUser) {
        this.telegramUser = telegramUser;
    }

    public TelegramUserSession getSession() {
        return session;
    }

    public void setSession(TelegramUserSession session) {
        this.session = session;
    }

    public boolean hasInput() {
        return hasInput;
    }

    public void setHasInput(boolean hasInput) {
        this.hasInput = hasInput;
    }

    public OpenDaimonMessage getUserMessage() {
        return userMessage;
    }

    public void setUserMessage(OpenDaimonMessage userMessage) {
        this.userMessage = userMessage;
    }

    public ConversationThread getThread() {
        return thread;
    }

    public void setThread(ConversationThread thread) {
        this.thread = thread;
    }

    public AssistantRole getAssistantRole() {
        return assistantRole;
    }

    public void setAssistantRole(AssistantRole assistantRole) {
        this.assistantRole = assistantRole;
    }

    public Map<String, String> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, String> metadata) {
        this.metadata = metadata;
    }

    public AICommand getAiCommand() {
        return aiCommand;
    }

    public void setAiCommand(AICommand aiCommand) {
        this.aiCommand = aiCommand;
    }

    public Set<ModelCapabilities> getModelCapabilities() {
        return modelCapabilities;
    }

    public void setModelCapabilities(Set<ModelCapabilities> modelCapabilities) {
        this.modelCapabilities = modelCapabilities;
    }

    public AIGateway getAiGateway() {
        return aiGateway;
    }

    public void setAiGateway(AIGateway aiGateway) {
        this.aiGateway = aiGateway;
    }

    public long getStartTime() {
        return startTime;
    }

    public void setStartTime(long startTime) {
        this.startTime = startTime;
    }

    // --- Response data ---

    public AIResponse getAiResponse() {
        return aiResponse;
    }

    public void setAiResponse(AIResponse aiResponse) {
        this.aiResponse = aiResponse;
    }

    public Map<String, Object> getUsefulResponseData() {
        return usefulResponseData;
    }

    public void setUsefulResponseData(Map<String, Object> usefulResponseData) {
        this.usefulResponseData = usefulResponseData;
    }

    public Optional<String> getResponseText() {
        return Optional.ofNullable(responseText);
    }

    public void setResponseText(String responseText) {
        this.responseText = responseText;
    }

    public Optional<String> getResponseError() {
        return Optional.ofNullable(responseError);
    }

    public void setResponseError(String responseError) {
        this.responseError = responseError;
    }

    public boolean isAlreadySentInStream() {
        return alreadySentInStream;
    }

    public void setAlreadySentInStream(boolean alreadySentInStream) {
        this.alreadySentInStream = alreadySentInStream;
    }

    public String getResponseModel() {
        return responseModel;
    }

    public void setResponseModel(String responseModel) {
        this.responseModel = responseModel;
    }

    public Integer getAgentProgressMessageId() {
        return agentProgressMessageId;
    }

    public void setAgentProgressMessageId(Integer agentProgressMessageId) {
        this.agentProgressMessageId = agentProgressMessageId;
    }

    /**
     * Appends a new HTML chunk to the progress message body and returns
     * the complete HTML that should be sent via editMessageText.
     * If the resulting text exceeds maxLength, oldest chunks are dropped.
     *
     * <p>When {@code isThinking} is false and the previous chunk was a thinking
     * placeholder, the placeholder is replaced instead of accumulated —
     * so "🤔 Thinking..." only stays visible while the agent is actively thinking.
     */
    public String appendAgentProgressChunk(String htmlChunk, int maxLength, boolean isThinking) {
        if (htmlChunk == null || htmlChunk.isBlank()) {
            return buildProgressHtml();
        }
        if (lastChunkIsThinking && !agentProgressChunks.isEmpty()) {
            agentProgressChunks.remove(agentProgressChunks.size() - 1);
        }
        agentProgressChunks.add(htmlChunk);
        lastChunkIsThinking = isThinking;

        String merged = buildProgressHtml();
        while (merged.length() > maxLength && agentProgressChunks.size() > 1) {
            agentProgressChunks.remove(0);
            merged = buildProgressHtml();
        }
        return merged;
    }

    private String buildProgressHtml() {
        return String.join("\n\n", agentProgressChunks);
    }

    // --- Error handling ---

    public Exception getException() {
        return exception;
    }

    public void setException(Exception exception) {
        this.exception = exception;
    }

    public MessageHandlerErrorType getErrorType() {
        return errorType;
    }

    public void setErrorType(MessageHandlerErrorType errorType) {
        this.errorType = errorType;
    }

    // --- Guards ---

    public boolean hasError() {
        return errorType != null;
    }

    public boolean hasNoError() {
        return errorType == null;
    }

    public boolean hasResponse() {
        return responseText != null && !responseText.isBlank();
    }

    public boolean hasNoResponse() {
        return responseText == null || responseText.isBlank();
    }

    // --- Terminal state queries ---

    public boolean isCompleted() {
        return state == MessageHandlerState.COMPLETED;
    }

    public boolean isError() {
        return state == MessageHandlerState.ERROR;
    }

    /**
     * Classifies an exception by walking the cause chain and sets the error type
     * and exception on this context. Shared by handler and FSM actions.
     */
    public void classifyAndSetError(Exception e) {
        Throwable t = e;
        while (t != null) {
            if (t instanceof UserMessageTooLongException) {
                this.errorType = MessageHandlerErrorType.MESSAGE_TOO_LONG;
                this.exception = (UserMessageTooLongException) t;
                return;
            }
            if (t instanceof DocumentContentNotExtractableException) {
                this.errorType = MessageHandlerErrorType.DOCUMENT_NOT_EXTRACTABLE;
                this.exception = (DocumentContentNotExtractableException) t;
                return;
            }
            if (t instanceof UnsupportedModelCapabilityException) {
                this.errorType = MessageHandlerErrorType.UNSUPPORTED_CAPABILITY;
                this.exception = (UnsupportedModelCapabilityException) t;
                return;
            }
            if (t instanceof SummarizationFailedException) {
                this.errorType = MessageHandlerErrorType.SUMMARIZATION_FAILED;
                this.exception = (SummarizationFailedException) t;
                return;
            }
            t = t.getCause();
        }
        this.errorType = MessageHandlerErrorType.GENERAL;
        this.exception = e;
    }

    @Override
    public String toString() {
        return "MessageHandlerContext{state=" + state + ", errorType=" + errorType + '}';
    }
}
