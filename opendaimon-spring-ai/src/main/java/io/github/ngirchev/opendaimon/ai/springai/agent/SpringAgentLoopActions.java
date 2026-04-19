package io.github.ngirchev.opendaimon.ai.springai.agent;

import io.github.ngirchev.opendaimon.common.ai.command.AICommand;
import io.github.ngirchev.opendaimon.common.agent.AgentContext;
import io.github.ngirchev.opendaimon.common.agent.AgentLoopActions;
import io.github.ngirchev.opendaimon.common.agent.AgentStepResult;
import io.github.ngirchev.opendaimon.common.agent.AgentStreamEvent;
import io.github.ngirchev.opendaimon.common.agent.AgentToolResult;
import io.github.ngirchev.opendaimon.ai.springai.tool.UrlLivenessChecker;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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

    private final ChatModel chatModel;
    private final ToolCallingManager toolCallingManager;
    private final List<ToolCallback> toolCallbacks;
    private final ChatMemory chatMemory;
    private final Duration streamTimeout;
    /** Optional — when set, final-answer text is passed through to strip dead URLs. */
    private final UrlLivenessChecker urlLivenessChecker;

    private static final String KEY_CONVERSATION_HISTORY = "spring.conversationHistory";
    private static final String KEY_LAST_PROMPT = "spring.lastPrompt";
    private static final String KEY_LAST_RESPONSE = "spring.lastResponse";

    /** Matches complete {@code <tool_call>...</tool_call>} blocks including content. */
    private static final Pattern TOOL_CALL_BLOCK_PATTERN =
            Pattern.compile("<tool_call>.*?</tool_call>", Pattern.DOTALL);

    /** Matches orphaned {@code <tool_call>} tag without closing — consumes to end of string. */
    private static final Pattern TOOL_CALL_OPEN_PATTERN =
            Pattern.compile("<tool_call>.*", Pattern.DOTALL);

    /** Matches orphaned {@code </tool_call>} closing tag. */
    private static final Pattern TOOL_CALL_CLOSE_PATTERN =
            Pattern.compile("</tool_call>");

    /** Matches loose inner tags: {@code <name>}, {@code <arg_key>}, {@code <arg_value>} with content. */
    private static final Pattern TOOL_CALL_INNER_TAGS_PATTERN =
            Pattern.compile("<(name|arg_key|arg_value)>.*?</\\1>", Pattern.DOTALL);

    /** Matches unclosed inner tags: e.g. {@code <arg_value>content} without a closing tag. */
    private static final Pattern TOOL_CALL_UNCLOSED_INNER_TAG_PATTERN =
            Pattern.compile("<(name|arg_key|arg_value)>[^\n]*");

    /** Matches a bare tool-like name on its own line (e.g. {@code http_get}, {@code web_search}). */
    private static final Pattern BARE_TOOL_NAME_PATTERN =
            Pattern.compile("(?m)^\\s*\\w+_\\w+\\s*$");

    private static final String KEY_FALLBACK_TOOL_CALL = "spring.fallbackToolCall";

    /** Matches {@code <name>toolName</name>} inside raw tool call markup. */
    private static final Pattern NAME_TAG_PATTERN =
            Pattern.compile("<name>(\\w+)</name>");

    /**
     * Matches {@code <tool_name>toolName</tool_name>} — the Ollama/Qwen variant. Kept as a
     * separate pattern (not combined with {@link #NAME_TAG_PATTERN}) because some models
     * emit both in the same payload and we want a deterministic priority: {@code <name>}
     * wins, {@code <tool_name>} is the fallback.
     */
    private static final Pattern TOOL_NAME_TAG_PATTERN =
            Pattern.compile("<tool_name>(\\w+)</tool_name>");

    /** Matches {@code <arg_key>key</arg_key>...<arg_value>value</arg_value>} pairs. */
    private static final Pattern ARG_PAIR_PATTERN =
            Pattern.compile("<arg_key>(.*?)</arg_key>\\s*<arg_value>(.*?)</arg_value>", Pattern.DOTALL);

    /** Parsed raw tool call from text output (fallback for models without structured function calling). */
    record RawToolCall(String name, String arguments) {}

    public SpringAgentLoopActions(ChatModel chatModel,
                                  ToolCallingManager toolCallingManager,
                                  List<ToolCallback> toolCallbacks,
                                  ChatMemory chatMemory,
                                  Duration streamTimeout) {
        this(chatModel, toolCallingManager, toolCallbacks, chatMemory, streamTimeout, null);
    }

    public SpringAgentLoopActions(ChatModel chatModel,
                                  ToolCallingManager toolCallingManager,
                                  List<ToolCallback> toolCallbacks,
                                  ChatMemory chatMemory,
                                  Duration streamTimeout,
                                  UrlLivenessChecker urlLivenessChecker) {
        this.chatModel = chatModel;
        this.toolCallingManager = toolCallingManager;
        this.toolCallbacks = toolCallbacks != null ? List.copyOf(toolCallbacks) : List.of();
        this.chatMemory = chatMemory;
        this.streamTimeout = Objects.requireNonNull(streamTimeout, "streamTimeout must not be null");
        this.urlLivenessChecker = urlLivenessChecker;
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
                String systemPrompt = AgentPromptBuilder.buildSystemPrompt();
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

            // Emit reasoning content if available from provider (OpenRouter/Anthropic/Ollama)
            String reasoning = extractReasoning(response);
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
                String rawText = stripThinkTags(output.getText());
                RawToolCall rawToolCall = tryParseRawToolCall(rawText);
                if (rawToolCall != null) {
                    ctx.setCurrentThought("Calling tool (fallback): " + rawToolCall.name());
                    ctx.setCurrentToolName(rawToolCall.name());
                    ctx.setCurrentToolArguments(rawToolCall.arguments());
                    ctx.putExtra(KEY_FALLBACK_TOOL_CALL, Boolean.TRUE);
                    log.info("Agent think: raw tool call detected via fallback — tool={}, args={}",
                            rawToolCall.name(), rawToolCall.arguments());
                    // Add cleaned message to history — raw XML confuses the model on next iterations
                    String cleanedText = stripToolCallTags(rawText);
                    messages.add(new AssistantMessage(
                            cleanedText != null && !cleanedText.isEmpty()
                                    ? cleanedText
                                    : "Calling tool: " + rawToolCall.name()));
                } else {
                    String text = stripToolCallTags(rawText);
                    ctx.setCurrentThought("Final answer ready");
                    ctx.setCurrentTextResponse(text);
                    log.info("Agent think: final answer, length={}",
                            text != null ? text.length() : 0);
                    log.debug("Agent think: final answer text:\n{}", text);
                    messages.add(output);
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
                                String text = output.getText();
                                String accumulated = fullText.toString();
                                fullText.append(normalizeDelta(accumulated, text));
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
                return chatModel.call(prompt);
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

    @Override
    public void executeTool(AgentContext ctx) {
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
        AgentToolResult toolResult = ctx.getToolResult();
        boolean toolError = toolResult != null && !toolResult.success();
        String streamContent;
        String observation;
        if (toolResult == null) {
            streamContent = null;
            observation = "No result";
        } else if (toolResult.success()) {
            streamContent = toolResult.result();
            observation = toolResult.result();
            // Several built-in @Tool implementations (HttpApiTool, WebTools) return a
            // non-exceptional String on HTTP failure — e.g. "HTTP error 403 FORBIDDEN: …" or
            // "Error: …". Spring AI treats that as a successful tool execution, so
            // toolResult.success() stays true and the Telegram layer would mis-render it as
            // "📋 Tool result received". Detect those textual failure markers here and
            // promote the observation to an error so the UI shows "⚠️ Tool failed: …".
            if (streamContent != null) {
                String trimmed = streamContent.trim();
                // Spring AI serializes String tool return values as JSON-quoted strings
                // (e.g. "HTTP error 200 OK" → "\"HTTP error 200 OK\""). Unwrap the outer
                // quotes before checking the textual-failure prefix so the heuristic works
                // regardless of whether the upstream serializer added them.
                if (trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length() >= 2) {
                    trimmed = trimmed.substring(1, trimmed.length() - 1);
                    streamContent = trimmed;
                }
                if (trimmed.startsWith("HTTP error ") || trimmed.startsWith("Error: ")) {
                    toolError = true;
                    streamContent = summarizeToolError(trimmed);
                }
            }
        } else {
            streamContent = toolResult.error();
            observation = "Error: " + toolResult.error();
        }
        ctx.emitEvent(AgentStreamEvent.observation(streamContent, toolError, ctx.getCurrentIteration()));

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

        log.info("Agent observe: iteration={} recorded, moving to next think cycle",
                ctx.getCurrentIteration());
    }

    @Override
    public void answer(AgentContext ctx) {
        String text = ctx.getCurrentTextResponse();
        String sanitized = sanitizeDeadUrls(text);
        ctx.setFinalAnswer(sanitized);
        saveConversationHistory(ctx);
        cleanup(ctx);
        log.info("Agent answer: final answer set, length={}",
                ctx.getFinalAnswer() != null ? ctx.getFinalAnswer().length() : 0);
        log.debug("Agent answer: final answer text:\n{}", ctx.getFinalAnswer());
    }

    /**
     * Passes the final answer text through {@link UrlLivenessChecker#stripDeadLinks(String)}
     * when the checker bean is available. Defends against LLM-hallucinated URLs in the
     * final user-visible answer. Failures in the checker never block answer delivery —
     * on any exception the original text is returned unchanged.
     */
    private String sanitizeDeadUrls(String text) {
        if (urlLivenessChecker == null || text == null || text.isBlank()) {
            return text;
        }
        try {
            return urlLivenessChecker.stripDeadLinks(text);
        } catch (Exception e) {
            log.warn("Agent answer: url liveness sanitization failed, keeping original text: {}",
                    e.getMessage());
            return text;
        }
    }

    @Override
    public void handleMaxIterations(AgentContext ctx) {
        String summary;
        try {
            summary = callSummaryModelWithoutTools(ctx);
        } catch (Exception e) {
            log.warn("Agent handleMaxIterations: summary LLM call failed, falling back to step-history digest", e);
            summary = buildFallbackSummary(ctx);
        }
        ctx.setFinalAnswer(summary);
        cleanup(ctx);
        log.warn("Agent handleMaxIterations: {} iterations exhausted", ctx.getMaxIterations());
    }

    /**
     * Extracts a short, UI-friendly error line from a textual tool failure like
     * {@code "HTTP error 403 FORBIDDEN: <html …>"} or {@code "Error: connection refused"}.
     * Keeps only the head of the first line so the Telegram {@code ⚠️ Tool failed: …}
     * marker stays compact (large CloudFlare challenge pages are ~7 kB otherwise).
     */
    private static String summarizeToolError(String raw) {
        int maxLen = 200;
        int newline = raw.indexOf('\n');
        String firstLine = newline >= 0 ? raw.substring(0, newline) : raw;
        if (firstLine.length() > maxLen) {
            return firstLine.substring(0, maxLen) + "…";
        }
        return firstLine;
    }

    /**
     * Asks the chat model to summarize the step history and answer the user's original
     * question, with tool execution explicitly disabled and no tool callbacks registered.
     * The model is forced to produce a direct answer from whatever observations already
     * exist — no further tool calls are possible.
     *
     * <p>The system prompt includes a language instruction derived from the
     * {@code languageCode} field in {@code ctx.getMetadata()} (see {@link AICommand#LANGUAGE_CODE_FIELD}).
     * The prompt also explicitly forbids meta-prose and introductory phrases such as
     * "Based on", "Answer:", "According to", or "The searches showed".
     */
    private String callSummaryModelWithoutTools(AgentContext ctx) {
        List<Message> messages = new ArrayList<>();
        String langInstruction = resolveLanguageInstruction(ctx.getMetadata());
        String systemPrompt = "You have reached the iteration limit. "
                + "Based on the step history, give a direct answer to the user's original question. "
                + "Do not call any tools. "
                + "Do not explain the research process. "
                + "Do not use introductory phrases like 'Based on', 'Answer:', 'According to', "
                + "'The searches showed', or similar. "
                + "If the available information is insufficient, say so in one sentence."
                + (langInstruction.isEmpty() ? "" : "\n" + langInstruction);
        messages.add(new SystemMessage(systemPrompt));
        messages.add(new UserMessage(ctx.getTask() + "\n\nContext so far:\n" + flattenStepHistory(ctx)));

        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .internalToolExecutionEnabled(false)
                .toolCallbacks(List.of())
                .build();

        ChatResponse response = chatModel.call(new Prompt(messages, options));
        String raw = response.getResult() != null && response.getResult().getOutput() != null
                ? response.getResult().getOutput().getText()
                : null;
        String clean = stripToolCallTags(stripThinkTags(raw));
        if (clean == null || clean.isBlank()) {
            throw new IllegalStateException("Summary LLM returned empty content");
        }
        return clean;
    }

    /** Flattens step history into a plain-text block for the summary prompt. */
    private String flattenStepHistory(AgentContext ctx) {
        var sb = new StringBuilder();
        for (AgentStepResult step : ctx.getStepHistory()) {
            if (step.observation() != null) {
                String obs = stripToolCallTags(step.observation());
                sb.append("- ").append(step.action()).append(": ").append(
                        obs != null && obs.length() > 500 ? obs.substring(0, 500) + "..." : (obs != null ? obs : "")
                ).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * Resolves a language instruction string from agent metadata.
     * Returns an empty string if no {@code languageCode} is set in metadata.
     */
    private static String resolveLanguageInstruction(Map<String, String> metadata) {
        if (metadata == null) return "";
        String code = metadata.get(AICommand.LANGUAGE_CODE_FIELD);
        if (code == null || code.isBlank()) return "";
        String name = switch (code.toLowerCase()) {
            case "ru" -> "Russian";
            case "en" -> "English";
            case "de" -> "German";
            case "fr" -> "French";
            case "es" -> "Spanish";
            case "zh" -> "Chinese";
            default -> code;
        };
        return "Respond in " + name + " (" + code + ").";
    }

    /**
     * Old StringBuilder digest — used only when the summary LLM call throws. Keeps the
     * user from receiving an empty final answer on MAX_ITERATIONS.
     */
    private String buildFallbackSummary(AgentContext ctx) {
        var sb = new StringBuilder();
        sb.append("I reached the maximum number of iterations (").append(ctx.getMaxIterations()).append("). ");
        sb.append("Here is what I found so far:\n\n");
        for (AgentStepResult step : ctx.getStepHistory()) {
            if (step.observation() != null) {
                String obs = stripToolCallTags(step.observation());
                sb.append("- ").append(step.action()).append(": ").append(
                        obs != null && obs.length() > 200 ? obs.substring(0, 200) + "..." : (obs != null ? obs : "")
                ).append('\n');
            }
        }
        return sb.toString();
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
     * Attempts to parse a tool call from raw XML tags in the text output.
     *
     * <p>Some models (especially via Ollama/OpenRouter) emit tool calls as XML tags
     * in text instead of using the structured function calling API. This method
     * detects such patterns and parses them into a usable tool call.
     *
     * <p>Requirements for a valid parse:
     * <ul>
     *   <li>At least one {@code <arg_key>/<arg_value>} pair must be present</li>
     *   <li>Tool name found via {@code <name>} tag or by matching registered tool names</li>
     *   <li>Tool name must correspond to a registered tool callback</li>
     * </ul>
     *
     * @param text raw text output from the LLM (after think-tag stripping)
     * @return parsed tool call, or null if no valid tool call pattern found
     */
    RawToolCall tryParseRawToolCall(String text) {
        if (text == null) {
            return null;
        }

        Matcher firstArgCheck = ARG_PAIR_PATTERN.matcher(text);
        if (!firstArgCheck.find()) {
            return null;
        }

        String toolName = null;
        Matcher nameMatcher = NAME_TAG_PATTERN.matcher(text);
        if (nameMatcher.find()) {
            toolName = nameMatcher.group(1).trim();
        }

        if (toolName == null) {
            Matcher toolNameMatcher = TOOL_NAME_TAG_PATTERN.matcher(text);
            if (toolNameMatcher.find()) {
                toolName = toolNameMatcher.group(1).trim();
            }
        }

        if (toolName == null) {
            for (ToolCallback cb : toolCallbacks) {
                String name = cb.getToolDefinition().name();
                if (text.contains(name)) {
                    toolName = name;
                    break;
                }
            }
        }

        if (toolName == null) {
            return null;
        }

        String resolvedName = toolName;
        boolean registered = toolCallbacks.stream()
                .anyMatch(cb -> cb.getToolDefinition().name().equals(resolvedName));
        if (!registered) {
            return null;
        }

        Matcher argMatcher = ARG_PAIR_PATTERN.matcher(text);
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        while (argMatcher.find()) {
            if (!first) {
                json.append(",");
            }
            json.append("\"").append(escapeJson(argMatcher.group(1).trim())).append("\":");
            json.append("\"").append(escapeJson(argMatcher.group(2).trim())).append("\"");
            first = false;
        }
        json.append("}");

        log.info("Agent think: parsed raw tool call — tool={}, args={}", toolName, json);
        return new RawToolCall(toolName, json.toString());
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

        String result = callback.call(toolArgs);
        ctx.setToolResult(AgentToolResult.success(toolName, result));

        List<Message> messages = getOrCreateHistory(ctx);
        messages.add(new UserMessage("[Tool result: " + toolName + "]\n" + result));

        ctx.removeExtra(KEY_FALLBACK_TOOL_CALL);

        log.info("Agent executeTool (fallback): completed, result length={}",
                result != null ? result.length() : 0);
        log.debug("Agent executeTool (fallback): raw result:\n{}", result);
    }

    /**
     * Escapes special characters for JSON string values.
     */
    static String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private void cleanup(AgentContext ctx) {
        ctx.removeExtra(KEY_CONVERSATION_HISTORY);
        ctx.removeExtra(KEY_LAST_PROMPT);
        ctx.removeExtra(KEY_LAST_RESPONSE);
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
                log.info("Agent extractReasoning: found 'thinking' metadata, length={}", text.length());
                return text;
            }
            // 2. Check "reasoningContent" metadata key (OpenRouter/Anthropic)
            Object reasoning = metadata.get("reasoningContent");
            if (reasoning instanceof String text && !text.isBlank()) {
                log.info("Agent extractReasoning: found 'reasoningContent' metadata, length={}", text.length());
                return text;
            }
            // 3. Fallback: check <think> tags in text (older Ollama or custom models)
            var output = response.getResult().getOutput();
            if (output != null && output.getText() != null) {
                String rawText = output.getText();
                boolean hasThinkTags = rawText.contains("<think>");
                if (hasThinkTags) {
                    log.info("Agent extractReasoning: found <think> tags, textLength={}", rawText.length());
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
        if (start < 0) {
            return text;
        }
        int end = text.indexOf("</think>");
        if (end < 0 || end <= start) {
            return text.substring(0, start).trim();
        }
        return (text.substring(0, start) + text.substring(end + "</think>".length())).trim();
    }

    /**
     * Returns the delta to append to {@code accumulated} when a streaming chunk arrives.
     * Some providers (Ollama) send cumulative snapshots rather than true deltas: each
     * chunk repeats all previous content plus the new suffix. When the new chunk starts
     * with the entire accumulated text, only the suffix beyond it is the new content.
     */
    static String normalizeDelta(String accumulated, String chunk) {
        if (!accumulated.isEmpty() && chunk.startsWith(accumulated)) {
            return chunk.substring(accumulated.length());
        }
        return chunk;
    }

    /**
     * Strips raw XML tool call markup that some models emit in text responses
     * instead of using the structured function calling API.
     *
     * <p>Removes:
     * <ul>
     *   <li>{@code <tool_call>...</tool_call>} blocks (including partial/unclosed)</li>
     *   <li>Orphaned {@code </tool_call>} closing tags</li>
     *   <li>Closed inner tags: {@code <name>x</name>}, {@code <arg_key>x</arg_key>}, etc.</li>
     *   <li>Unclosed inner tags: {@code <arg_value>content} without closing tag</li>
     *   <li>Bare tool-like names on their own line (e.g. {@code http_get})</li>
     * </ul>
     */
    static String stripToolCallTags(String text) {
        if (text == null) {
            return null;
        }
        String result = TOOL_CALL_BLOCK_PATTERN.matcher(text).replaceAll("");
        result = TOOL_CALL_OPEN_PATTERN.matcher(result).replaceAll("");
        result = TOOL_CALL_CLOSE_PATTERN.matcher(result).replaceAll("");
        if (result.contains("<arg_key>") || result.contains("<arg_value>")) {
            result = TOOL_CALL_INNER_TAGS_PATTERN.matcher(result).replaceAll("");
            result = TOOL_CALL_UNCLOSED_INNER_TAG_PATTERN.matcher(result).replaceAll("");
            result = BARE_TOOL_NAME_PATTERN.matcher(result).replaceAll("");
        }
        return result.trim().isEmpty() ? "" : result.trim();
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
                    // Append summary to existing system prompt instead of adding a second SystemMessage
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
