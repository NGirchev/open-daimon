package io.github.ngirchev.aibot.common.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.OffsetDateTime;

/**
 * Entity for assistant roles with versioning.
 * Each user can have multiple role versions, one of which is active (current).
 */
@Entity
@Table(name = "assistant_role", indexes = {
        @Index(name = "idx_assistant_role_user_id", columnList = "user_id"),
        @Index(name = "idx_assistant_role_user_active", columnList = "user_id, is_active"),
        @Index(name = "idx_assistant_role_content_hash", columnList = "content_hash")
})
@Getter
@Setter
@ToString(exclude = "user")
@NoArgsConstructor
public class AssistantRole extends AbstractEntity<Long> {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    /**
     * User who owns this role.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;
    
    /**
     * Role content (system prompt).
     */
    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;
    
    /**
     * Content hash for fast duplicate lookup.
     */
    @Column(name = "content_hash", nullable = false)
    private String contentHash;
    
    /**
     * Role version for this user.
     */
    @Column(name = "version", nullable = false)
    private Integer version;
    
    /**
     * Whether this is user's active (current) role.
     */
    @Column(name = "is_active", nullable = false)
    private Boolean isActive;
    
    /**
     * Role creation date.
     */
    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
    
    /**
     * Last role usage date.
     */
    @Column(name = "last_used_at")
    private OffsetDateTime lastUsedAt;
    
    /**
     * Number of requests using this role.
     */
    @Column(name = "usage_count", nullable = false)
    private Long usageCount;
    
    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now();
        lastUsedAt = OffsetDateTime.now();
        if (usageCount == null) {
            usageCount = 0L;
        }
        if (isActive == null) {
            isActive = false;
        }
        // Compute content hash
        if (content != null && contentHash == null) {
            contentHash = String.valueOf(content.hashCode());
        }
    }
    
    /**
     * Increments role usage counter.
     */
    public void incrementUsageCount() {
        this.usageCount++;
        this.lastUsedAt = OffsetDateTime.now();
    }
}

