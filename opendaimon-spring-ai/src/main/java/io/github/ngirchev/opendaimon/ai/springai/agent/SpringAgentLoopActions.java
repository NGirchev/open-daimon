package io.github.ngirchev.opendaimon.ai.springai.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ngirchev.opendaimon.ai.springai.agent.RawToolCallParser.RawToolCall;
import io.github.ngirchev.opendaimon.ai.springai.agent.ToolObservationClassifier.Classification;
import io.github.ngirchev.opendaimon.ai.springai.tool.UrlLivenessChecker;
import io.github.ngirchev.opendaimon.ai.springai.tool.WebTools;
import io.github.ngirchev.opendaimon.bulkhead.service.PriorityRequestExecutor;
import io.github.ngirchev.opendaimon.common.ai.command.AICommand;
import io.github.ngirchev.opendaimon.common.agent.AgentContext;
import io.github.ngirchev.opendaimon.common.agent.AgentLoopActions;
import io.github.ngirchev.opendaimon.common.agent.AgentStepResult;
import io.github.ngirchev.opendaimon.common.agent.AgentStreamEvent;
import io.github.ngirchev.opendaimon.common.agent.AgentToolResult;
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
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import reactor.core.publisher.Flux;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
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
 *
 * <p>Cross-cutting concerns that are not FSM state transitions are delegated to
 * focused helpers:
 * <ul>
 *   <li>{@link AgentTextSanitizer} — strips {@code <think>}/{@code <tool_call>} markup
 *       from batch text; also owns the hot-path {@code appendDelta} helper used in
 *       {@link #streamAndAggregate(AgentContext, Prompt)}.</li>
 *   <li>{@link RawToolCallParser} — parses fallback XML-style tool calls emitted by
 *       some models as plain text.</li>
 *   <li>{@link ToolObservationClassifier} — turns a Spring AI-flavoured
 *       {@link AgentToolResult} into the {@code (streamContent, observation, toolError)}
 *       triple expected by the observation event + step history.</li>
 *   <li>{@link SummaryModelInvoker} — produces the MAX_ITERATIONS closing answer
 *       via tool-less LLM call with a deterministic step-history fallback.</li>
 * </ul>
 */
@Slf4j
public class SpringAgentLoopActions implements AgentLoopActions {

    private final ChatModel chatModel;
    private final ToolCallingManager toolCallingManager;
    private final List<ToolCallback> toolCallbacks;
    private final ChatMemory chatMemory;
    private final Duration streamTimeout;
    /** Optional — when set, final-answer text is passed through to strip dead URLs. */
    private final UrlLivenessChecker urlLivenessChecker;
    private final RawToolCallParser rawToolCallParser;
    private final SummaryModelInvoker summaryModelInvoker;
    private final PriorityRequestExecutor priorityRequestExecutor;

    private static final String KEY_CONVERSATION_HISTORY = "spring.conversationHistory";
    private static final String KEY_LAST_PROMPT = "spring.lastPrompt";
    private static final String KEY_LAST_RESPONSE = "spring.lastResponse";
    private static final String KEY_FAILED_FETCH_URLS = "spring.failedFetchUrls";
    private static final String KEY_FAILED_FETCH_HOSTS = "spring.failedFetchHosts";
    private static final String KEY_FALLBACK_TOOL_CALL = "spring.fallbackToolCall";
    private static final String TOOL_FETCH_URL = "fetch_url";
    private static final int MAX_FAILED_FETCHES_PER_HOST = 2;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public SpringAgentLoopActions(ChatModel chatModel,
                                  ToolCallingManager toolCallingManager,
                                  List<ToolCallback> toolCallbacks,
                                  ChatMemory chatMemory,
                                  Duration streamTimeout) {
        this(chatModel, toolCallingManager, toolCallbacks, chatMemory, streamTimeout, null, null);
    }

    public SpringAgentLoopActions(ChatModel chatModel,
                                  ToolCallingManager toolCallingManager,
                                  List<ToolCallback> toolCallbacks,
                                  ChatMemory chatMemory,
                                  Duration streamTimeout,
                                  UrlLivenessChecker urlLivenessChecker) {
        this(chatModel, toolCallingManager, toolCallbacks, chatMemory, streamTimeout,
                urlLivenessChecker, null);
    }

    public SpringAgentLoopActions(ChatModel chatModel,
                                  ToolCallingManager toolCallingManager,
                                  List<ToolCallback> toolCallbacks,
                                  ChatMemory chatMemory,
                                  Duration streamTimeout,
                                  UrlLivenessChecker urlLivenessChecker,
                                  PriorityRequestExecutor priorityRequestExecutor) {
        this.chatModel = chatModel;
        this.toolCallingManager = toolCallingManager;
        this.toolCallbacks = toolCallbacks != null ? List.copyOf(toolCallbacks) : List.of();
        this.chatMemory = chatMemory;
        this.streamTimeout = Objects.requireNonNull(streamTimeout, "streamTimeout must not be null");
        this.urlLivenessChecker = urlLivenessChecker;
        this.priorityRequestExecutor = priorityRequestExecutor;
        this.rawToolCallParser = new RawToolCallParser(this.toolCallbacks);
        this.summaryModelInvoker = new SummaryModelInvoker(chatModel, priorityRequestExecutor);
    }

    @Override
    public void think(AgentContext ctx) {
        if (ctx.isCancelled()) {
            ctx.setErrorMessage("Agent run cancelled by user before think()");
            return;
        }
        ctx.emitEvent(AgentStreamEvent.thinking(ctx.getCurrentIteration()));
        try {
            List<Message> messages = getOrCreateHistory(ctx);

            if (messages.isEmpty()) {
                String systemPrompt = AgentPromptBuilder.buildSystemPrompt(ctx.getMetadata());
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

            log.info("Agent think: iteration={}, messages={}, tools={}",
                    ctx.getCurrentIteration(), messages.size(), toolCallbacks.size());
            if (log.isDebugEnabled()) {
                log.debug("Agent think: raw prompt messages:\n{}", messages.stream()
                        .map(m -> "[" + m.getMessageType() + "] " + m.getText())
                        .collect(Collectors.joining("\n---\n")));
            }

            ChatResponse response = streamAndAggregate(ctx, prompt);
            if (response == null) {
                ctx.setErrorMessage("LLM returned an empty stream");
                return;
            }
            ctx.putExtra(KEY_LAST_RESPONSE, response);
            if (log.isDebugEnabled()) {
                var debugOutput = response.getResult().getOutput();
                log.debug("Agent think: raw LLM response text:\n{}", debugOutput.getText());
                if (response.hasToolCalls()) {
                    log.debug("Agent think: raw tool calls: {}", debugOutput.getToolCalls());
                }
            }

            if (response.getMetadata().getModel() != null) {
                ctx.setModelName(response.getMetadata().getModel());
            }

            String reasoning = AgentTextSanitizer.extractReasoning(response);
            log.info("Agent think: reasoning extracted, length={}",
                    reasoning != null ? reasoning.length() : 0);
            if (reasoning != null && !reasoning.isBlank()) {
                ctx.emitEvent(AgentStreamEvent.thinking(reasoning, ctx.getCurrentIteration()));
            }

            response.getResult();

            var output = response.getResult().getOutput();

            if (response.hasToolCalls()) {
                var toolCalls = output.getToolCalls();
                var firstToolCall = toolCalls.getFirst();
                if (toolCalls.size() > 1) {
                    log.warn("Agent think: LLM returned {} tool calls, truncating to first (parallel not supported)",
                            toolCalls.size());
                    AssistantMessage singleMsg = AssistantMessage.builder()
                            .content(output.getText())
                            .toolCalls(List.of(firstToolCall))
                            .build();
                    ChatResponse existing = ctx.getExtra(KEY_LAST_RESPONSE);
                    Generation singleGen = existing.getResult() != null && existing.getResult().getMetadata() != null
                            ? new Generation(singleMsg, existing.getResult().getMetadata())
                            : new Generation(singleMsg);
                    ctx.putExtra(KEY_LAST_RESPONSE, new ChatResponse(List.of(singleGen), existing.getMetadata()));
                    messages.add(singleMsg);
                } else {
                    messages.add(output);
                }
                ctx.setCurrentThought("Calling tool: " + firstToolCall.name());
                ctx.setCurrentToolName(firstToolCall.name());
                ctx.setCurrentToolArguments(firstToolCall.arguments());
                log.info("Agent think: tool call detected — tool={}, args={}",
                        firstToolCall.name(), firstToolCall.arguments());
            } else {
                String rawText = AgentTextSanitizer.stripThinkTags(output.getText());
                RawToolCall rawToolCall = rawToolCallParser.tryParseRawToolCall(rawText);
                if (rawToolCall != null) {
                    ctx.setCurrentThought("Calling tool (fallback): " + rawToolCall.name());
                    ctx.setCurrentToolName(rawToolCall.name());
                    ctx.setCurrentToolArguments(rawToolCall.arguments());
                    ctx.putExtra(KEY_FALLBACK_TOOL_CALL, Boolean.TRUE);
                    log.info("Agent think: raw tool call detected via fallback — tool={}, args={}",
                            rawToolCall.name(), rawToolCall.arguments());
                    String cleanedText = AgentTextSanitizer.stripToolCallTags(rawText);
                    messages.add(new AssistantMessage(
                            cleanedText != null && !cleanedText.isEmpty()
                                    ? cleanedText
                                    : "Calling tool: " + rawToolCall.name()));
                } else {
                    String text = AgentTextSanitizer.stripToolCallTags(rawText);
                    if (text == null || text.isBlank()) {
                        ctx.markEmptyResponse();
                        log.warn("Agent think: LLM returned empty response (no tool call, no text), "
                                        + "iteration={}, emptyRetryCount={}",
                                ctx.getCurrentIteration(), ctx.getEmptyResponseRetryCount());
                    } else {
                        ctx.setCurrentThought("Final answer ready");
                        ctx.setCurrentTextResponse(text);
                        log.info("Agent think: final answer, length={}", text.length());
                        log.debug("Agent think: final answer text:\n{}", text);
                        messages.add(output);
                    }
                }
            }

        } catch (Exception e) {
            log.error("Agent think failed: {}", e.getMessage(), e);
            ctx.setErrorMessage("LLM call failed: " + e.getMessage());
        }
    }

    /**
     * Streams the LLM response, emits {@code PARTIAL_ANSWER} events for each filtered text
     * chunk, and builds an aggregated {@link ChatResponse} that preserves structured tool calls.
     *
     * <p>{@link StreamingAnswerFilter} strips LLM-output artifacts ({@code <think>} reasoning
     * and {@code <tool_call>} XML fallback) from the user-visible stream — these are
     * LLM implementation details, not user content, and must never leak through PARTIAL_ANSWER.
     * The full raw text is still accumulated for the final {@link ChatResponse} so tool-call
     * parsing and reasoning extraction downstream keep working.
     *
     * <p>Paragraph batching and message-length limits are a rendering concern (Telegram/REST/CLI)
     * owned by the respective consumers — this module streams as-is (filtered).
     *
     * <p>The last chunk's response metadata (model, usage) is preserved so downstream
     * model-name and usage tracking keeps working.
     */
    private ChatResponse streamAndAggregate(AgentContext ctx, Prompt prompt) {
        StreamingAnswerFilter filter = new StreamingAnswerFilter();
        int iteration = ctx.getCurrentIteration();

        StringBuilder fullText = new StringBuilder();
        // Separate accumulator from `fullText` (which `doOnNext` also feeds for tool-call
        // aggregation). This one normalizes per-chunk text reaching StreamingAnswerFilter:
        // providers that emit cumulative snapshots (full text so far, each chunk) would
        // otherwise concatenate into `HHeHelHell…` downstream, and — worse — re-open the
        // filter's <think>/<tool_call> state machine on every snapshot, swallowing content.
        StringBuilder snapshotAcc = new StringBuilder();
        List<AssistantMessage.ToolCall> collectedToolCalls = new ArrayList<>();
        Set<String> seenToolCallIds = new LinkedHashSet<>();
        AtomicReference<ChatResponse> lastChunk = new AtomicReference<>();

        try {
            Flux<ChatResponse> stream = chatModel.stream(prompt);
            if (stream == null) {
                throw new IllegalStateException("chatModel.stream returned null flux");
            }
            stream
                    .takeWhile(chunk -> !ctx.isCancelled())
                    .doOnNext(chunk -> {
                        lastChunk.set(chunk);
                        if (chunk.getResult() != null && chunk.getResult().getOutput() != null) {
                            AssistantMessage output = chunk.getResult().getOutput();
                            if (output.getText() != null) {
                                AgentTextSanitizer.appendDelta(fullText, output.getText());
                            }
                            if (output.getToolCalls() != null && !output.getToolCalls().isEmpty()) {
                                for (AssistantMessage.ToolCall call : output.getToolCalls()) {
                                    String dedupKey = call.id() != null && !call.id().isBlank()
                                            ? call.id()
                                            : call.name() + "|" + call.arguments();
                                    if (seenToolCallIds.add(dedupKey)) {
                                        collectedToolCalls.add(call);
                                    }
                                }
                            }
                        }
                    })
                    .map(AIUtils::extractText)
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .map(text -> AgentTextSanitizer.computeDelta(snapshotAcc, text))
                    .filter(s -> !s.isEmpty())
                    .map(filter::feed)
                    .filter(s -> !s.isEmpty())
                    .concatWith(Flux.defer(() -> {
                        String tail = filter.flush();
                        return tail.isEmpty() ? Flux.empty() : Flux.just(tail);
                    }))
                    .doOnNext(text -> ctx.emitEvent(AgentStreamEvent.partialAnswer(text, iteration)))
                    .blockLast(streamTimeout);
        } catch (Exception e) {
            if (lastChunk.get() == null) {
                log.warn("Agent think: stream path unavailable (timeout={}), falling back to call(): {}",
                        streamTimeout, e.getMessage());
                ctx.emitEvent(AgentStreamEvent.error(
                        "Streaming unavailable, switched to non-streaming mode", iteration));
                return callWithPriority(ctx, prompt);
            }
            log.warn("Agent think: stream failed mid-flight after partial chunks, surfacing partial response: {}",
                    e.getMessage());
            ctx.emitEvent(AgentStreamEvent.error(
                    "Stream interrupted: " + e.getMessage(), iteration));
        }

        if (ctx.isCancelled()) {
            ctx.setErrorMessage("Agent run cancelled by user during streaming");
            log.info("Agent think: stream aborted because context was cancelled");
            return null;
        }

        ChatResponse last = lastChunk.get();
        if (last == null) {
            return null;
        }
        AssistantMessage finalMessage = collectedToolCalls.isEmpty()
                ? new AssistantMessage(fullText.toString())
                : AssistantMessage.builder()
                        .content(fullText.toString())
                        .toolCalls(collectedToolCalls)
                        .build();
        Generation finalGeneration = last.getResult() != null && last.getResult().getMetadata() != null
                ? new Generation(finalMessage, last.getResult().getMetadata())
                : new Generation(finalMessage);
        return new ChatResponse(List.of(finalGeneration), last.getMetadata());
    }

    /**
     * Delegates {@code chatModel.call(prompt)} through {@link PriorityRequestExecutor} so that
     * non-streaming fallback calls respect the same concurrency limits as all other AI calls.
     * When no executor is configured (e.g. in tests using the two-argument constructor),
     * the call is made directly.
     */
    private ChatResponse callWithPriority(AgentContext ctx, Prompt prompt) {
        if (priorityRequestExecutor == null) {
            return chatModel.call(prompt);
        }
        Long userId = SummaryModelInvoker.resolveUserId(ctx.getMetadata());
        try {
            return priorityRequestExecutor.executeRequest(userId, () -> chatModel.call(prompt));
        } catch (Exception e) {
            log.warn("Agent think: fallback call via PriorityRequestExecutor failed: {}", e.getMessage());
            throw e instanceof RuntimeException re ? re : new RuntimeException(e);
        }
    }

    @Override
    public void executeTool(AgentContext ctx) {
        if (ctx.isCancelled()) {
            ctx.setErrorMessage("Agent run cancelled by user before executeTool()");
            return;
        }
        ctx.emitEvent(AgentStreamEvent.toolCall(
                ctx.getCurrentToolName(), ctx.getCurrentToolArguments(), ctx.getCurrentIteration()));
        try {
            if (Boolean.TRUE.equals(ctx.getExtra(KEY_FALLBACK_TOOL_CALL))) {
                executeFallbackToolCall(ctx);
                return;
            }

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

            log.info("Agent executeTool: completed, observation length={}",
                    observation != null ? observation.length() : 0);
            log.debug("Agent executeTool: raw observation:\n{}", observation);

        } catch (Exception e) {
            log.error("Agent executeTool failed: tool={}, error={}",
                    ctx.getCurrentToolName(), e.getMessage(), e);
            ctx.setToolResult(AgentToolResult.failure(ctx.getCurrentToolName(), e.getMessage()));
        }
    }

    @Override
    public void observe(AgentContext ctx) {
        if (ctx.isCancelled()) {
            ctx.setErrorMessage("Agent run cancelled by user before observe()");
            return;
        }
        Classification classification = ToolObservationClassifier.classify(ctx.getToolResult());
        ctx.emitEvent(AgentStreamEvent.observation(
                classification.streamContent(), classification.toolError(), ctx.getCurrentIteration()));

        ctx.recordStep(new AgentStepResult(
                ctx.getCurrentIteration(),
                ctx.getCurrentThought(),
                ctx.getCurrentToolName(),
                ctx.getCurrentToolArguments(),
                classification.observation(),
                Instant.now()
        ));

        ctx.incrementIteration();
        ctx.resetIterationState();

        log.info("Agent observe: iteration={} recorded, moving to next think cycle",
                ctx.getCurrentIteration());
    }

    @Override
    public void answer(AgentContext ctx) {
        if (ctx.isCancelled()) {
            // Set error only — FSM's ANSWERING→FAILED guard on hasError routes the
            // terminal state to FAILED (not COMPLETED), so AgentResult.isSuccess()
            // returns false. handleError() runs cleanup on the failure branch; no
            // finalAnswer is set because the user no longer wants the result.
            ctx.setErrorMessage("Agent run cancelled by user before answer()");
            return;
        }
        String text = ctx.getCurrentTextResponse();
        String sanitized = sanitizeDeadUrls(ctx, text);
        ctx.setFinalAnswer(sanitized);
        saveConversationHistory(ctx);
        cleanup(ctx);
        log.info("Agent answer: final answer set, length={}",
                ctx.getFinalAnswer() != null ? ctx.getFinalAnswer().length() : 0);
        log.debug("Agent answer: final answer text:\n{}", ctx.getFinalAnswer());
    }

    /**
     * Passes the final answer text through {@link UrlLivenessChecker#stripDeadLinks(String, String)}
     * when the checker bean is available. The language-code is pulled from
     * {@link AICommand#LANGUAGE_CODE_FIELD} in the agent metadata so that dead-link markers
     * localise to the same language as the rest of the answer. Failures in the checker never
     * block answer delivery — on any exception the original text is returned unchanged.
     */
    private String sanitizeDeadUrls(AgentContext ctx, String text) {
        if (urlLivenessChecker == null || text == null || text.isBlank()) {
            return text;
        }
        String languageCode = ctx.getMetadata() != null
                ? ctx.getMetadata().get(AICommand.LANGUAGE_CODE_FIELD) : null;
        try {
            return urlLivenessChecker.stripDeadLinks(text, languageCode);
        } catch (Exception e) {
            log.warn("Agent answer: url liveness sanitization failed, keeping original text: {}",
                    e.getMessage());
            return text;
        }
    }

    @Override
    public void handleMaxIterations(AgentContext ctx) {
        if (ctx.isCancelled()) {
            ctx.setErrorMessage("Agent run cancelled by user before handleMaxIterations()");
            ctx.setFinalAnswer(summaryModelInvoker.buildFallbackSummary(ctx));
            cleanup(ctx);
            return;
        }
        String summary;
        try {
            summary = summaryModelInvoker.callSummaryModelWithoutTools(ctx);
        } catch (Exception e) {
            log.warn("Agent handleMaxIterations: summary LLM call failed, falling back to step-history digest", e);
            summary = summaryModelInvoker.buildFallbackSummary(ctx);
        }
        ctx.setFinalAnswer(summary);
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
     * Single-shot recovery when the LLM returns an empty response. Appends a
     * nudge SystemMessage to the conversation history, increments the retry
     * counter, clears the empty flag, and re-invokes {@link #think(AgentContext)}.
     *
     * <p>The retry budget is enforced by {@link AgentContext#canRetryEmptyResponse()}
     * in the FSM guard — this method itself is unconditional.
     */
    @Override
    public void retryEmptyResponse(AgentContext ctx) {
        List<Message> messages = getOrCreateHistory(ctx);
        messages.add(new SystemMessage(
                "Your previous response was empty. Reply with either a tool call "
                        + "or a final text answer now. Do not return an empty message."));
        ctx.incrementEmptyResponseRetryCount();
        ctx.clearEmptyResponse();
        log.info("Agent retryEmptyResponse: nudging LLM, iteration={}, retryCount={}",
                ctx.getCurrentIteration(), ctx.getEmptyResponseRetryCount());
        think(ctx);
    }

    /**
     * Filters the full tool callback list by {@code ctx.getEnabledTools()}.
     * If enabledTools is empty or null, all tools are available (default behavior).
     */
    List<ToolCallback> resolveEffectiveTools(AgentContext ctx) {
        Set<String> enabled = ctx.getEnabledTools();
        List<ToolCallback> resolved;
        if (enabled == null || enabled.isEmpty()) {
            resolved = toolCallbacks;
        } else {
            List<ToolCallback> filtered = toolCallbacks.stream()
                    .filter(cb -> enabled.contains(cb.getToolDefinition().name()))
                    .toList();
            if (filtered.isEmpty()) {
                log.warn("Agent think: enabledTools={} matched no registered tools, using all", enabled);
                resolved = toolCallbacks;
            } else {
                resolved = filtered;
            }
        }
        return resolved.stream()
                .map(callback -> guardFetchUrlCallback(ctx, callback))
                .toList();
    }

    private ToolCallback guardFetchUrlCallback(AgentContext ctx, ToolCallback callback) {
        if (!TOOL_FETCH_URL.equals(callback.getToolDefinition().name())) {
            return callback;
        }
        return new GuardedFetchUrlToolCallback(callback, ctx);
    }

    private static final class GuardedFetchUrlToolCallback implements ToolCallback {
        private final ToolCallback delegate;
        private final AgentContext ctx;

        private GuardedFetchUrlToolCallback(ToolCallback delegate, AgentContext ctx) {
            this.delegate = delegate;
            this.ctx = ctx;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return delegate.getToolDefinition();
        }

        @Override
        public ToolMetadata getToolMetadata() {
            return delegate.getToolMetadata();
        }

        @Override
        public String call(String toolInput) {
            return callGuarded(toolInput, () -> delegate.call(toolInput));
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            return callGuarded(toolInput, () -> delegate.call(toolInput, toolContext));
        }

        private String callGuarded(String toolInput, Supplier<String> delegateCall) {
            String url = extractFetchUrl(toolInput);
            String host = hostOf(url);
            String shortCircuit = shortCircuitFetchMessage(ctx, url, host);
            if (shortCircuit != null) {
                return shortCircuit;
            }

            String result = delegateCall.get();
            recordFetchFailure(ctx, url, host, result);
            return result;
        }
    }

    private static String extractFetchUrl(String toolInput) {
        if (toolInput == null || toolInput.isBlank()) {
            return null;
        }
        try {
            JsonNode node = OBJECT_MAPPER.readTree(toolInput);
            JsonNode urlNode = node.get("url");
            if (urlNode == null || urlNode.isNull()) {
                return null;
            }
            String url = urlNode.asText();
            return url == null || url.isBlank() ? null : url.trim();
        } catch (Exception e) {
            log.debug("Agent fetch_url guard: could not parse tool input as JSON: {}", e.getMessage());
            return null;
        }
    }

    private static String shortCircuitFetchMessage(AgentContext ctx, String url, String host) {
        if (url != null && failedFetchUrls(ctx).contains(url)) {
            log.info("Agent fetch_url guard: short-circuiting previously failed url={}", url);
            return "Error: previously_failed_url - " + url
                    + " failed earlier in this run; use another source or answer from search snippets";
        }
        if (host != null && failedFetchHosts(ctx).getOrDefault(host, 0) >= MAX_FAILED_FETCHES_PER_HOST) {
            log.info("Agent fetch_url guard: short-circuiting host={} after repeated failures", host);
            return "Error: host_unreadable - " + host
                    + " failed repeatedly in this run; use another source or answer from search snippets";
        }
        return null;
    }

    private static void recordFetchFailure(AgentContext ctx, String url, String host, String result) {
        String failure = ToolObservationClassifier.normalizeStringToolResult(result);
        if (!ToolObservationClassifier.isTextualToolFailure(failure)) {
            return;
        }
        if (url != null) {
            failedFetchUrls(ctx).add(url);
        }
        if (host != null && shouldCountHostFailure(failure)) {
            Map<String, Integer> hosts = failedFetchHosts(ctx);
            hosts.put(host, hosts.getOrDefault(host, 0) + 1);
        }
    }

    private static boolean shouldCountHostFailure(String failure) {
        if (failure.startsWith("Error: " + WebTools.REASON_TIMEOUT)
                || failure.startsWith("HTTP error 408")
                || failure.startsWith("HTTP error 429")
                || failure.matches("^HTTP error 5\\d\\d\\b.*")) {
            return false;
        }
        return true;
    }

    private static String hostOf(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            String host = URI.create(url).getHost();
            return host == null || host.isBlank() ? null : host.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Set<String> failedFetchUrls(AgentContext ctx) {
        Set<String> urls = ctx.getExtra(KEY_FAILED_FETCH_URLS);
        if (urls == null) {
            urls = new LinkedHashSet<>();
            ctx.putExtra(KEY_FAILED_FETCH_URLS, urls);
        }
        return urls;
    }

    private static Map<String, Integer> failedFetchHosts(AgentContext ctx) {
        Map<String, Integer> hosts = ctx.getExtra(KEY_FAILED_FETCH_HOSTS);
        if (hosts == null) {
            hosts = new HashMap<>();
            ctx.putExtra(KEY_FAILED_FETCH_HOSTS, hosts);
        }
        return hosts;
    }

    /**
     * Extracts the tool result text from the conversation history returned by
     * {@link ToolCallingManager#executeToolCalls}.
     */
    private String extractToolObservation(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return "(no tool output)";
        }
        Message last = messages.getLast();
        if (last instanceof ToolResponseMessage trm) {
            String joined = trm.getResponses().stream()
                    .map(ToolResponseMessage.ToolResponse::responseData)
                    .collect(Collectors.joining("\n"));
            if (!joined.isBlank()) {
                return joined;
            }
        }
        String text = last.getText();
        return (text != null && !text.isBlank()) ? text : "(no tool output)";
    }

    /**
     * Executes a tool call that was parsed from raw text (fallback path).
     * Directly invokes the matching {@link ToolCallback} instead of going through
     * {@link ToolCallingManager}, since there is no structured tool call in the
     * {@link ChatResponse} for the manager to process.
     */
    private void executeFallbackToolCall(AgentContext ctx) {
        String toolName = ctx.getCurrentToolName();
        String toolArgs = ctx.getCurrentToolArguments();

        ToolCallback callback = toolCallbacks.stream()
                .filter(cb -> cb.getToolDefinition().name().equals(toolName))
                .findFirst()
                .orElse(null);

        if (callback == null) {
            ctx.setErrorMessage("Fallback tool not found: " + toolName);
            return;
        }

        log.info("Agent executeTool (fallback): tool={}, args={}", toolName, toolArgs);

        String result = guardFetchUrlCallback(ctx, callback).call(toolArgs);
        ctx.setToolResult(AgentToolResult.success(toolName, result));

        List<Message> messages = getOrCreateHistory(ctx);
        messages.add(new UserMessage("[Tool result: " + toolName + "]\n" + result));

        ctx.removeExtra(KEY_FALLBACK_TOOL_CALL);

        log.info("Agent executeTool (fallback): completed, result length={}",
                result != null ? result.length() : 0);
        log.debug("Agent executeTool (fallback): raw result:\n{}", result);
    }

    private void cleanup(AgentContext ctx) {
        ctx.removeExtra(KEY_CONVERSATION_HISTORY);
        ctx.removeExtra(KEY_LAST_PROMPT);
        ctx.removeExtra(KEY_LAST_RESPONSE);
    }

    /**
     * Loads prior conversation turns from {@link ChatMemory} and appends them
     * between the system prompt and the current user message. Skips any
     * {@link SystemMessage} entries from memory (e.g. summaries) to avoid
     * conflicting with the agent system prompt — the summary content is
     * prepended to the first system message instead.
     */
    private void loadConversationHistory(AgentContext ctx, List<Message> messages) {
        if (chatMemory == null || ctx.getConversationId() == null) {
            return;
        }
        try {
            List<Message> history = chatMemory.get(ctx.getConversationId());
            if (history == null || history.isEmpty()) {
                return;
            }
            for (Message msg : history) {
                if (msg.getMessageType() == MessageType.SYSTEM) {
                    if (!messages.isEmpty() && messages.getFirst() instanceof SystemMessage existing) {
                        messages.set(0, new SystemMessage(existing.getText() + "\n\n" + msg.getText()));
                    }
                } else {
                    messages.add(msg);
                }
            }
            log.info("Agent think: loaded {} history messages from ChatMemory", history.size());
        } catch (Exception e) {
            log.warn("Agent think: failed to load conversation history: {}", e.getMessage());
        }
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
            log.info("Agent answer: saved user+assistant messages to ChatMemory");
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
}
