package io.github.ngirchev.opendaimon.ai.springai.memory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.lang.NonNull;
import io.github.ngirchev.opendaimon.common.event.SummarizationStartedEvent;
import io.github.ngirchev.opendaimon.common.exception.SummarizationFailedException;
import io.github.ngirchev.opendaimon.common.model.ConversationThread;
import io.github.ngirchev.opendaimon.common.model.OpenDaimonMessage;
import io.github.ngirchev.opendaimon.common.repository.OpenDaimonMessageRepository;
import io.github.ngirchev.opendaimon.common.repository.ConversationThreadRepository;
import io.github.ngirchev.opendaimon.common.service.SummarizationService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Custom ChatMemory implementation that integrates SummarizationService.
 *
 * Main behaviour:
 * 1. Delegates core work to MessageWindowChatMemory
 * 2. When loading history adds summary from ConversationThread as SystemMessage
 * 3. On save checks whether to trigger summarization via SummarizationService
 *
 * IMPORTANT: conversationId corresponds to thread_key from ConversationThread.
 */
@Slf4j
public class SummarizingChatMemory implements ChatMemory {

    private final MessageWindowChatMemory delegate; // MessageWindowChatMemory
    private final ConversationThreadRepository conversationThreadRepository;
    private final OpenDaimonMessageRepository messageRepository;
    private final SummarizationService summarizationService;
    private final ApplicationEventPublisher eventPublisher;
    private final Integer maxMessages; // Max messages from MessageWindowChatMemory
    private final Integer maxWindowTokens; // Max tokens trigger for summarization

    public SummarizingChatMemory(
            ChatMemoryRepository chatMemoryRepository,
            ConversationThreadRepository conversationThreadRepository,
            OpenDaimonMessageRepository messageRepository,
            SummarizationService summarizationService,
            ApplicationEventPublisher eventPublisher,
            Integer maxMessages,
            Integer maxWindowTokens) {
        this.conversationThreadRepository = conversationThreadRepository;
        this.messageRepository = messageRepository;
        this.summarizationService = summarizationService;
        this.eventPublisher = eventPublisher;
        this.maxMessages = maxMessages;
        this.maxWindowTokens = maxWindowTokens;
        this.delegate = MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(maxMessages)
                .build();
        log.info("SummarizingChatMemory initialized: maxMessages={}, maxWindowTokens={}", maxMessages, maxWindowTokens);
    }

    @Override
    @NonNull
    public List<Message> get(@NonNull String conversationId) {
        // Get messages from delegate (MessageWindowChatMemory)
        List<Message> messages = delegate.get(conversationId);

        // Primary-store recovery: if the delegate cache is empty but the primary
        // store (ConversationThread + OpenDaimonMessage) has history — rebuild the
        // window from it. This covers app restarts and cache evictions: without
        // this fallback the agent would lose all context on every restart.
        if (messages.isEmpty()) {
            List<Message> restored = restoreHistoryFromPrimaryStore(conversationId);
            if (!restored.isEmpty()) {
                synchronized (conversationId.intern()) {
                    // Re-check under lock in case a concurrent writer populated it.
                    if (delegate.get(conversationId).isEmpty()) {
                        for (Message m : restored) {
                            delegate.add(conversationId, m);
                        }
                    }
                }
                messages = delegate.get(conversationId);
                log.info("Restored ChatMemory from primary store for conversationId {}: {} messages",
                        conversationId, messages.size());
            }
        }

        int messageCount = messages.size();

        // Check if summarization should be triggered (by messages or tokens)
        boolean messageLimitReached = messageCount >= maxMessages;
        boolean tokenLimitReached = false;

        if (!messageLimitReached && maxWindowTokens != null) {
            Optional<ConversationThread> threadOpt = conversationThreadRepository.findByThreadKey(conversationId);
            tokenLimitReached = threadOpt
                .map(t -> t.getTotalTokens() != null && t.getTotalTokens() >= maxWindowTokens)
                .orElse(false);
        }

        // If either limit reached, trigger summarization before Spring AI evicts anything
        if (messageLimitReached || tokenLimitReached) {
            log.info("Summarization triggered: messages={}/{}, tokens={}, conversationId={}",
                messageCount, maxMessages, tokenLimitReached ? "limit reached" : "ok", conversationId);
            eventPublisher.publishEvent(new SummarizationStartedEvent(conversationId));

            if (performSummarizationAndUpdateChatMemory(conversationId)) {
                messages = delegate.get(conversationId);
                log.debug("After summarization, ChatMemory has {} messages for conversationId {}",
                    messages.size(), conversationId);
            }
        }

        return messages;
    }

    @Override
    public void add(@NonNull String conversationId, @NonNull Message message) {
        // Save message via delegate
        delegate.add(conversationId, message);
    }

    @Override
    public void add(@NonNull String conversationId, @NonNull List<Message> messages) {
        // Save messages via delegate
        delegate.add(conversationId, messages);
    }

    /**
     * Rebuilds a ChatMemory window from the primary store (ConversationThread +
     * OpenDaimonMessage) when the cache is empty. Returns the messages to seed
     * into the delegate — caller owns actually adding them under the shared
     * per-conversation lock.
     *
     * <p>Layout of the restored window (oldest → newest):
     * <ol>
     *   <li>{@code SystemMessage(summary + memoryBullets)} if the thread has a summary</li>
     *   <li>Up to {@code maxMessages - 1} most recent messages from {@code messages_at_last_summarization + 1}</li>
     * </ol>
     *
     * <p>When the primary store has no thread (first-ever interaction) returns an
     * empty list — the caller keeps the delegate empty and the agent treats the
     * conversation as fresh.
     */
    private List<Message> restoreHistoryFromPrimaryStore(@NonNull String conversationId) {
        try {
            Optional<ConversationThread> threadOpt =
                    conversationThreadRepository.findByThreadKey(conversationId);
            if (threadOpt.isEmpty()) {
                return List.of();
            }
            ConversationThread thread = threadOpt.get();

            List<Message> restored = new ArrayList<>();
            if (thread.getSummary() != null && !thread.getSummary().isEmpty()) {
                restored.add(new SystemMessage(buildSummaryContent(thread)));
            }

            Integer messagesAtLastSummarization = thread.getMessagesAtLastSummarization();
            int minSequenceNumber = messagesAtLastSummarization != null ? messagesAtLastSummarization : 0;

            List<OpenDaimonMessage> postSummaryMessages = messageRepository
                    .findByThreadAndSequenceNumberGreaterThanOrderBySequenceNumberAsc(thread, minSequenceNumber);

            // Reserve one slot in the window for the incoming user message.
            int windowCapacity = Math.max(0, maxMessages - restored.size() - 1);
            int startIdx = Math.max(0, postSummaryMessages.size() - windowCapacity);
            for (int i = startIdx; i < postSummaryMessages.size(); i++) {
                restored.add(convertToSpringMessage(postSummaryMessages.get(i)));
            }
            return restored;
        } catch (Exception e) {
            log.warn("Failed to restore ChatMemory from primary store for conversationId {}: {}",
                    conversationId, e.getMessage());
            return List.of();
        }
    }

    /**
     * Performs partial summarization: summarizes the older half of messages,
     * keeps the recent half in ChatMemory for context continuity.
     *
     * Logic:
     * 1. Loads messages from main DB (after last summarization point)
     * 2. Splits into older half (to summarize) and recent half (to keep)
     * 3. Summarizes the older half via SummarizationService
     * 4. Rebuilds ChatMemory: SystemMessage(summary) + recent messages
     *
     * @param conversationId conversation id
     * @return true if summarization succeeded; false if nothing to summarize
     * @throws SummarizationFailedException if the AI call failed
     */
    private boolean performSummarizationAndUpdateChatMemory(@NonNull String conversationId) {
        try {
            Optional<ConversationThread> threadOpt = conversationThreadRepository.findByThreadKey(conversationId);

            if (threadOpt.isEmpty()) {
                log.debug("Thread not found for conversationId {}, skipping summarization", conversationId);
                return false;
            }

            ConversationThread thread = threadOpt.get();

            log.info("Triggering partial summarization for conversationId {} (thread {})",
                conversationId, thread.getThreadKey());

            // Load messages from main DB (after last summarization point)
            Integer messagesAtLastSummarization = thread.getMessagesAtLastSummarization();
            int minSequenceNumber = messagesAtLastSummarization != null ? messagesAtLastSummarization : 0;

            List<OpenDaimonMessage> allMessages = new ArrayList<>(messageRepository
                .findByThreadAndSequenceNumberGreaterThanOrderBySequenceNumberAsc(thread, minSequenceNumber));

            if (allMessages.size() < 2) {
                log.warn("Not enough messages to summarize for conversationId {}", conversationId);
                return false;
            }

            // Split: older half to summarize, recent half to keep
            int half = allMessages.size() / 2;
            List<OpenDaimonMessage> toSummarize = allMessages.subList(0, half);
            List<OpenDaimonMessage> toKeep = allMessages.subList(half, allMessages.size());

            log.info("Partial summarization: {} messages to summarize, {} to keep for conversationId {}",
                toSummarize.size(), toKeep.size(), conversationId);

            // Summarize the older half
            summarizationService.summarizeThread(thread, toSummarize);

            // Refresh thread from DB after summarization
            thread = conversationThreadRepository.findByThreadKey(conversationId)
                .orElseThrow(() -> new RuntimeException("Thread not found after summarization"));

            // Rebuild ChatMemory atomically so that any concurrent get() on the same
            // conversationId never observes a half-cleared state. conversationId.intern()
            // gives us one shared monitor per conversation — cheap, and the critical
            // section does no I/O (summarization LLM call already ran above).
            synchronized (conversationId.intern()) {
                delegate.clear(conversationId);

                if (thread.getSummary() != null && !thread.getSummary().isEmpty()) {
                    String summaryContent = buildSummaryContent(thread);
                    delegate.add(conversationId, new SystemMessage(summaryContent));
                }

                for (OpenDaimonMessage msg : toKeep) {
                    Message springMessage = convertToSpringMessage(msg);
                    delegate.add(conversationId, springMessage);
                }
            }

            log.info("Successfully summarized and rebuilt ChatMemory for conversationId {}: summary + {} recent messages",
                conversationId, toKeep.size());
            return true;
        } catch (Exception e) {
            log.error("Error during summarization for conversationId {}", conversationId, e);
            throw new SummarizationFailedException(
                    "Conversation summarization failed. Please start a new session (/newthread).", e);
        }
    }

    /**
     * Converts OpenDaimonMessage to Spring AI Message.
     * For USER messages with attachments, appends file metadata to the message text
     * so the model knows which files were uploaded in the conversation history.
     */
    private Message convertToSpringMessage(OpenDaimonMessage msg) {
        return switch (msg.getRole()) {
            case USER -> new UserMessage(enrichWithAttachmentInfo(msg.getContent(), msg.getAttachments()));
            case ASSISTANT -> new AssistantMessage(msg.getContent());
            case SYSTEM -> new SystemMessage(msg.getContent());
        };
    }

    /**
     * Appends attachment metadata to user message text so the model can reference
     * previously uploaded files in follow-up messages.
     */
    private String enrichWithAttachmentInfo(String content, List<Map<String, Object>> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return content;
        }
        StringBuilder sb = new StringBuilder(content != null ? content : "");
        sb.append("\n[Attached files: ");
        for (int i = 0; i < attachments.size(); i++) {
            if (i > 0) sb.append(", ");
            Map<String, Object> att = attachments.get(i);
            String filename = (String) att.get("filename");
            String mimeType = (String) att.get("mimeType");
            if (filename != null) {
                sb.append("\"").append(filename).append("\"");
            }
            if (mimeType != null) {
                sb.append(" (").append(mimeType).append(")");
            }
        }
        sb.append("]");
        return sb.toString();
    }

    @Override
    public void clear(@NonNull String conversationId) {
        delegate.clear(conversationId);
    }

    /**
     * Builds SystemMessage content from summary and memory bullets.
     */
    private String buildSummaryContent(ConversationThread thread) {
        StringBuilder content = new StringBuilder();
        
        content.append("Summary of previous conversation:\n");
        content.append(thread.getSummary());
        
        if (thread.getMemoryBullets() != null && !thread.getMemoryBullets().isEmpty()) {
            content.append("\n\nKey points:\n");
            thread.getMemoryBullets().forEach(bullet -> 
                content.append("• ").append(bullet).append("\n")
            );
        }
        
        return content.toString();
    }
}
