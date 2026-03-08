package io.github.ngirchev.aibot.telegram.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import io.github.ngirchev.aibot.common.model.AbstractEntity;

import java.time.OffsetDateTime;

@Entity
@Table(name = "telegram_whitelist")
@Getter
@Setter
@NoArgsConstructor
public class TelegramWhitelist extends AbstractEntity<Long> {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", referencedColumnName = "id")
    private TelegramUser user;
    
    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
    
    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now();
    }
} 