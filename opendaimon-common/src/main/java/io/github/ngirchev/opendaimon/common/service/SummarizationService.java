package io.github.ngirchev.opendaimon.common.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.annotation.Transactional;
import io.github.ngirchev.opendaimon.common.ai.command.ChatAICommand;
import io.github.ngirchev.opendaimon.common.config.CoreCommonProperties;
import io.github.ngirchev.opendaimon.common.model.ConversationThread;
import io.github.ngirchev.opendaimon.common.model.OpenDaimonMessage;
import io.github.ngirchev.opendaimon.common.model.MessageRole;
import io.github.ngirchev.opendaimon.common.repository.OpenDaimonMessageRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
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
    private static final String NO_MESSAGES_AFTER_FILTERING = "No messages to summarize after filtering for thread {}";
    private static final String MEMORY_BULLETS = "memory_bullets";

    private final OpenDaimonMessageRepository messageRepository;
    private final ConversationThreadService threadService;
    private final AIGatewayRegistry aiGatewayRegistry;
    private final CoreCommonProperties coreCommonProperties;
    private final ObjectMapper objectMapper;
    
    // Sync by threadKey to prevent concurrent summarization
    private final Set<String> ongoingSummarizations = ConcurrentHashMap.newKeySet();

    /** Max retries when model response is not valid JSON. */
    private static final int SUMMARIZATION_PARSE_MAX_RETRIES = 3;

    /**
     * Whether to trigger summarization based on token usage.
     * Used for non-Spring AI providers (ConversationHistoryAICommandFactory).
     */
    public boolean shouldTriggerSummarization(ConversationThread thread) {
        Long totalTokens = thread.getTotalTokens();
        if (totalTokens == null || totalTokens == 0) {
            return false;
        }

        CoreCommonProperties.SummarizationProperties summarization = coreCommonProperties.getSummarization();
        double usageRatio = (double) totalTokens / summarization.getMaxContextTokens();
        boolean shouldTrigger = usageRatio >= summarization.getSummaryTriggerThreshold();

        if (shouldTrigger) {
            log.debug("Thread {} reached summarization threshold by tokens: {} tokens / {} max = {} (threshold: {})",
                    thread.getThreadKey(), totalTokens, summarization.getMaxContextTokens(),
                    usageRatio, summarization.getSummaryTriggerThreshold());
        }

        return shouldTrigger;
    }

    /**
     * Creates or updates the summary for the thread.
     * Called asynchronously when the thread exceeds token budget.
     */
    @Async("summarizationTaskExecutor")
    @Transactional
    public CompletableFuture<Void> summarizeThreadAsync(ConversationThread thread) {
        String threadKey = thread.getThreadKey();

        // Sync by threadKey to prevent concurrent summarization
        if (!ongoingSummarizations.add(threadKey)) {
            log.debug("Summarization already in progress for thread {}, skipping", threadKey);
            return CompletableFuture.completedFuture(null);
        }

        try {
            log.info("Starting summarization for thread {}", threadKey);

            // 1. Load all messages from thread
            List<OpenDaimonMessage> messages = messageRepository
                    .findByThreadOrderBySequenceNumberAsc(thread);

            if (messages.isEmpty()) {
                log.warn(NO_MESSAGES_TO_SUMMARIZE_FOR_THREAD, threadKey);
                return CompletableFuture.completedFuture(null);
            }

            // Async path: load all messages, filter by keepRecentMessages
            int keepRecentMessages = coreCommonProperties.getSummarization().getKeepRecentMessages();
            List<OpenDaimonMessage> messagesToSummarize = filterMessagesForSummarization(messages, keepRecentMessages, thread.getThreadKey());

            if (messagesToSummarize.isEmpty()) {
                log.info(NO_MESSAGES_AFTER_FILTERING, threadKey);
                return CompletableFuture.completedFuture(null);
            }

            performSummarization(thread, messagesToSummarize);

            log.info("Successfully summarized messages for thread {}", threadKey);
        } catch (Exception e) {
            log.error("Error during summarization for thread {}", threadKey, e);
        } finally {
            ongoingSummarizations.remove(threadKey);
        }

        return CompletableFuture.completedFuture(null);
    }

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
     * Filters messages for summarization, leaving the last N messages untouched.
     *
     * @param messages all messages
     * @param keepRecentMessages number of recent messages to keep
     * @param threadKey thread key for logging
     * @return filtered list of messages to summarize (empty if nothing to summarize)
     */
    private List<OpenDaimonMessage> filterMessagesForSummarization(List<OpenDaimonMessage> messages, int keepRecentMessages, String threadKey) {
        // Too few messages (<= 2): do not summarize
        if (messages.size() <= 2) {
            log.info("Not enough messages to summarize for thread {} (only {} messages, need at least 3)",
                    threadKey, messages.size());
            return List.of();
        }
        
        int actualKeepMessages;
        if (messages.size() <= keepRecentMessages) {
            actualKeepMessages = messages.size();
        } else {
            actualKeepMessages = keepRecentMessages;
        }

        // How many messages to summarize
        int messagesToSummarizeCount = messages.size() - actualKeepMessages;

        if (messagesToSummarizeCount <= 0) {
            log.info("Not enough messages to summarize for thread {} (only {} messages, keeping {} messages)",
                    threadKey, messages.size(), actualKeepMessages);
            return List.of();
        }
        
        List<OpenDaimonMessage> messagesToSummarize = messages.subList(0, messagesToSummarizeCount);
        log.debug("Filtered messages: summarizing {} messages, keeping {} messages for thread {}",
                messagesToSummarizeCount, actualKeepMessages, threadKey);
        
        return messagesToSummarize;
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
        SummaryResult result = callAiAndParseSummaryResult(dialogTextStr);
        String combinedSummary = combineWithExistingSummary(thread.getSummary(), result.summary());
        List<String> combinedBullets = combineWithExistingBullets(thread.getMemoryBullets(), result.memoryBullets());
        threadService.updateThreadSummary(thread, combinedSummary, combinedBullets);
        log.info("Successfully summarized {} messages for thread {}", messages.size(), thread.getThreadKey());
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

    private SummaryResult callAiAndParseSummaryResult(String dialogTextStr) {
        String summarizationPrompt = coreCommonProperties.getSummarization().getPrompt();
        ChatAICommand summaryCommand = new ChatAICommand(
                Set.of(SUMMARIZATION), 0.3, 2000, summarizationPrompt, dialogTextStr);
        AIGateway aiGateway = aiGatewayRegistry.getSupportedAiGateways(summaryCommand).stream()
                .findFirst()
                .orElseThrow(() -> new RuntimeException("No AI gateway for summarization"));
        RuntimeException lastError = null;
        for (int attempt = 1; attempt <= SUMMARIZATION_PARSE_MAX_RETRIES; attempt++) {
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
        throw new RuntimeException("Summarization failed: no valid result", lastError);
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

    private String combineWithExistingSummary(String existing, String newSummary) {
        if (existing == null || existing.isEmpty()) {
            return newSummary;
        }

        // Incremental update: append new summary to existing
        return existing + "\n\n---\n\nContinuation:\n" + newSummary;
    }

    private List<String> combineWithExistingBullets(List<String> existing, List<String> newBullets) {
        List<String> combined = new ArrayList<>(existing != null ? existing : new ArrayList<>());
        combined.addAll(newBullets);

        // Cap number of bullets (e.g. last 20)
        if (combined.size() > 20) {
            return combined.subList(combined.size() - 20, combined.size());
        }

        return combined;
    }

    private record SummaryResult(String summary, List<String> memoryBullets) {
    }
}

