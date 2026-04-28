package io.github.ngirchev.opendaimon.common.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.OffsetDateTime;

/**
 * Recent AI model picked explicitly by a user via the Telegram {@code /model} menu.
 * One row per (user, modelName) pair; upsert semantics enforced by the unique
 * constraint on (user_id, model_name). The history is pruned write-side to the
 * top-N entries ordered by {@link #lastUsedAt} descending.
 */
@Entity
@Table(
        name = "user_recent_model",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_user_recent_model",
                columnNames = {"user_id", "model_name"}),
        indexes = @Index(
                name = "idx_user_recent_model_user_lastused",
                columnList = "user_id, last_used_at DESC")
)
@Getter
@Setter
@ToString(exclude = "user")
@NoArgsConstructor
public class UserRecentModel extends AbstractEntity<Long> {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Owner of the recent-model entry.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /**
     * Model identifier as returned by the gateway (matches {@code ModelInfo.name()}).
     */
    @Column(name = "model_name", nullable = false, length = 255)
    private String modelName;

    /**
     * Timestamp of the most recent explicit pick. Updated on every insert/update
     * via {@link #onPersist()} / {@link #onUpdate()}.
     */
    @Column(name = "last_used_at", nullable = false)
    private OffsetDateTime lastUsedAt;

    @PrePersist
    protected void onPersist() {
        if (lastUsedAt == null) {
            lastUsedAt = OffsetDateTime.now();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        lastUsedAt = OffsetDateTime.now();
    }
}
