package io.github.ngirchev.opendaimon.common.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import io.github.ngirchev.opendaimon.common.model.ConversationThread;
import io.github.ngirchev.opendaimon.common.model.OpenDaimonMessage;
import io.github.ngirchev.opendaimon.common.model.MessageRole;
import io.github.ngirchev.opendaimon.common.model.User;

import java.util.List;
import java.util.Optional;

/**
 * Repository for dialog messages.
 * Replaces UserRequestRepository and ServiceResponseRepository.
 */
@Repository
public interface OpenDaimonMessageRepository extends JpaRepository<OpenDaimonMessage, Long> {
    
    /**
     * Finds all messages for thread, sorted by sequence number.
     */
    @Query("SELECT m FROM Message m WHERE m.thread = :thread " +
           "ORDER BY m.sequenceNumber ASC")
    List<OpenDaimonMessage> findByThreadOrderBySequenceNumberAsc(@Param("thread") ConversationThread thread);
    
    /**
     * Finds all messages for thread with sequenceNumber greater than given value, sorted by sequence number.
     */
    @Query("SELECT m FROM Message m WHERE m.thread = :thread AND m.sequenceNumber > :minSequenceNumber " +
           "ORDER BY m.sequenceNumber ASC")
    List<OpenDaimonMessage> findByThreadAndSequenceNumberGreaterThanOrderBySequenceNumberAsc(
            @Param("thread") ConversationThread thread,
            @Param("minSequenceNumber") Integer minSequenceNumber);
    
    /**
     * Finds all messages for thread with given role, sorted by sequence number.
     */
    @Query("SELECT m FROM Message m WHERE m.thread = :thread AND m.role = :role " +
           "ORDER BY m.sequenceNumber ASC")
    List<OpenDaimonMessage> findByThreadAndRoleOrderBySequenceNumberAsc(
            @Param("thread") ConversationThread thread,
            @Param("role") MessageRole role);
    
    /**
     * Counts messages in thread.
     */
    @Query("SELECT COUNT(m) FROM Message m WHERE m.thread = :thread")
    Integer countByThread(@Param("thread") ConversationThread thread);
    
    /**
     * Counts messages in thread with given role.
     */
    @Query("SELECT COUNT(m) FROM Message m WHERE m.thread = :thread AND m.role = :role")
    Integer countByThreadAndRole(
            @Param("thread") ConversationThread thread,
            @Param("role") MessageRole role);
    
    /**
     * Finds last message in thread (max sequence_number).
     */
    @Query("SELECT m FROM Message m WHERE m.thread = :thread " +
           "ORDER BY m.sequenceNumber DESC")
    List<OpenDaimonMessage> findByThreadOrderBySequenceNumberDesc(@Param("thread") ConversationThread thread);
    
    /**
     * Finds last message in thread (max sequence_number).
     * Uses Top1 to get first result.
     */
    default Optional<OpenDaimonMessage> findLastByThread(ConversationThread thread) {
        List<OpenDaimonMessage> messages = findByThreadOrderBySequenceNumberDesc(thread);
        return messages.isEmpty() ? Optional.empty() : Optional.of(messages.get(0));
    }
    
    /**
     * Finds all user messages, sorted by creation date.
     */
    @Query("SELECT m FROM Message m WHERE m.user = :user " +
           "ORDER BY m.createdAt DESC")
    List<OpenDaimonMessage> findByUserOrderByCreatedAtDesc(@Param("user") User user);
    
    /**
     * Finds all user messages in given thread.
     */
    @Query("SELECT m FROM Message m WHERE m.user = :user AND m.thread = :thread " +
           "ORDER BY m.sequenceNumber ASC")
    List<OpenDaimonMessage> findByUserAndThreadOrderBySequenceNumberAsc(
            @Param("user") User user,
            @Param("thread") ConversationThread thread);
    
}

