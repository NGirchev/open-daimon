package io.github.ngirchev.aibot.common.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import io.github.ngirchev.aibot.bulkhead.service.IUserObject;

import java.time.OffsetDateTime;

@Entity
@Table(name = "\"user\"")
@Inheritance(strategy = InheritanceType.JOINED)
@DiscriminatorColumn(name = "user_type", discriminatorType = DiscriminatorType.STRING)
@Getter
@Setter
@ToString
@NoArgsConstructor
public class User extends AbstractEntity<Long> implements IUserObject {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "username", unique = true)
    private String username;
    
    @Column(name = "first_name")
    private String firstName;
    
    @Column(name = "last_name")
    private String lastName;
    
    @Column(name = "language_code")
    private String languageCode;
    
    @Column(name = "phone", unique = true)
    private String phone;
    
    @Column(name = "is_premium")
    private Boolean isPremium;
    
    @Column(name = "created_at")
    private OffsetDateTime createdAt;
    
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;
    
    @Column(name = "last_activity_at")
    private OffsetDateTime lastActivityAt;

    @Column(name = "is_blocked")
    private Boolean isBlocked;
    
    @Column(name = "is_admin")
    private Boolean isAdmin;
    
    /**
     * Current active assistant role
     */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "current_assistant_role_id")
    private AssistantRole currentAssistantRole;
    
    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now();
        updatedAt = OffsetDateTime.now();
        lastActivityAt = OffsetDateTime.now();
        isBlocked = false;
        isPremium = false;
        isAdmin = false;
    }
    
    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now();
        lastActivityAt = OffsetDateTime.now();
    }
} 