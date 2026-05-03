package io.github.ngirchev.opendaimon.common.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import io.github.ngirchev.opendaimon.common.ai.command.AICommand;
import io.github.ngirchev.opendaimon.common.ai.command.ChatAICommand;
import io.github.ngirchev.opendaimon.common.config.CoreCommonProperties;
import io.github.ngirchev.opendaimon.common.model.ConversationThread;
import io.github.ngirchev.opendaimon.common.model.OpenDaimonMessage;
import io.github.ngirchev.opendaimon.common.model.MessageRole;
import io.github.ngirchev.opendaimon.common.model.ThreadScopeKind;
import io.github.ngirchev.opendaimon.common.model.User;
import java.util.Optional;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static io.github.ngirchev.opendaimon.common.ai.ModelCapabilities.*;
import static io.github.ngirchev.opendaimon.common.service.AIUtils.retrieveMessage;

/**
 * Service for automatic summarization of long conversations.
 * When a conversation gets too long, creates a summary of older messages
 * and stores key facts in memory bullets.
 * <p>
 * Bean is created in CoreAutoConfig (no @Service for explicit control).
 */
@RequiredArgsConstructor
@Slf4j
public class SummarizationService {

    private static final String NO_MESSAGES_TO_SUMMARIZE_FOR_THREAD = "No messages to summarize for thread {}";
    private static final String MEMORY_BULLETS = "memory_bullets";

    private final ConversationThreadService threadService;
    private final AIGatewayRegistry aiGatewayRegistry;
    private final CoreCommonProperties coreCommonProperties;
    private final ObjectMapper objectMapper;
    private final ChatOwnerLookup chatOwnerLookup;
    
    // Sync by threadKey to prevent concurrent summarization
    private final Set<String> ongoingSummarizations = ConcurrentHashMap.newKeySet();

    /** Max retries when model response is not valid JSON. */
    private static final int SUMMARIZATION_PARSE_MAX_RETRIES = 3;

    /**
     * Synchronous summarization for a thread.
     * Takes messages as parameter (does not load from DB).
     * Used by SummarizingChatMemory for Spring AI.
     */
    @Transactional
    public void summarizeThread(ConversationThread thread, List<OpenDaimonMessage> messages) {
        String threadKey = thread.getThreadKey();

        // Sync by threadKey to prevent concurrent summarization
        if (!ongoingSummarizations.add(threadKey)) {
            log.debug("Summarization already in progress for thread {}, skipping", threadKey);
            return;
        }

        try {
            log.info("Starting synchronous summarization for thread {}", threadKey);

            if (messages.isEmpty()) {
                log.warn(NO_MESSAGES_TO_SUMMARIZE_FOR_THREAD, threadKey);
                return;
            }

            // Sync path: messages already filtered by caller
            performSummarization(thread, messages);

            log.info("Successfully completed synchronous summarization for thread {}", threadKey);
        } catch (Exception e) {
            log.error("Error during synchronous summarization for thread {}", threadKey, e);
            throw new RuntimeException("Summarization failed", e);
        } finally {
            ongoingSummarizations.remove(threadKey);
        }
    }

    /**
     * Shared summarization logic for sync and async paths.
     * Summarizes ALL passed messages without extra filtering.
     * Caller must pass only the messages to summarize.
     */
    private void performSummarization(ConversationThread thread, List<OpenDaimonMessage> messages) {
        if (messages.isEmpty()) {
            log.warn(NO_MESSAGES_TO_SUMMARIZE_FOR_THREAD, thread.getThreadKey());
            return;
        }
        log.debug("Summarizing {} messages for thread {}", messages.size(), thread.getThreadKey());
        String dialogTextStr = buildDialogTextForSummarization(thread, messages);
        SummaryResult result = callAiAndParseSummaryResult(dialogTextStr, thread);
        // Unified summary: the model already sees the previous summary in buildDialogText
        // and produces a single unified summary (not a continuation).
        threadService.updateThreadSummary(thread, result.summary(), result.memoryBullets());
        log.info("Successfully summarized {} messages for thread {}", messages.size(), thread.getThreadKey());
    }

    /**
     * Returns the preferred model of the chat-scoped owner (group entity for group chats,
     * user entity for private chats). Empty when the thread has no chat scope, when no
     * owner is resolvable, or when the owner has not picked a model yet (AUTO routing).
     */
    private Optional<String> resolveChatOwnerPreferredModel(ConversationThread thread) {
        if (thread == null || thread.getScopeKind() != ThreadScopeKind.TELEGRAM_CHAT || thread.getScopeId() == null) {
            return Optional.empty();
        }
        Optional<User> owner = chatOwnerLookup.findByChatId(thread.getScopeId());
        return owner.map(User::getPreferredModelId)
                .filter(id -> id != null && !id.isBlank());
    }

    private String buildDialogTextForSummarization(ConversationThread thread, List<OpenDaimonMessage> messages) {
        StringBuilder dialogText = new StringBuilder();
        if (thread.getSummary() != null && !thread.getSummary().isEmpty()) {
            dialogText.append("=== Previous conversation summary ===\n");
            dialogText.append(thread.getSummary()).append("\n\n");
            if (thread.getMemoryBullets() != null && !thread.getMemoryBullets().isEmpty()) {
                dialogText.append("Key points from previous conversation:\n");
                thread.getMemoryBullets().forEach(bullet -> dialogText.append("• ").append(bullet).append("\n"));
                dialogText.append("\n");
            }
            dialogText.append("=== Conversation continuation ===\n\n");
        }
        for (OpenDaimonMessage message : messages) {
            if (message.getRole() == MessageRole.USER) {
                dialogText.append("USER: ").append(message.getContent()).append("\n\n");
            } else if (message.getRole() == MessageRole.ASSISTANT) {
                dialogText.append("ASSISTANT: ").append(message.getContent()).append("\n\n");
            }
        }
        return dialogText.toString();
    }

    private SummaryResult callAiAndParseSummaryResult(String dialogTextStr, ConversationThread thread) {
        String summarizationPrompt = coreCommonProperties.getSummarization().getPrompt();
        // Summarization does not need reasoning — disable it explicitly to avoid
        // failures on small free models with tight budget constraints (max_price=0.5).
        // Pass empty body + null for maxReasoningTokens via metadata to prevent reasoning from being added.
        //
        // Seed the chat's preferred model id so group chats summarize with the group's
        // explicit model choice (fixing "HTTP 400 model is required" regression where
        // AUTO-routing produced an empty request body for certain tariffs).
        Map<String, String> summarizationMetadata = new HashMap<>();
        resolveChatOwnerPreferredModel(thread)
                .ifPresent(modelId -> summarizationMetadata.put(AICommand.PREFERRED_MODEL_ID_FIELD, modelId));
        ChatAICommand summaryCommand = new ChatAICommand(
                Set.of(SUMMARIZATION), Set.of(), 0.3, coreCommonProperties.getSummarization().getMaxOutputTokens(), null,
                summarizationPrompt, dialogTextStr, false, summarizationMetadata, new HashMap<>(), List.of());
        AIGateway aiGateway = aiGatewayRegistry.getSupportedAiGateways(summaryCommand).stream()
                .findFirst()
                .orElseThrow(() -> new RuntimeException("No AI gateway for summarization"));
        RuntimeException lastError;
        for (int attempt = 1; true; attempt++) {
            String summaryResponse = retrieveMessage(aiGateway.generateResponse(summaryCommand))
                    .orElseThrow(() -> new RuntimeException("Response is empty"));
            try {
                return parseSummaryResponse(summaryResponse);
            } catch (RuntimeException e) {
                lastError = e;
                log.warn("Summarization response is not valid JSON (attempt {}/{}), retrying", attempt, SUMMARIZATION_PARSE_MAX_RETRIES, e);
                if (attempt == SUMMARIZATION_PARSE_MAX_RETRIES) {
                    throw new RuntimeException("Summarization failed after " + SUMMARIZATION_PARSE_MAX_RETRIES
                            + " attempts: model did not return valid JSON", lastError);
                }
            }
        }
    }

    private SummaryResult parseSummaryResponse(String response) {
        // Extract JSON from markdown block or wrapped text (model may have added preamble)
        String jsonContent = extractJsonFromMarkdown(response);
        jsonContent = extractJsonObjectIfNeeded(jsonContent);

        try {
            JsonNode node = objectMapper.readTree(jsonContent);
            String summary = node.has("summary") ? node.get("summary").asText() : "";
            List<String> bullets = new ArrayList<>();
            if (node.has(MEMORY_BULLETS) && node.get(MEMORY_BULLETS).isArray()) {
                node.get(MEMORY_BULLETS).forEach(b -> bullets.add(b.asText()));
            }
            return new SummaryResult(summary, bullets);
        } catch (Exception e) {
            throw new RuntimeException("Invalid summarization response: not valid JSON", e);
        }
    }

    /**
     * Tries to extract a single JSON object from the string (text before/after JSON from model).
     */
    private String extractJsonObjectIfNeeded(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        int start = s.indexOf('{');
        if (start == -1) {
            return s;
        }
        int depth = 0;
        int end = -1;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    end = i;
                    break;
                }
            }
        }
        if (end != -1) {
            return s.substring(start, end + 1);
        }
        return s;
    }

    /**
     * Extracts JSON from markdown block if wrapped in ```json.
     * If no markdown block found, returns the original string.
     */
    private String extractJsonFromMarkdown(String response) {
        if (response == null || response.trim().isEmpty()) {
            return response;
        }
        
        String trimmed = response.trim();
        
        // Check if string starts with ```json or ```
        if (trimmed.startsWith("```json")) {
            // Remove ```json at start and ``` at end
            String content = trimmed.substring("```json".length());
            int endIndex = content.lastIndexOf("```");
            if (endIndex != -1) {
                return content.substring(0, endIndex).trim();
            }
        } else if (trimmed.startsWith("```")) {
            // Remove ``` at start and end (no language tag)
            String content = trimmed.substring("```".length());
            int endIndex = content.lastIndexOf("```");
            if (endIndex != -1) {
                return content.substring(0, endIndex).trim();
            }
        }
        
        // No markdown block found, return original string
        return response;
    }


    private record SummaryResult(String summary, List<String> memoryBullets) {
    }
}

