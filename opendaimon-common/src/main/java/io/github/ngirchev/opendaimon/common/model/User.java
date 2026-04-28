package io.github.ngirchev.opendaimon.common.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import io.github.ngirchev.opendaimon.bulkhead.service.IUserObject;

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
    
    @Column(name = "preferred_model_id")
    private String preferredModelId;

    /**
     * Per-user agent mode flag. {@code null} means "use application default"
     * ({@code open-daimon.agent.enabled}). Set to {@code true}/{@code false}
     * explicitly via the {@code /mode} Telegram command.
     */
    @Column(name = "agent_mode_enabled")
    private Boolean agentModeEnabled;

    /**
     * Per-user thinking-visibility mode. Controls how the model's reasoning is rendered
     * during and after streaming in the Telegram status transcript.
     * Set explicitly via the {@code /thinking} Telegram command.
     *
     * @see ThinkingMode
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "thinking_mode", nullable = false)
    private ThinkingMode thinkingMode = ThinkingMode.HIDE_REASONING;

    /**
     * Current active assistant role
     */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "current_assistant_role_id")
    private AssistantRole currentAssistantRole;
} 