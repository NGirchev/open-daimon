package io.github.ngirchev.aibot.telegram.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import io.github.ngirchev.aibot.common.model.AbstractEntity;

import java.time.OffsetDateTime;

@Entity
@Table(name = "telegram_user_session")
@Getter
@Setter
@ToString(exclude = {"telegramUser"})
@NoArgsConstructor
public class TelegramUserSession extends AbstractEntity<Long> {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "telegram_id", referencedColumnName = "id", nullable = false)
    private TelegramUser telegramUser;
    
    @Column(name = "session_id", nullable = false)
    private String sessionId;
    
    @Column(name = "created_at")
    private OffsetDateTime createdAt;
    
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;
    
    @Column(name = "expired_at")
    private OffsetDateTime expiredAt;
    
    @Column(name = "is_active")
    private Boolean isActive;
    
    @Column(name = "bot_status")
    private String botStatus;
    
    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now();
        updatedAt = OffsetDateTime.now();
        isActive = true;
    }
    
    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
} 