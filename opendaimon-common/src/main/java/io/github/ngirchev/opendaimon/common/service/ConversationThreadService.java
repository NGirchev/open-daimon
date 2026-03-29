package io.github.ngirchev.opendaimon.common.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import io.github.ngirchev.opendaimon.common.model.ConversationThread;
import io.github.ngirchev.opendaimon.common.model.OpenDaimonMessage;
import io.github.ngirchev.opendaimon.common.model.ThreadScopeKind;
import io.github.ngirchev.opendaimon.common.model.User;
import io.github.ngirchev.opendaimon.common.repository.ConversationThreadRepository;
import io.github.ngirchev.opendaimon.common.repository.OpenDaimonMessageRepository;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for managing conversation threads (AI conversations).
 * Base service in opendaimon-common, used directly in handlers.
 *
 * Bean is created in CoreAutoConfig (no @Service for explicit control).
 */
@RequiredArgsConstructor
@Slf4j
@Transactional
public class ConversationThreadService {
    
    private final ConversationThreadRepository threadRepository;
    private final OpenDaimonMessageRepository messageRepository;
    
    private static final Duration THREAD_INACTIVITY_TIMEOUT = Duration.ofHours(24);
    
    /**
     * Gets or creates active thread for user.
     */
    public ConversationThread getOrCreateThread(User user) {
        Long userId = requirePersistedUserId(user);
        return getOrCreateThread(user, ThreadScopeKind.USER, userId);
    }

    /**
     * Gets or creates active thread for explicit scope.
     */
    public ConversationThread getOrCreateThread(User user, ThreadScopeKind scopeKind, Long scopeId) {
        validateScope(scopeKind, scopeId);
        return threadRepository.findMostRecentActiveThread(scopeKind, scopeId)
            .filter(this::isThreadStillActive)
            .orElseGet(() -> createNewThread(user, scopeKind, scopeId));
    }
    
    /**
     * Creates new thread.
     */
    public ConversationThread createNewThread(User user) {
        Long userId = requirePersistedUserId(user);
        return createNewThread(user, ThreadScopeKind.USER, userId);
    }

    /**
     * Creates new thread for explicit scope.
     */
    public ConversationThread createNewThread(User user, ThreadScopeKind scopeKind, Long scopeId) {
        validateScope(scopeKind, scopeId);
        ConversationThread thread = new ConversationThread();
        thread.setUser(user);
        thread.setThreadKey(UUID.randomUUID().toString());
        thread.setScopeKind(scopeKind);
        thread.setScopeId(scopeId);
        thread.setIsActive(true);
        thread.setLastActivityAt(OffsetDateTime.now());
        thread.setTotalMessages(0);
        thread.setTotalTokens(0L);
        thread.setMemoryBullets(new ArrayList<>());
        
        log.info("Created new conversation thread {} for user {} in scope {}:{}",
                thread.getThreadKey(), user.getId(), scopeKind, scopeId);
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
     * Updates thread counters from its messages.
     * When summarization has occurred, only tokens from messages after the summarization
     * point are counted — this keeps the context usage indicator accurate.
     * Called after saving Message.
     */
    public void updateThreadCounters(ConversationThread thread) {
        Integer messageCount = messageRepository.countByThread(thread);
        int totalMessages = messageCount != null ? messageCount : 0;

        // After summarization only count tokens for new messages (sequenceNumber > messagesAtLastSummarization)
        // so that the context-usage indicator resets to 0 after context compaction.
        Integer messagesAtLastSummarization = thread.getMessagesAtLastSummarization();
        List<OpenDaimonMessage> messages = messagesAtLastSummarization != null
                ? messageRepository.findByThreadAndSequenceNumberGreaterThanOrderBySequenceNumberAsc(
                        thread, messagesAtLastSummarization)
                : messageRepository.findByThreadOrderBySequenceNumberAsc(thread);
        long totalTokens = messages.stream()
            .mapToLong(m -> m.getTokenCount() != null ? m.getTokenCount() : 0)
            .sum();

        thread.setTotalMessages(totalMessages);
        thread.setTotalTokens(totalTokens);
        thread.setLastActivityAt(OffsetDateTime.now());
        threadRepository.save(thread);

        log.debug("Updated thread {} counters: {} messages, {} tokens (from sequence > {})",
            thread.getThreadKey(), totalMessages, totalTokens, messagesAtLastSummarization);
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
     * Returns the current active thread for the user without creating a new one.
     * Use this when you need the thread for display purposes only.
     */
    @Transactional(readOnly = true)
    public Optional<ConversationThread> findCurrentThread(User user) {
        Long userId = requirePersistedUserId(user);
        return findCurrentThread(ThreadScopeKind.USER, userId);
    }

    /**
     * Returns current active thread for explicit scope without creating a new one.
     */
    @Transactional(readOnly = true)
    public Optional<ConversationThread> findCurrentThread(ThreadScopeKind scopeKind, Long scopeId) {
        validateScope(scopeKind, scopeId);
        return threadRepository.findMostRecentActiveThread(scopeKind, scopeId)
            .filter(this::isThreadStillActive);
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
        Long userId = requirePersistedUserId(user);
        return activateThread(user, threadToActivate, ThreadScopeKind.USER, userId);
    }

    /**
     * Activates thread in explicit scope (closes current active and activates selected).
     */
    public ConversationThread activateThread(User user, ConversationThread threadToActivate,
                                             ThreadScopeKind scopeKind, Long scopeId) {
        validateScope(scopeKind, scopeId);

        // Guard against switching threads across scopes accidentally.
        if (threadToActivate.getScopeKind() != scopeKind || !scopeId.equals(threadToActivate.getScopeId())) {
            throw new IllegalArgumentException("Thread scope does not match requested scope");
        }

        // Close current active thread (if any)
        threadRepository.findMostRecentActiveThread(scopeKind, scopeId)
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
        
        log.info("Activated conversation thread {} for user {} in scope {}:{}",
                threadToActivate.getThreadKey(), user.getId(), scopeKind, scopeId);
        return threadToActivate;
    }

    private static void validateScope(ThreadScopeKind scopeKind, Long scopeId) {
        if (scopeKind == null) {
            throw new IllegalArgumentException("scopeKind is required");
        }
        if (scopeId == null) {
            throw new IllegalArgumentException("scopeId is required");
        }
    }

    private static Long requirePersistedUserId(User user) {
        if (user == null || user.getId() == null) {
            throw new IllegalArgumentException("Persisted user with id is required");
        }
        return user.getId();
    }
}
