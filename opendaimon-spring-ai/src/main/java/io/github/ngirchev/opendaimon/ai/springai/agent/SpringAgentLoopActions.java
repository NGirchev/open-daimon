package io.github.ngirchev.opendaimon.ai.springai.agent;

import io.github.ngirchev.opendaimon.ai.springai.agent.memory.FactExtractor;
import io.github.ngirchev.opendaimon.common.ai.command.AICommand;
import io.github.ngirchev.opendaimon.common.agent.AgentContext;
import io.github.ngirchev.opendaimon.common.agent.AgentLoopActions;
import io.github.ngirchev.opendaimon.common.agent.AgentStepResult;
import io.github.ngirchev.opendaimon.common.agent.AgentStreamEvent;
import io.github.ngirchev.opendaimon.common.agent.AgentToolResult;
import io.github.ngirchev.opendaimon.common.agent.memory.AgentFact;
import io.github.ngirchev.opendaimon.common.agent.memory.AgentMemory;
import io.github.ngirchev.opendaimon.common.model.ConversationThread;
import io.github.ngirchev.opendaimon.common.model.MessageRole;
import io.github.ngirchev.opendaimon.common.model.OpenDaimonMessage;
import io.github.ngirchev.opendaimon.common.repository.ConversationThreadRepository;
import io.github.ngirchev.opendaimon.common.repository.OpenDaimonMessageRepository;
import io.github.ngirchev.opendaimon.common.service.AIUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Spring AI implementation of the agent loop actions.
 *
 * <p>Uses {@link ChatModel} with {@code internalToolExecutionEnabled=false}
 * to get manual control over tool calling. This allows the FSM to manage
 * each ReAct iteration explicitly rather than letting Spring AI auto-execute tools.
 *
 * <p>Tool execution is delegated to {@link ToolCallingManager} which resolves
 * and invokes tools discovered by Spring AI's {@code SpringBeanToolCallbackResolver}.
 */
@Slf4j
public class SpringAgentLoopActions implements AgentLoopActions {

    private static final String NO_TOOL_OUTPUT = "(no tool output)";
    private static final int MEMORY_RECALL_TOP_K = 5;
    private static final Set<String> TOOL_NAMES = Set.of("http_get", "http_post", "web_search", "fetch_url");
    private static final String UNKNOWN_TOOL_NAME = "unknown_tool";
    private static final Pattern TOOL_NAME_TAG_PATTERN =
            Pattern.compile("(?is)<tool_name>\\s*([^<\\s][^<]*)\\s*</tool_name>");
    private static final Pattern ARG_PAIR_PATTERN =
            Pattern.compile("(?is)<arg_key>\\s*(.*?)\\s*</arg_key>\\s*<arg_value>\\s*(.*?)\\s*</arg_value>");
    private static final Pattern KEY_VALUE_LINE_PATTERN =
            Pattern.compile("(?m)^\\s*([a-zA-Z0-9_.-]+)\\s*=\\s*(.+?)\\s*$");
    private static final Pattern HTTP_URL_PATTERN = Pattern.compile("(?i)https?://\\S+");

    private final ChatModel chatModel;
    private final ToolCallingManager toolCallingManager;
    private final List<ToolCallback> toolCallbacks;
    private final AgentMemory agentMemory;
    private final FactExtractor factExtractor;
    private final ChatMemory chatMemory;
    private final ConversationThreadRepository conversationThreadRepository;
    private final OpenDaimonMessageRepository openDaimonMessageRepository;

    private static final String KEY_CONVERSATION_HISTORY = "spring.conversationHistory";
    private static final String KEY_LAST_PROMPT = "spring.lastPrompt";
    private static final String KEY_LAST_RESPONSE = "spring.lastResponse";
    private static final String KEY_STREAMING_EXECUTION = "spring.streamingExecution";
    private static final String KEY_STREAMED_VISIBLE_FINAL_ANSWER = "spring.streamedVisibleFinalAnswer";
    private static final String KEY_MODEL_METADATA_EMITTED = "spring.modelMetadataEmitted";

    public SpringAgentLoopActions(ChatModel chatModel,
                                  ToolCallingManager toolCallingManager,
                                  List<ToolCallback> toolCallbacks,
                                  AgentMemory agentMemory,
                                  FactExtractor factExtractor,
                                  ChatMemory chatMemory) {
        this(chatModel, toolCallingManager, toolCallbacks, agentMemory, factExtractor, chatMemory, null, null);
    }

    public SpringAgentLoopActions(ChatModel chatModel,
                                  ToolCallingManager toolCallingManager,
                                  List<ToolCallback> toolCallbacks,
                                  AgentMemory agentMemory,
                                  FactExtractor factExtractor,
                                  ChatMemory chatMemory,
                                  ConversationThreadRepository conversationThreadRepository,
                                  OpenDaimonMessageRepository openDaimonMessageRepository) {
        this.chatModel = chatModel;
        this.toolCallingManager = toolCallingManager;
        this.toolCallbacks = toolCallbacks != null ? List.copyOf(toolCallbacks) : List.of();
        this.agentMemory = agentMemory;
        this.factExtractor = factExtractor;
        this.chatMemory = chatMemory;
        this.conversationThreadRepository = conversationThreadRepository;
        this.openDaimonMessageRepository = openDaimonMessageRepository;
    }

    @Override
    public void think(AgentContext ctx) {
        ctx.removeExtra(KEY_STREAMED_VISIBLE_FINAL_ANSWER);
        ctx.emitEvent(AgentStreamEvent.thinking(ctx.getCurrentIteration()));
        try {
            List<Message> messages = getOrCreateHistory(ctx);

            if (messages.isEmpty()) {
                String systemPrompt = AgentPromptBuilder.buildSystemPrompt();
                String memoryContext = recallMemoryContext(ctx);
                if (memoryContext != null) {
                    systemPrompt = systemPrompt + "\n\n" + memoryContext;
                }
                messages.add(new SystemMessage(systemPrompt));
                loadConversationHistory(ctx, messages);
                messages.add(new UserMessage(AgentPromptBuilder.buildUserMessage(ctx)));
            }

            List<ToolCallback> effectiveCallbacks = resolveEffectiveTools(ctx);
            String preferredModelId = ctx.getMetadata() != null
                    ? ctx.getMetadata().get(AICommand.PREFERRED_MODEL_ID_FIELD) : null;
            ToolCallingChatOptions.Builder optionsBuilder = ToolCallingChatOptions.builder()
                    .toolCallbacks(effectiveCallbacks)
                    .internalToolExecutionEnabled(false);
            if (preferredModelId != null) {
                optionsBuilder.model(preferredModelId);
            }
            ToolCallingChatOptions chatOptions = optionsBuilder.build();

            Prompt prompt = new Prompt(List.copyOf(messages), chatOptions);
            ctx.putExtra(KEY_LAST_PROMPT, prompt);
            logPromptMessages("think", messages);

            log.debug("Agent think: iteration={}, messages={}, tools={}",
                    ctx.getCurrentIteration(), messages.size(), toolCallbacks.size());

            ChatResponse response = invokeThinkModel(prompt, ctx);
            ctx.putExtra(KEY_LAST_RESPONSE, response);

            maybeEmitModelMetadata(ctx, response.getMetadata().getModel());

            // Emit reasoning content if available from provider (OpenRouter/Anthropic/Ollama)
            String reasoning = extractReasoning(response);
            log.debug("Agent think: reasoning extracted, length={}",
                    reasoning != null ? reasoning.length() : 0);
            if (reasoning != null && !reasoning.isBlank()) {
                ctx.emitEvent(AgentStreamEvent.thinking(reasoning, ctx.getCurrentIteration()));
            }

            response.getResult();

            var output = response.getResult().getOutput();
            String rawOutputText = output != null ? output.getText() : null;
            log.debug("Agent think: raw output text, length={}, content='{}'",
                    rawOutputText != null ? rawOutputText.length() : 0,
                    normalizeForLog(rawOutputText));

            if (response.hasToolCalls()) {
                var toolCalls = output.getToolCalls();
                if (toolCalls.size() > 1) {
                    log.warn("Agent think: LLM returned {} tool calls, only the first will be executed. " +
                            "Parallel tool calls are not yet supported.", toolCalls.size());
                }
                var firstToolCall = toolCalls.getFirst();
                ctx.setCurrentThought("Calling tool: " + firstToolCall.name());
                ctx.setCurrentToolName(firstToolCall.name());
                ctx.setCurrentToolArguments(firstToolCall.arguments());
                ctx.removeExtra(KEY_STREAMED_VISIBLE_FINAL_ANSWER);
                log.info("Agent think: tool call detected — tool={}, args={}",
                        firstToolCall.name(), firstToolCall.arguments());
                if (output != null) {
                    messages.add(output);
                }
            } else {
                String text = sanitizeFinalAnswerText(rawOutputText);
                RecoveredToolCall recoveredToolCall = recoverToolCallFromText(text);
                if (recoveredToolCall != null) {
                    ChatResponse recoveredResponse =
                            buildRecoveredToolCallResponse(response, text, recoveredToolCall, ctx.getCurrentIteration());
                    ctx.putExtra(KEY_LAST_RESPONSE, recoveredResponse);
                    ctx.setCurrentThought("Calling tool: " + recoveredToolCall.toolName());
                    ctx.setCurrentToolName(recoveredToolCall.toolName());
                    ctx.setCurrentToolArguments(recoveredToolCall.argumentsJson());
                    ctx.removeExtra(KEY_STREAMED_VISIBLE_FINAL_ANSWER);
                    log.warn("Agent think: recovered tool call from mixed output, tool={}, args={}, leadingText='{}'",
                            recoveredToolCall.toolName(),
                            recoveredToolCall.argumentsJson(),
                            normalizeForLog(recoveredToolCall.leadingText()));
                    if (recoveredResponse.getResult() != null && recoveredResponse.getResult().getOutput() != null) {
                        messages.add(recoveredResponse.getResult().getOutput());
                    }
                } else {
                    ensureFinalAnswerTailStreamed(ctx, text);
                    ctx.setCurrentThought("Final answer ready");
                    ctx.setCurrentTextResponse(text);
                    log.debug("Agent think: final answer, length={}, content='{}'",
                            text != null ? text.length() : 0,
                            normalizeForLog(text));
                    if (output != null) {
                        messages.add(output);
                    }
                }
            }

        } catch (Exception e) {
            log.error("Agent think failed: {}", e.getMessage(), e);
            ctx.setErrorMessage("LLM call failed: " + e.getMessage());
        }
    }

    private ChatResponse invokeThinkModel(Prompt prompt, AgentContext ctx) {
        if (!isStreamingExecution(ctx)) {
            return chatModel.call(prompt);
        }
        return streamThinkResponse(prompt, ctx);
    }

    private ChatResponse streamThinkResponse(Prompt prompt, AgentContext ctx) {
        AtomicReference<ChatResponse> lastResponse = new AtomicReference<>();
        StringBuilder fullText = new StringBuilder();
        AtomicReference<String> previousChunkText = new AtomicReference<>("");
        AtomicReference<List<AssistantMessage.ToolCall>> latestStreamToolCalls = new AtomicReference<>(List.of());
        AtomicBoolean hasStructuredToolCall = new AtomicBoolean(false);
        try {
            Flux<ChatResponse> responseFlux = chatModel.stream(prompt);
            if (responseFlux == null) {
                throw new IllegalStateException("chatModel.stream returned null response flux");
            }
            responseFlux
                    .doOnNext(chunk -> {
                        lastResponse.set(chunk);
                        if (chunk.getMetadata() != null) {
                            maybeEmitModelMetadata(ctx, chunk.getMetadata().getModel());
                        }
                        List<AssistantMessage.ToolCall> chunkToolCalls = extractToolCallsFromChunk(chunk);
                        if (!chunkToolCalls.isEmpty()) {
                            hasStructuredToolCall.set(true);
                            latestStreamToolCalls.set(List.copyOf(chunkToolCalls));
                        }
                        AIUtils.extractText(chunk).ifPresent(text -> {
                            String delta = resolveStreamDelta(previousChunkText.get(), text);
                            previousChunkText.set(text);
                            if (delta.isEmpty()) {
                                return;
                            }
                            fullText.append(delta);
                            emitStreamFinalAnswerDelta(ctx, fullText.toString(), hasStructuredToolCall.get());
                        });
                    })
                    .blockLast(Duration.ofMinutes(10));
        } catch (Exception e) {
            if (lastResponse.get() == null) {
                log.warn("Agent think: stream path unavailable, falling back to call(): {}", e.getMessage());
                return chatModel.call(prompt);
            }
            throw e;
        }

        ChatResponse terminalChunk = lastResponse.get();
        if (terminalChunk == null) {
            log.warn("Agent think: stream returned no chunks, falling back to call()");
            return chatModel.call(prompt);
        }
        return mergeStreamingText(terminalChunk, fullText.toString(), latestStreamToolCalls.get());
    }

    private void maybeEmitModelMetadata(AgentContext ctx, String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return;
        }
        ctx.setModelName(modelName);
        if (!isStreamingExecution(ctx)) {
            return;
        }
        if (Boolean.TRUE.equals(ctx.getExtra(KEY_MODEL_METADATA_EMITTED))) {
            return;
        }
        ctx.emitEvent(AgentStreamEvent.metadata(modelName, ctx.getCurrentIteration()));
        ctx.putExtra(KEY_MODEL_METADATA_EMITTED, Boolean.TRUE);
    }

    private void emitStreamFinalAnswerDelta(AgentContext ctx, String accumulatedText, boolean hasStructuredToolCall) {
        if (hasStructuredToolCall) {
            return;
        }
        String normalized = sanitizeFinalAnswerText(accumulatedText);
        if (normalized == null || normalized.isBlank()) {
            return;
        }
        String streamedVisible = getStreamedFinalVisibleAnswer(ctx);
        if (normalized.equals(streamedVisible)) {
            return;
        }
        if (streamedVisible.isBlank()) {
            ctx.emitEvent(AgentStreamEvent.finalAnswerChunk(normalized, ctx.getCurrentIteration()));
            ctx.putExtra(KEY_STREAMED_VISIBLE_FINAL_ANSWER, normalized);
            return;
        }
        if (normalized.startsWith(streamedVisible)) {
            String delta = normalized.substring(streamedVisible.length());
            if (!delta.isEmpty()) {
                ctx.emitEvent(AgentStreamEvent.finalAnswerChunk(delta, ctx.getCurrentIteration()));
            }
            ctx.putExtra(KEY_STREAMED_VISIBLE_FINAL_ANSWER, normalized);
            return;
        }
        // Non-monotonic snapshots can briefly happen with some providers; ignore to avoid duplicate chunks.
        if (streamedVisible.startsWith(normalized)) {
            return;
        }
        log.debug("Agent think: stream delta diverged, skipping chunk emission. streamed='{}', normalized='{}'",
                normalizeForLog(streamedVisible), normalizeForLog(normalized));
    }

    static String resolveStreamDelta(String previousChunkText, String currentChunkText) {
        if (currentChunkText == null || currentChunkText.isEmpty()) {
            return "";
        }
        if (previousChunkText == null || previousChunkText.isEmpty()) {
            return currentChunkText;
        }
        if (currentChunkText.equals(previousChunkText)) {
            return "";
        }
        if (currentChunkText.startsWith(previousChunkText)) {
            return currentChunkText.substring(previousChunkText.length());
        }
        // Provider may temporarily return a shorter snapshot (non-monotonic cumulative stream).
        if (previousChunkText.startsWith(currentChunkText)) {
            return "";
        }
        // Treat as normal delta chunk when snapshots do not overlap.
        return currentChunkText;
    }

    private static ChatResponse mergeStreamingText(ChatResponse terminalChunk,
                                                   String fullText,
                                                   List<AssistantMessage.ToolCall> streamedToolCalls) {
        if (fullText == null || fullText.isBlank()) {
            return terminalChunk;
        }
        AssistantMessage chunkOutput = terminalChunk.getResult() != null
                ? terminalChunk.getResult().getOutput() : null;
        List<AssistantMessage.ToolCall> mergedToolCalls = List.of();
        if (streamedToolCalls != null && !streamedToolCalls.isEmpty()) {
            mergedToolCalls = streamedToolCalls;
        } else if (chunkOutput != null && chunkOutput.getToolCalls() != null && !chunkOutput.getToolCalls().isEmpty()) {
            mergedToolCalls = chunkOutput.getToolCalls();
        }
        AssistantMessage mergedOutput;
        if (!mergedToolCalls.isEmpty()) {
            mergedOutput = AssistantMessage.builder()
                    .content(fullText)
                    .toolCalls(mergedToolCalls)
                    .build();
        } else {
            mergedOutput = new AssistantMessage(fullText);
        }
        return ChatResponse.builder()
                .metadata(terminalChunk.getMetadata())
                .generations(List.of(new Generation(mergedOutput)))
                .build();
    }

    private static List<AssistantMessage.ToolCall> extractToolCallsFromChunk(ChatResponse chunk) {
        if (chunk == null || chunk.getResult() == null || chunk.getResult().getOutput() == null) {
            return List.of();
        }
        List<AssistantMessage.ToolCall> toolCalls = chunk.getResult().getOutput().getToolCalls();
        if (toolCalls == null || toolCalls.isEmpty()) {
            return List.of();
        }
        return toolCalls;
    }

    private void ensureFinalAnswerTailStreamed(AgentContext ctx, String finalAnswer) {
        if (!isStreamingExecution(ctx)) {
            return;
        }
        String normalizedFinalAnswer = extractUserTextBeforeToolPayload(
                finalAnswer == null ? "" : finalAnswer).trim();
        if (normalizedFinalAnswer.isBlank()) {
            return;
        }
        String streamedVisible = getStreamedFinalVisibleAnswer(ctx);
        if (streamedVisible.isBlank()) {
            ctx.emitEvent(AgentStreamEvent.finalAnswerChunk(normalizedFinalAnswer, ctx.getCurrentIteration()));
            ctx.putExtra(KEY_STREAMED_VISIBLE_FINAL_ANSWER, normalizedFinalAnswer);
            return;
        }
        if (normalizedFinalAnswer.startsWith(streamedVisible)
                && normalizedFinalAnswer.length() > streamedVisible.length()) {
            String delta = normalizedFinalAnswer.substring(streamedVisible.length());
            if (!delta.isEmpty()) {
                ctx.emitEvent(AgentStreamEvent.finalAnswerChunk(delta, ctx.getCurrentIteration()));
            }
            ctx.putExtra(KEY_STREAMED_VISIBLE_FINAL_ANSWER, normalizedFinalAnswer);
        }
    }

    @Override
    public void executeTool(AgentContext ctx) {
        ctx.emitEvent(AgentStreamEvent.toolCall(
                ctx.getCurrentToolName(), ctx.getCurrentToolArguments(), ctx.getCurrentIteration()));
        try {
            Prompt prompt = ctx.getExtra(KEY_LAST_PROMPT);
            ChatResponse response = ctx.getExtra(KEY_LAST_RESPONSE);

            if (prompt == null || response == null) {
                ctx.setErrorMessage("No prompt/response available for tool execution");
                return;
            }

            log.info("Agent executeTool: tool={}", ctx.getCurrentToolName());

            ToolExecutionResult toolResult = toolCallingManager.executeToolCalls(prompt, response);

            List<Message> resultMessages = toolResult.conversationHistory();
            String observation = extractToolObservation(resultMessages);

            ctx.setToolResult(AgentToolResult.success(ctx.getCurrentToolName(), observation));

            List<Message> messages = getOrCreateHistory(ctx);
            if (!resultMessages.isEmpty()) {
                Message lastMsg = resultMessages.getLast();
                messages.add(lastMsg);
            }

            log.info("Agent executeTool: completed, observation length={}, observation='{}'",
                    observation != null ? observation.length() : 0,
                    normalizeForLog(observation));

        } catch (Exception e) {
            log.error("Agent executeTool failed: tool={}, error={}",
                    ctx.getCurrentToolName(), e.getMessage(), e);
            ctx.setToolResult(AgentToolResult.failure(ctx.getCurrentToolName(), e.getMessage()));
        }
    }

    @Override
    public void observe(AgentContext ctx) {
        AgentToolResult toolResult = ctx.getToolResult();
        String observation = toolResult != null && toolResult.success()
                ? toolResult.result()
                : (toolResult != null ? "Error: " + toolResult.error() : "No result");
        ctx.emitEvent(AgentStreamEvent.observation(observation, ctx.getCurrentIteration()));

        ctx.recordStep(new AgentStepResult(
                ctx.getCurrentIteration(),
                ctx.getCurrentThought(),
                ctx.getCurrentToolName(),
                ctx.getCurrentToolArguments(),
                observation,
                Instant.now()
        ));

        ctx.incrementIteration();
        ctx.resetIterationState();

        log.debug("Agent observe: iteration={} recorded, moving to next think cycle",
                ctx.getCurrentIteration());
    }

    @Override
    public void answer(AgentContext ctx) {
        ctx.setFinalAnswer(ctx.getCurrentTextResponse());
        saveConversationHistory(ctx);
        extractFacts(ctx);
        cleanup(ctx);
        log.debug("Agent answer: final answer set, length={}, content='{}'",
                ctx.getFinalAnswer() != null ? ctx.getFinalAnswer().length() : 0,
                normalizeForLog(ctx.getFinalAnswer()));
    }

    @Override
    public void handleMaxIterations(AgentContext ctx) {
        String limitNotice = buildMaxIterationsNotice(ctx);
        String synthesizedAnswer = synthesizeMaxIterationsAnswer(ctx);
        ctx.setFinalAnswer(composeMaxIterationsAnswer(ctx, limitNotice, synthesizedAnswer));
        ctx.setCurrentTextResponse(ctx.getFinalAnswer());
        saveConversationHistory(ctx);
        extractFacts(ctx);
        cleanup(ctx);
        log.warn("Agent handleMaxIterations: {} iterations exhausted", ctx.getMaxIterations());
    }

    @Override
    public void handleError(AgentContext ctx) {
        if (ctx.getErrorMessage() == null) {
            ctx.setErrorMessage("LLM returned neither a tool call nor a final answer");
        }
        cleanup(ctx);
        log.error("Agent handleError: {}", ctx.getErrorMessage());
    }

    /**
     * Extracts key facts from the completed conversation and stores them in memory.
     * Best-effort — failures don't affect the agent response.
     */
    private void extractFacts(AgentContext ctx) {
        if (factExtractor != null && !ctx.getStepHistory().isEmpty()) {
            // Only extract when there were tool interactions (non-trivial conversations)
            factExtractor.extractAndStore(ctx);
        }
    }

    private String recallMemoryContext(AgentContext ctx) {
        if (agentMemory == null || ctx.getConversationId() == null) {
            return null;
        }
        try {
            List<AgentFact> facts = agentMemory.recall(
                    ctx.getConversationId(), ctx.getTask(), MEMORY_RECALL_TOP_K);
            if (facts.isEmpty()) {
                return null;
            }
            String factsText = facts.stream()
                    .map(AgentFact::content)
                    .collect(Collectors.joining("\n- ", "- ", ""));
            log.debug("Agent memory: recalled {} facts for task", facts.size());
            return "Relevant information from memory:\n" + factsText;
        } catch (Exception e) {
            log.warn("Agent memory recall failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Filters the full tool callback list by {@code ctx.getEnabledTools()}.
     * If enabledTools is empty or null, all tools are available (default behavior).
     */
    private List<ToolCallback> resolveEffectiveTools(AgentContext ctx) {
        Set<String> enabled = ctx.getEnabledTools();
        if (enabled == null || enabled.isEmpty()) {
            return toolCallbacks;
        }
        List<ToolCallback> filtered = toolCallbacks.stream()
                .filter(cb -> enabled.contains(cb.getToolDefinition().name()))
                .toList();
        if (filtered.isEmpty()) {
            log.warn("Agent think: enabledTools={} matched no registered tools, using all", enabled);
            return toolCallbacks;
        }
        return filtered;
    }

    /**
     * Extracts the tool result text from the conversation history returned by
     * {@link ToolCallingManager#executeToolCalls}.
     */
    private String extractToolObservation(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return NO_TOOL_OUTPUT;
        }
        Message lastMessage = messages.getLast();
        if (lastMessage instanceof ToolResponseMessage toolResponseMessage) {
            return extractToolResponseData(toolResponseMessage);
        }

        String text = lastMessage.getText();
        if (text != null && !text.isBlank()) {
            return text;
        }

        for (int index = messages.size() - 2; index >= 0; index--) {
            String previousText = messages.get(index).getText();
            if (previousText != null && !previousText.isBlank()) {
                return previousText;
            }
        }
        return NO_TOOL_OUTPUT;
    }

    private String extractToolResponseData(ToolResponseMessage toolResponseMessage) {
        if (toolResponseMessage.getResponses() == null || toolResponseMessage.getResponses().isEmpty()) {
            return NO_TOOL_OUTPUT;
        }

        String mergedResponseData = toolResponseMessage.getResponses().stream()
                .map(ToolResponseMessage.ToolResponse::responseData)
                .filter(responseData -> responseData != null && !responseData.isBlank())
                .collect(Collectors.joining("\n\n"));

        return mergedResponseData.isBlank() ? NO_TOOL_OUTPUT : mergedResponseData;
    }

    private void cleanup(AgentContext ctx) {
        ctx.removeExtra(KEY_CONVERSATION_HISTORY);
        ctx.removeExtra(KEY_LAST_PROMPT);
        ctx.removeExtra(KEY_LAST_RESPONSE);
        // Keep streamed visible final answer until executor emits terminal events.
        // ReActAgentExecutor reads it after FSM completion to avoid duplicate
        // FINAL_ANSWER_CHUNK fallback emission.
        ctx.removeExtra(KEY_STREAMING_EXECUTION);
    }

    private void logPromptMessages(String phase, List<Message> messages) {
        if (!log.isDebugEnabled() || messages == null || messages.isEmpty()) {
            return;
        }
        for (int index = 0; index < messages.size(); index++) {
            Message message = messages.get(index);
            log.debug("Agent {} request: messageIndex={}, type={}, text='{}'",
                    phase,
                    index,
                    message != null ? message.getMessageType() : null,
                    normalizeForLog(message != null ? message.getText() : null));
        }
    }

    private String synthesizeMaxIterationsAnswer(AgentContext ctx) {
        try {
            List<Message> messages = List.of(
                    new SystemMessage(AgentPromptBuilder.buildMaxIterationsSynthesisSystemPrompt()),
                    new UserMessage(AgentPromptBuilder.buildMaxIterationsSynthesisUserMessage(ctx))
            );

            String preferredModelId = ctx.getMetadata() != null
                    ? ctx.getMetadata().get(AICommand.PREFERRED_MODEL_ID_FIELD) : null;
            ToolCallingChatOptions.Builder optionsBuilder = ToolCallingChatOptions.builder()
                    .internalToolExecutionEnabled(false);
            if (preferredModelId != null) {
                optionsBuilder.model(preferredModelId);
            }
            Prompt prompt = new Prompt(messages, optionsBuilder.build());
            logPromptMessages("max-iterations-synthesis", messages);

            ChatResponse response = chatModel.call(prompt);
            if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
                return null;
            }
            if (response.getMetadata().getModel() != null) {
                ctx.setModelName(response.getMetadata().getModel());
            }

            String rawOutputText = response.getResult().getOutput().getText();
            String sanitized = sanitizeFinalAnswerText(rawOutputText);
            if (sanitized == null || sanitized.isBlank()) {
                return null;
            }
            if (containsToolPayloadMarkers(sanitized)) {
                String recoveredPrefix = extractUserTextBeforeToolPayload(sanitized);
                return recoveredPrefix.isBlank() ? null : recoveredPrefix;
            }
            return sanitized;
        } catch (Exception e) {
            log.warn("Agent handleMaxIterations: final synthesis failed: {}", e.getMessage(), e);
            return null;
        }
    }

    private String composeMaxIterationsAnswer(AgentContext ctx, String limitNotice, String synthesizedAnswer) {
        if (synthesizedAnswer == null || synthesizedAnswer.isBlank()) {
            return limitNotice + "\n\n" + maxIterationsFallbackText(ctx);
        }
        String normalized = synthesizedAnswer.trim();
        if (normalized.startsWith(limitNotice)) {
            return normalized;
        }
        return limitNotice + "\n\n" + normalized;
    }

    private String buildMaxIterationsNotice(AgentContext ctx) {
        if (isRussianLanguage(ctx)) {
            return "Достигнут лимит в " + ctx.getMaxIterations()
                    + " итераций. Ниже — лучший ответ на основе уже собранных данных.";
        }
        return "Reached the iteration limit of " + ctx.getMaxIterations()
                + ". Below is the best answer based on the collected data.";
    }

    private String maxIterationsFallbackText(AgentContext ctx) {
        if (isRussianLanguage(ctx)) {
            return "Не удалось подготовить полный итоговый ответ на последнем шаге. "
                    + "Попробуйте уточнить запрос или сузить задачу.";
        }
        return "Could not prepare a complete final answer on the last step. "
                + "Try refining or narrowing your request.";
    }

    private boolean isRussianLanguage(AgentContext ctx) {
        String languageCode = ctx.getMetadata() != null
                ? ctx.getMetadata().get(AICommand.LANGUAGE_CODE_FIELD)
                : null;
        return languageCode != null && languageCode.toLowerCase(Locale.ROOT).startsWith("ru");
    }

    /**
     * Attempts to extract reasoning/thinking content from the LLM response.
     *
     * <p>Two sources are checked:
     * <ol>
     *   <li>Generation metadata key "reasoningContent" (OpenRouter/Anthropic)</li>
     *   <li>{@code <think>...</think>} tags in text output (Ollama with think=true)</li>
     * </ol>
     *
     * @return reasoning text, or null if not available
     */
    static String extractReasoning(ChatResponse response) {
        try {
            if (response == null) {
                return null;
            } else {
                response.getResult();
            }
            var metadata = response.getResult().getMetadata();
            // 1. Check "thinking" metadata key (Spring AI Ollama 1.1+ with think=true)
            Object thinking = metadata.get("thinking");
            if (thinking instanceof String text && !text.isBlank()) {
                log.debug("Agent extractReasoning: found 'thinking' metadata, length={}", text.length());
                return text;
            }
            // 2. Check "reasoningContent" metadata key (OpenRouter/Anthropic)
            Object reasoning = metadata.get("reasoningContent");
            if (reasoning instanceof String text && !text.isBlank()) {
                log.debug("Agent extractReasoning: found 'reasoningContent' metadata, length={}", text.length());
                return text;
            }
            // 3. Fallback: check <think> tags in text (older Ollama or custom models)
            var output = response.getResult().getOutput();
            if (output != null && output.getText() != null) {
                String rawText = output.getText();
                boolean hasThinkTags = rawText.contains("<think>");
                if (hasThinkTags) {
                    log.debug("Agent extractReasoning: found <think> tags, textLength={}", rawText.length());
                    return extractThinkTags(rawText);
                }
            }
        } catch (Exception e) {
            log.debug("Could not extract reasoning from response: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Extracts content from {@code <think>...</think>} tags (Ollama thinking mode).
     * Returns the thinking text, or null if no tags found.
     */
    static String extractThinkTags(String text) {
        if (text == null) {
            return null;
        }
        int start = text.indexOf("<think>");
        int end = text.indexOf("</think>");
        if (start < 0 || end < 0 || end <= start) {
            return null;
        }
        String thinking = text.substring(start + "<think>".length(), end).trim();
        return thinking.isEmpty() ? null : thinking;
    }

    /**
     * Strips {@code <think>...</think>} block from text, returning only the answer part.
     */
    static String stripThinkTags(String text) {
        if (text == null) {
            return null;
        }
        int start = text.indexOf("<think>");
        int end = text.indexOf("</think>");
        if (start < 0) {
            return text;
        }
        if (end < 0 || end <= start) {
            return text.substring(0, start).trim();
        }
        return (text.substring(0, start) + text.substring(end + "</think>".length())).trim();
    }

    static String sanitizeFinalAnswerText(String text) {
        String withoutThinking = stripThinkTags(text);
        if (withoutThinking == null) {
            return null;
        }
        return withoutThinking.trim();
    }

    private ChatResponse buildRecoveredToolCallResponse(ChatResponse sourceResponse,
                                                        String rawOutputText,
                                                        RecoveredToolCall recoveredToolCall,
                                                        int iteration) {
        String toolCallId = "recovered-tool-call-" + iteration;
        AssistantMessage recoveredAssistantMessage = AssistantMessage.builder()
                .content(rawOutputText)
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        toolCallId,
                        "function",
                        recoveredToolCall.toolName(),
                        recoveredToolCall.argumentsJson())))
                .build();

        return ChatResponse.builder()
                .metadata(sourceResponse.getMetadata())
                .generations(List.of(new Generation(recoveredAssistantMessage)))
                .build();
    }

    /**
     * Loads prior conversation turns from {@link ChatMemory} and appends them
     * between the system prompt and the current user message. Skips any
     * {@link SystemMessage} entries from memory (e.g. summaries) to avoid
     * conflicting with the agent system prompt — the summary content is
     * prepended to the first system message instead.
     *
     * <p>If chat memory is empty for an existing conversation, restores history
     * from primary message storage ({@code message} table) by thread key.
     */
    private void loadConversationHistory(AgentContext ctx, List<Message> messages) {
        if (chatMemory == null || ctx.getConversationId() == null) {
            return;
        }
        try {
            String conversationId = ctx.getConversationId();
            List<Message> history = chatMemory.get(conversationId);
            boolean restoredFromPrimaryStore = false;
            if (history == null || history.isEmpty()) {
                history = restoreHistoryFromPrimaryStore(conversationId);
                restoredFromPrimaryStore = history != null && !history.isEmpty();
                if (restoredFromPrimaryStore) {
                    chatMemory.add(conversationId, history);
                    log.info("Agent think: restored {} history messages from primary store for conversationId={}",
                            history.size(), conversationId);
                }
            }
            if (history == null || history.isEmpty()) {
                return;
            }
            for (Message msg : history) {
                if (msg.getMessageType() == MessageType.SYSTEM) {
                    // Append summary to existing system prompt instead of adding a second SystemMessage
                    if (!messages.isEmpty() && messages.getFirst() instanceof SystemMessage existing) {
                        messages.set(0, new SystemMessage(existing.getText() + "\n\n" + msg.getText()));
                    }
                } else {
                    messages.add(msg);
                }
            }
            if (restoredFromPrimaryStore) {
                log.debug("Agent think: loaded {} restored history messages", history.size());
            } else {
                log.debug("Agent think: loaded {} history messages from ChatMemory", history.size());
            }
        } catch (Exception e) {
            log.warn("Agent think: failed to load conversation history: {}", e.getMessage());
        }
    }

    private List<Message> restoreHistoryFromPrimaryStore(String conversationId) {
        if (conversationThreadRepository == null || openDaimonMessageRepository == null) {
            return List.of();
        }
        try {
            ConversationThread thread = conversationThreadRepository.findByThreadKey(conversationId).orElse(null);
            if (thread == null) {
                return List.of();
            }
            List<OpenDaimonMessage> storedMessages = openDaimonMessageRepository.findByThreadOrderBySequenceNumberAsc(thread);
            if (storedMessages == null || storedMessages.isEmpty()) {
                return List.of();
            }
            List<Message> restored = new ArrayList<>(storedMessages.size() + 1);
            String summary = buildSummaryContent(thread);
            if (!summary.isBlank()) {
                restored.add(new SystemMessage(summary));
            }
            for (OpenDaimonMessage stored : storedMessages) {
                Message springMessage = convertToSpringMessage(stored);
                if (springMessage != null) {
                    restored.add(springMessage);
                }
            }
            return restored;
        } catch (Exception e) {
            log.warn("Agent think: failed to restore history from primary store for conversationId={}: {}",
                    conversationId, e.getMessage());
            return List.of();
        }
    }

    private Message convertToSpringMessage(OpenDaimonMessage msg) {
        if (msg == null || msg.getRole() == null) {
            return null;
        }
        String content = msg.getContent() != null ? msg.getContent() : "";
        if (msg.getRole() == MessageRole.USER) {
            return new UserMessage(enrichWithAttachmentInfo(content, msg.getAttachments()));
        }
        if (msg.getRole() == MessageRole.ASSISTANT) {
            return new AssistantMessage(content);
        }
        return new SystemMessage(content);
    }

    private String enrichWithAttachmentInfo(String content, List<Map<String, Object>> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return content;
        }
        StringBuilder text = new StringBuilder(content != null ? content : "");
        text.append("\n[Attached files: ");
        for (int i = 0; i < attachments.size(); i++) {
            if (i > 0) {
                text.append(", ");
            }
            Map<String, Object> att = attachments.get(i);
            String filename = att.get("filename") != null ? String.valueOf(att.get("filename")) : null;
            String mimeType = att.get("mimeType") != null ? String.valueOf(att.get("mimeType")) : null;
            if (filename != null) {
                text.append("\"").append(filename).append("\"");
            }
            if (mimeType != null) {
                text.append(" (").append(mimeType).append(")");
            }
        }
        text.append("]");
        return text.toString();
    }

    private String buildSummaryContent(ConversationThread thread) {
        if (thread == null || (thread.getSummary() == null || thread.getSummary().isBlank())) {
            return "";
        }
        StringBuilder content = new StringBuilder();
        content.append("Summary of previous conversation:\n");
        content.append(thread.getSummary());
        if (thread.getMemoryBullets() != null && !thread.getMemoryBullets().isEmpty()) {
            content.append("\n\nKey points:\n");
            for (String bullet : thread.getMemoryBullets()) {
                if (bullet != null && !bullet.isBlank()) {
                    content.append("• ").append(bullet).append("\n");
                }
            }
        }
        return content.toString();
    }

    /**
     * Persists the current user message and final assistant answer to
     * {@link ChatMemory} so they are available in subsequent turns.
     */
    private void saveConversationHistory(AgentContext ctx) {
        if (chatMemory == null || ctx.getConversationId() == null) {
            return;
        }
        try {
            String conversationId = ctx.getConversationId();
            chatMemory.add(conversationId, List.of(
                    new UserMessage(ctx.getTask()),
                    new AssistantMessage(ctx.getCurrentTextResponse())
            ));
            log.debug("Agent answer: saved user+assistant messages to ChatMemory");
        } catch (Exception e) {
            log.warn("Agent answer: failed to save conversation history: {}", e.getMessage());
        }
    }

    private List<Message> getOrCreateHistory(AgentContext ctx) {
        List<Message> history = ctx.getExtra(KEY_CONVERSATION_HISTORY);
        if (history == null) {
            history = new ArrayList<>();
            ctx.putExtra(KEY_CONVERSATION_HISTORY, history);
        }
        return history;
    }

    static void markStreamingExecution(AgentContext ctx) {
        ctx.putExtra(KEY_STREAMING_EXECUTION, Boolean.TRUE);
    }

    static boolean isStreamingExecution(AgentContext ctx) {
        return Boolean.TRUE.equals(ctx.getExtra(KEY_STREAMING_EXECUTION));
    }

    static String getStreamedFinalVisibleAnswer(AgentContext ctx) {
        String streamed = ctx.getExtra(KEY_STREAMED_VISIBLE_FINAL_ANSWER);
        return streamed == null ? "" : streamed;
    }

    static boolean wasModelMetadataEmitted(AgentContext ctx) {
        return Boolean.TRUE.equals(ctx.getExtra(KEY_MODEL_METADATA_EMITTED));
    }

    static RecoveredToolCall recoverToolCallFromText(String text) {
        if (text == null || text.isBlank() || !containsToolPayloadMarkers(text)) {
            return null;
        }
        String toolName = extractToolName(text);
        if (toolName == null || toolName.isBlank()) {
            toolName = UNKNOWN_TOOL_NAME;
        }
        String argumentsJson = extractToolArgumentsJson(text, toolName);
        String leadingText = extractUserTextBeforeToolPayload(text);
        return new RecoveredToolCall(toolName, argumentsJson, leadingText);
    }

    static boolean containsToolPayloadMarkers(String text) {
        return findFirstToolPayloadIndex(text) >= 0;
    }

    static String extractUserTextBeforeToolPayload(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        int markerIndex = findFirstToolPayloadIndex(text);
        String candidate = markerIndex >= 0 ? text.substring(0, markerIndex) : text;
        return candidate.trim();
    }

    static int findFirstToolPayloadIndex(String text) {
        if (text == null || text.isBlank()) {
            return -1;
        }
        int firstIndex = Integer.MAX_VALUE;
        String lowered = text.toLowerCase(Locale.ROOT);
        String[] markers = {
                "<tool_call",
                "</tool_call>",
                "<arg_key>",
                "</arg_key>",
                "<arg_value>",
                "</arg_value>",
                "<tool_name>",
                "</tool_name>"
        };
        for (String marker : markers) {
            int markerIndex = lowered.indexOf(marker);
            if (markerIndex >= 0 && markerIndex < firstIndex) {
                firstIndex = markerIndex;
            }
        }

        int standaloneToolLineIndex = findFirstStandaloneToolLineIndex(lowered);
        if (standaloneToolLineIndex >= 0 && standaloneToolLineIndex < firstIndex) {
            firstIndex = standaloneToolLineIndex;
        }

        return firstIndex == Integer.MAX_VALUE ? -1 : firstIndex;
    }

    private static int findFirstStandaloneToolLineIndex(String loweredText) {
        int offset = 0;
        for (String rawLine : loweredText.split("\n", -1)) {
            String line = rawLine.endsWith("\r") ? rawLine.substring(0, rawLine.length() - 1) : rawLine;
            if (TOOL_NAMES.contains(line.trim())) {
                return offset;
            }
            offset += rawLine.length() + 1;
        }
        return -1;
    }

    private static String extractToolName(String text) {
        Matcher toolNameMatcher = TOOL_NAME_TAG_PATTERN.matcher(text);
        if (toolNameMatcher.find()) {
            return toolNameMatcher.group(1).trim().toLowerCase(Locale.ROOT);
        }
        for (String line : text.lines().toList()) {
            String trimmed = line.trim().toLowerCase(Locale.ROOT);
            if (TOOL_NAMES.contains(trimmed)) {
                return trimmed;
            }
        }
        return null;
    }

    private static String extractToolArgumentsJson(String text, String toolName) {
        Map<String, String> arguments = new LinkedHashMap<>();

        Matcher argPairMatcher = ARG_PAIR_PATTERN.matcher(text);
        while (argPairMatcher.find()) {
            String key = argPairMatcher.group(1) != null ? argPairMatcher.group(1).trim() : "";
            String value = argPairMatcher.group(2) != null ? argPairMatcher.group(2).trim() : "";
            if (!key.isBlank() && !value.isBlank()) {
                arguments.put(key, value);
            }
        }

        if (arguments.isEmpty()) {
            Matcher keyValueMatcher = KEY_VALUE_LINE_PATTERN.matcher(text);
            while (keyValueMatcher.find()) {
                String key = keyValueMatcher.group(1) != null ? keyValueMatcher.group(1).trim() : "";
                String value = keyValueMatcher.group(2) != null ? keyValueMatcher.group(2).trim() : "";
                if (!key.isBlank() && !value.isBlank()) {
                    arguments.put(key, value);
                }
            }
        }

        if (arguments.isEmpty()) {
            String extractedUrl = extractFirstHttpUrl(text);
            if (extractedUrl != null) {
                arguments.put(defaultArgumentKey(toolName), extractedUrl);
            }
        }

        return toJsonObject(arguments);
    }

    private static String extractFirstHttpUrl(String text) {
        Matcher matcher = HTTP_URL_PATTERN.matcher(text);
        if (matcher.find()) {
            return matcher.group();
        }
        return null;
    }

    private static String defaultArgumentKey(String toolName) {
        if ("web_search".equals(toolName)) {
            return "query";
        }
        return "url";
    }

    private static String toJsonObject(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return "{}";
        }
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (!first) {
                json.append(",");
            }
            first = false;
            json.append("\"")
                    .append(escapeJson(entry.getKey()))
                    .append("\":\"")
                    .append(escapeJson(entry.getValue()))
                    .append("\"");
        }
        json.append("}");
        return json.toString();
    }

    private static String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static String normalizeForLog(String text) {
        if (text == null) {
            return "null";
        }
        return text.replace("\r", "\\r").replace("\n", "\\n");
    }

    static final record RecoveredToolCall(String toolName, String argumentsJson, String leadingText) {
    }
}
