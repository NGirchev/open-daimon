package io.github.ngirchev.aibot.common.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import io.github.ngirchev.aibot.common.model.ConversationThread;
import io.github.ngirchev.aibot.common.model.AIBotMessage;
import io.github.ngirchev.aibot.common.model.User;
import io.github.ngirchev.aibot.common.repository.ConversationThreadRepository;
import io.github.ngirchev.aibot.common.repository.AIBotMessageRepository;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for managing conversation threads (AI conversations).
 * Base service in aibot-common, used directly in handlers.
 *
 * Bean is created in CoreAutoConfig (no @Service for explicit control).
 */
@RequiredArgsConstructor
@Slf4j
@Transactional
public class ConversationThreadService {
    
    private final ConversationThreadRepository threadRepository;
    private final AIBotMessageRepository messageRepository;
    
    private static final Duration THREAD_INACTIVITY_TIMEOUT = Duration.ofHours(24);
    
    /**
     * Gets or creates active thread for user.
     */
    public ConversationThread getOrCreateThread(User user) {
        return threadRepository.findMostRecentActiveThread(user)
            .filter(this::isThreadStillActive)
            .orElseGet(() -> createNewThread(user));
    }
    
    /**
     * Creates new thread.
     */
    public ConversationThread createNewThread(User user) {
        ConversationThread thread = new ConversationThread();
        thread.setUser(user);
        thread.setThreadKey(UUID.randomUUID().toString());
        thread.setIsActive(true);
        thread.setLastActivityAt(OffsetDateTime.now());
        thread.setTotalMessages(0);
        thread.setTotalTokens(0L);
        thread.setMemoryBullets(new ArrayList<>());
        
        log.info("Created new conversation thread {} for user {}", thread.getThreadKey(), user.getId());
        return threadRepository.save(thread);
    }
    
    /**
     * Sets thread title from first message (if title not set yet).
     */
    public void updateThreadTitleIfNeeded(ConversationThread thread, String firstUserMessage) {
        if (thread.getTitle() == null || thread.getTitle().isEmpty()) {
            // Use first 50 chars of first message as title
            String title = firstUserMessage.length() > 50 
                ? firstUserMessage.substring(0, 47) + "..." 
                : firstUserMessage;
            thread.setTitle(title);
            threadRepository.save(thread);
            log.debug("Set title for thread {}: {}", thread.getThreadKey(), title);
        }
    }
    
    /**
     * Updates thread counters from all its messages.
     * Called after saving Message.
     */
    public void updateThreadCounters(ConversationThread thread) {
        // Count total messages and tokens from all Messages
        Integer messageCount = messageRepository.countByThread(thread);
        int totalMessages = messageCount != null ? messageCount : 0;
        
        // Sum tokens from all Messages
        List<AIBotMessage> messages = messageRepository
            .findByThreadOrderBySequenceNumberAsc(thread);
        long totalTokens = messages.stream()
            .mapToLong(m -> m.getTokenCount() != null ? m.getTokenCount() : 0)
            .sum();
        
        thread.setTotalMessages(totalMessages);
        thread.setTotalTokens(totalTokens);
        thread.setLastActivityAt(OffsetDateTime.now());
        threadRepository.save(thread);
        
        log.debug("Updated thread {} counters: {} messages, {} tokens", 
            thread.getThreadKey(), totalMessages, totalTokens);
    }
    
    /**
     * Closes thread (marks as inactive).
     */
    public void closeThread(ConversationThread thread) {
        thread.setIsActive(false);
        thread.setClosedAt(OffsetDateTime.now());
        threadRepository.save(thread);
        log.info("Closed conversation thread {}", thread.getThreadKey());
    }
    
    /**
     * Checks if thread is still active (by last activity time).
     */
    private boolean isThreadStillActive(ConversationThread thread) {
        if (!thread.getIsActive()) {
            return false;
        }
        
        OffsetDateTime lastActivity = thread.getLastActivityAt();
        if (lastActivity == null) {
            return true; // New thread
        }
        
        Duration inactivity = Duration.between(lastActivity, OffsetDateTime.now());
        return inactivity.compareTo(THREAD_INACTIVITY_TIMEOUT) < 0;
    }
    
    /**
     * Updates summary and memory bullets for thread.
     * Also saves current message count to track new messages after summarization.
     */
    public void updateThreadSummary(ConversationThread thread, String summary, List<String> memoryBullets) {
        thread.setSummary(summary);
        thread.setMemoryBullets(memoryBullets != null ? memoryBullets : new ArrayList<>());
        // Save current message count at summarization time
        thread.setMessagesAtLastSummarization(thread.getTotalMessages());
        threadRepository.save(thread);
        log.info("Updated summary for thread {} (messages at summarization: {})", 
            thread.getThreadKey(), thread.getMessagesAtLastSummarization());
    }
    
    /**
     * Finds thread by key.
     */
    public Optional<ConversationThread> findByThreadKey(String threadKey) {
        return threadRepository.findByThreadKey(threadKey);
    }
    
    /**
     * Activates thread for user (closes current active and activates selected).
     */
    public ConversationThread activateThread(User user, ConversationThread threadToActivate) {
        // Close current active thread (if any)
        threadRepository.findMostRecentActiveThread(user)
            .ifPresent(currentThread -> {
                if (!currentThread.getId().equals(threadToActivate.getId())) {
                    closeThread(currentThread);
                }
            });
        
        // Activate selected thread
        threadToActivate.setIsActive(true);
        threadToActivate.setLastActivityAt(OffsetDateTime.now());
        threadToActivate.setClosedAt(null); // Clear close date if set
        threadRepository.save(threadToActivate);
        
        log.info("Activated conversation thread {} for user {}", threadToActivate.getThreadKey(), user.getId());
        return threadToActivate;
    }
}

