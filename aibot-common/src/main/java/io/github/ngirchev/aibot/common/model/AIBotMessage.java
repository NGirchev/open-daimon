package io.github.ngirchev.aibot.common.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Entity for storing messages in a dialog.
 * Combines UserRequest and ServiceResponse functionality.
 * Aligns with Spring AI Message concept.
 * Uses JPA Inheritance SINGLE_TABLE for modular subclasses (if needed in future).
 */
@Entity(name = "Message")
@Table(name = "message", indexes = {
        @Index(name = "idx_message_user_id", columnList = "user_id"),
            @Index(name = "idx_message_thread_id", columnList = "thread_id"),
            @Index(name = "idx_message_role", columnList = "role"),
            @Index(name = "idx_message_sequence", columnList = "thread_id, sequence_number"),
            @Index(name = "idx_message_created_at", columnList = "created_at")
})
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@DiscriminatorColumn(name = "message_type", discriminatorType = DiscriminatorType.STRING)
@DiscriminatorValue("MESSAGE")
@Getter
@Setter
@ToString(exclude = {"user", "thread", "assistantRole", "metadata", "responseData", "attachments"})
@NoArgsConstructor
public class AIBotMessage extends AbstractEntity<Long> {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    /**
     * User who sent/received the message
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;
    
    /**
     * Message role (USER, ASSISTANT, SYSTEM)
     */
    @Column(name = "role", nullable = false)
    @Enumerated(EnumType.STRING)
    private MessageRole role;
    
    /**
     * Message content
     */
    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;
    
    /**
     * Conversation thread this message belongs to
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "thread_id")
    private ConversationThread thread;
    
    /**
     * Sequence number of message in dialog (1, 2, 3, 4...)
     */
    @Column(name = "sequence_number")
    private Integer sequenceNumber;
    
    /**
     * Token count in message (optional, for accurate count)
     */
    @Column(name = "token_count")
    private Integer tokenCount;
    
    /**
     * Assistant role used for this message (for USER and ASSISTANT)
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assistant_role_id")
    private AssistantRole assistantRole;
    
    /**
     * Request type (for USER messages)
     */
    @Column(name = "request_type")
    @Enumerated(EnumType.STRING)
    private RequestType requestType;
    
    /**
     * AI service name that processed the request (for ASSISTANT messages)
     */
    @Column(name = "service_name")
    private String serviceName;
    
    /**
     * Processing status (for ASSISTANT messages)
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private ResponseStatus status;
    
    /**
     * Processing time in milliseconds (for ASSISTANT messages)
     */
    @Column(name = "processing_time_ms")
    private Integer processingTimeMs;
    
    /**
     * Error message (for ASSISTANT messages with error)
     */
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;
    
    /**
     * Additional response data in JSON format (for ASSISTANT messages)
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_data", columnDefinition = "jsonb")
    private Map<String, Object> responseData;
    
    /**
     * Message metadata in JSON format.
     * Holds type-specific fields for different user types:
     * - For Telegram: session_id (duplicated for query convenience)
     * - For REST: client_ip, user_agent, endpoint
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private Map<String, Object> metadata;
    
    /**
     * Attachment refs (files in MinIO) with expiry time.
     * Format: list of maps with keys storageKey, expiresAt (ISO-8601), mimeType, filename.
     * Used to load images into context before TTL expires.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "attachments", columnDefinition = "jsonb")
    private List<Map<String, Object>> attachments;
    
    /**
     * Message creation date
     */
    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
    
    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
        if (status == null && role == MessageRole.ASSISTANT) {
            status = ResponseStatus.PENDING;
        }
        // responseData and metadata stay null if not set explicitly
    }
}

