package ru.girchev.aibot.common.model;

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
 * Entity для хранения сообщений в диалоге.
 * Объединяет функциональность UserRequest и ServiceResponse.
 * Соответствует Spring AI Message концепции.
 * Использует JPA Inheritance SINGLE_TABLE для поддержки модульных наследников (если понадобятся в будущем).
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
     * Пользователь, отправивший/получивший сообщение
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;
    
    /**
     * Роль сообщения (USER, ASSISTANT, SYSTEM)
     */
    @Column(name = "role", nullable = false)
    @Enumerated(EnumType.STRING)
    private MessageRole role;
    
    /**
     * Содержимое сообщения
     */
    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;
    
    /**
     * Conversation thread, к которому относится это сообщение
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "thread_id")
    private ConversationThread thread;
    
    /**
     * Порядковый номер сообщения в диалоге (1, 2, 3, 4...)
     */
    @Column(name = "sequence_number")
    private Integer sequenceNumber;
    
    /**
     * Количество токенов в сообщении (опционально, для точного подсчета)
     */
    @Column(name = "token_count")
    private Integer tokenCount;
    
    /**
     * Роль ассистента, использованная для данного сообщения (для USER и ASSISTANT)
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assistant_role_id")
    private AssistantRole assistantRole;
    
    /**
     * Тип запроса (для USER сообщений)
     */
    @Column(name = "request_type")
    @Enumerated(EnumType.STRING)
    private RequestType requestType;
    
    /**
     * Название сервиса AI, который обработал запрос (для ASSISTANT сообщений)
     */
    @Column(name = "service_name")
    private String serviceName;
    
    /**
     * Статус обработки (для ASSISTANT сообщений)
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private ResponseStatus status;
    
    /**
     * Время обработки в миллисекундах (для ASSISTANT сообщений)
     */
    @Column(name = "processing_time_ms")
    private Integer processingTimeMs;
    
    /**
     * Сообщение об ошибке (для ASSISTANT сообщений с ошибкой)
     */
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;
    
    /**
     * Дополнительные данные ответа в формате JSON (для ASSISTANT сообщений)
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_data", columnDefinition = "jsonb")
    private Map<String, Object> responseData;
    
    /**
     * Метаданные сообщения в формате JSON.
     * Хранит специфичные поля для разных типов пользователей:
     * - Для Telegram: session_id (дублируется для удобства запросов)
     * - Для REST: client_ip, user_agent, endpoint
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private Map<String, Object> metadata;
    
    /**
     * Ссылки на вложения (файлы в MinIO) с временем истечения.
     * Формат: список мап с ключами storageKey, expiresAt (ISO-8601), mimeType, filename.
     * Используется для подтягивания изображений в контекст до истечения TTL.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "attachments", columnDefinition = "jsonb")
    private List<Map<String, Object>> attachments;
    
    /**
     * Дата создания сообщения
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
        // responseData и metadata остаются null, если не установлены явно
    }
}

