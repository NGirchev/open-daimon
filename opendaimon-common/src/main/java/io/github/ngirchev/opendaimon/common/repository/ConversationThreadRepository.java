package io.github.ngirchev.opendaimon.common.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import io.github.ngirchev.opendaimon.common.model.ConversationThread;
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
}

