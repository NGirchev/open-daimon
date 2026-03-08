package io.github.ngirchev.aibot.common.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Entity for conversation threads (AI conversations).
 * Groups user requests into a logical conversation with message history.
 */
@Entity
@Table(name = "conversation_thread", indexes = {
        @Index(name = "idx_conversation_thread_user_id", columnList = "user_id"),
        @Index(name = "idx_conversation_thread_thread_key", columnList = "thread_key"),
        @Index(name = "idx_conversation_thread_is_active", columnList = "is_active"),
        @Index(name = "idx_conversation_thread_last_activity", columnList = "last_activity_at"),
        @Index(name = "idx_conversation_thread_user_active", columnList = "user_id, is_active, last_activity_at")
})
@Getter
@Setter
@ToString(exclude = "user")
@NoArgsConstructor
public class ConversationThread extends AbstractEntity<Long> {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    /**
     * User who owns this thread.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;
    
    /**
     * Unique thread key (UUID).
     */
    @Column(name = "thread_key", nullable = false, unique = true)
    private String threadKey;
    
    /**
     * Optional conversation topic title.
     */
    @Column(name = "title", length = 500)
    private String title;
    
    /**
     * Brief dialog summary (1-2 paragraphs).
     */
    @Column(name = "summary", columnDefinition = "TEXT")
    private String summary;
    
    /**
     * List of key facts from dialog (memory bullets).
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "memory_bullets", columnDefinition = "jsonb")
    private List<String> memoryBullets = new ArrayList<>();
    
    /**
     * Total message count in thread.
     */
    @Column(name = "total_messages")
    private Integer totalMessages = 0;
    
    /**
     * Message count at last summarization.
     * Used to track new messages after summarization.
     * NULL means summarization has not run yet.
     */
    @Column(name = "messages_at_last_summarization")
    private Integer messagesAtLastSummarization;
    
    /**
     * Total token count (approximate).
     */
    @Column(name = "total_tokens")
    private Long totalTokens = 0L;
    
    /**
     * Whether thread is active.
     */
    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;
    
    /**
     * Last activity date in thread.
     */
    @Column(name = "last_activity_at", nullable = false)
    private OffsetDateTime lastActivityAt;
    
    /**
     * Thread creation date.
     */
    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
    
    /**
     * Thread close date.
     */
    @Column(name = "closed_at")
    private OffsetDateTime closedAt;
    
    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now();
        lastActivityAt = OffsetDateTime.now();
        if (isActive == null) {
            isActive = true;
        }
        if (totalMessages == null) {
            totalMessages = 0;
        }
        if (totalTokens == null) {
            totalTokens = 0L;
        }
        if (memoryBullets == null) {
            memoryBullets = new ArrayList<>();
        }
    }
    
    @PreUpdate
    protected void onUpdate() {
        lastActivityAt = OffsetDateTime.now();
    }
}

