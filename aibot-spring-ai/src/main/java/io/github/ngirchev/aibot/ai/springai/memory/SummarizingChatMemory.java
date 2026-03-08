package io.github.ngirchev.aibot.ai.springai.memory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.lang.NonNull;
import io.github.ngirchev.aibot.common.model.ConversationThread;
import io.github.ngirchev.aibot.common.model.AIBotMessage;
import io.github.ngirchev.aibot.common.repository.AIBotMessageRepository;
import io.github.ngirchev.aibot.common.repository.ConversationThreadRepository;
import io.github.ngirchev.aibot.common.service.SummarizationService;

import java.util.List;
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
    private final AIBotMessageRepository messageRepository;
    private final SummarizationService summarizationService;
    private final Integer maxMessages; // Max messages from MessageWindowChatMemory

    public SummarizingChatMemory(
            ChatMemoryRepository chatMemoryRepository,
            ConversationThreadRepository conversationThreadRepository,
            AIBotMessageRepository messageRepository,
            SummarizationService summarizationService,
            Integer maxMessages) {
        this.conversationThreadRepository = conversationThreadRepository;
        this.messageRepository = messageRepository;
        this.summarizationService = summarizationService;
        this.maxMessages = maxMessages;
        this.delegate = MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(maxMessages)
                .build();
    }

    @Override
    @NonNull
    public List<Message> get(@NonNull String conversationId) {
        // Get messages from delegate (MessageWindowChatMemory)
        List<Message> messages = delegate.get(conversationId);
        
        // Check message count in ChatMemory
        int messageCount = messages.size();
        
        // If more than maxMessages, trigger summarization
        if (messageCount >= maxMessages) {
            log.debug("ChatMemory has {} messages (max: {}), triggering summarization for conversationId {}", 
                messageCount, maxMessages, conversationId);
            
            // Run summarization and update ChatMemory
            if (performSummarizationAndUpdateChatMemory(conversationId)) {
                // After successful summarization get updated message list
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
     * Performs summarization and updates ChatMemory.
     *
     * Logic:
     * 1. Gets all messages from main DB for summarization
     * 2. Calls synchronous summarization (sync inside SummarizationService)
     * 3. Clears ChatMemory (delegate.clear) — removes messages from SPRING_AI_CHAT_MEMORY
     * 4. Adds summary as SystemMessage to ChatMemory (delegate.add)
     *
     * @param conversationId conversation id
     * @return true if summarization succeeded, false otherwise
     */
    private boolean performSummarizationAndUpdateChatMemory(@NonNull String conversationId) {
        try {
            Optional<ConversationThread> threadOpt = conversationThreadRepository.findByThreadKey(conversationId);
            
            if (threadOpt.isEmpty()) {
                log.debug("Thread not found for conversationId {}, skipping summarization", conversationId);
                return false;
            }
            
            ConversationThread thread = threadOpt.get();
            
            log.info("Triggering summarization for conversationId {} (thread {})",
                conversationId, thread.getThreadKey());
            
            // Get messages from main DB for summarization
            // If there was a previous summarization, take only new messages after it
            // If messagesAtLastSummarization == null, no summarization yet, take all messages (from 0)
            Integer messagesAtLastSummarization = thread.getMessagesAtLastSummarization();
            int minSequenceNumber = messagesAtLastSummarization != null ? messagesAtLastSummarization : 0;
            
            List<AIBotMessage> messages = messageRepository
                .findByThreadAndSequenceNumberGreaterThanOrderBySequenceNumberAsc(thread, minSequenceNumber);
            messages.removeLast();
            
            log.debug("Getting messages for summarization (sequenceNumber > {}) for thread {}: {} messages",
                minSequenceNumber, thread.getThreadKey(), messages.size());
            
            if (messages.isEmpty()) {
                log.warn("No messages to summarize for conversationId {}", conversationId);
                return false;
            }
            
            // Call synchronous summarization
            summarizationService.summarizeThread(thread, messages);
            
            // Refresh thread from DB after summarization
            thread = conversationThreadRepository.findByThreadKey(conversationId)
                .orElseThrow(() -> new RuntimeException("Thread not found after summarization"));
            
            // Clear ChatMemory (Spring AI temporary history) — removes messages from SPRING_AI_CHAT_MEMORY
            delegate.clear(conversationId);
            
            // Add summary as SystemMessage to ChatMemory
            if (thread.getSummary() != null && !thread.getSummary().isEmpty()) {
                String summaryContent = buildSummaryContent(thread);
                SystemMessage summaryMessage = new SystemMessage(summaryContent);
                delegate.add(conversationId, summaryMessage);
                
                log.info("Successfully summarized and updated ChatMemory for conversationId {}: {} chars",
                    conversationId, summaryContent.length());
                return true;
            } else {
                log.warn("Summarization completed but summary is empty for conversationId {}", conversationId);
                return false;
            }
        } catch (Exception e) {
            log.error("Error during summarization for conversationId {}", conversationId, e);
            return false;
        }
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
