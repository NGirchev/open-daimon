package io.github.ngirchev.opendaimon.common.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import io.github.ngirchev.opendaimon.common.model.ConversationThread;
import io.github.ngirchev.opendaimon.common.model.ThreadScopeKind;
import io.github.ngirchev.opendaimon.common.model.User;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ConversationThreadRepository extends JpaRepository<ConversationThread, Long> {

    /**
     * Finds thread by unique key.
     */
    Optional<ConversationThread> findByThreadKey(String threadKey);

    /**
     * Finds thread by unique key with user eagerly loaded (avoids LazyInitializationException
     * when called outside of an active Hibernate session, e.g. from Spring event listeners).
     */
    @Query("SELECT t FROM ConversationThread t JOIN FETCH t.user WHERE t.threadKey = :threadKey")
    Optional<ConversationThread> findByThreadKeyWithUser(@Param("threadKey") String threadKey);
    
    /**
     * Finds all active threads of user, sorted by last activity date (newest first).
     */
    List<ConversationThread> findByUserAndIsActiveTrueOrderByLastActivityAtDesc(User user);
    
    /**
     * Finds all user threads (active and inactive), sorted by last activity date (newest first).
     */
    List<ConversationThread> findByUserOrderByLastActivityAtDesc(User user);
    
    /**
     * Finds user's active threads that have been inactive longer than specified time.
     */
    List<ConversationThread> findByUserAndIsActiveTrueAndLastActivityAtBefore(
            User user, OffsetDateTime before);
    
    /**
     * Finds user's most recent active thread.
     * Uses Spring Data JPA naming convention for query generation.
     */
    Optional<ConversationThread> findFirstByUserAndIsActiveTrueOrderByLastActivityAtDesc(User user);
    
    /**
     * Finds user's most recent active thread (convenience alias).
     */
    default Optional<ConversationThread> findMostRecentActiveThread(User user) {
        return findFirstByUserAndIsActiveTrueOrderByLastActivityAtDesc(user);
    }

    /**
     * Finds all active threads in scope, sorted by last activity date (newest first).
     */
    List<ConversationThread> findByScopeKindAndScopeIdAndIsActiveTrueOrderByLastActivityAtDesc(
            ThreadScopeKind scopeKind, Long scopeId);

    /**
     * Finds all threads in scope (active + inactive), sorted by last activity date (newest first).
     */
    List<ConversationThread> findByScopeKindAndScopeIdOrderByLastActivityAtDesc(
            ThreadScopeKind scopeKind, Long scopeId);

    /**
     * Finds most recent active thread in scope.
     */
    Optional<ConversationThread> findFirstByScopeKindAndScopeIdAndIsActiveTrueOrderByLastActivityAtDesc(
            ThreadScopeKind scopeKind, Long scopeId);

    /**
     * Finds most recent active thread in scope (convenience alias).
     */
    default Optional<ConversationThread> findMostRecentActiveThread(ThreadScopeKind scopeKind, Long scopeId) {
        return findFirstByScopeKindAndScopeIdAndIsActiveTrueOrderByLastActivityAtDesc(scopeKind, scopeId);
    }
}
