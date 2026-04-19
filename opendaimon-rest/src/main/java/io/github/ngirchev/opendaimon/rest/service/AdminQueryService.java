package io.github.ngirchev.opendaimon.rest.service;

import io.github.ngirchev.opendaimon.common.model.ConversationThread;
import io.github.ngirchev.opendaimon.common.model.OpenDaimonMessage;
import io.github.ngirchev.opendaimon.common.model.ThreadScopeKind;
import io.github.ngirchev.opendaimon.common.model.User;
import io.github.ngirchev.opendaimon.common.repository.OpenDaimonMessageRepository;
import io.github.ngirchev.opendaimon.rest.dto.admin.AttachmentRefDto;
import io.github.ngirchev.opendaimon.rest.dto.admin.ConversationSummaryDto;
import io.github.ngirchev.opendaimon.rest.dto.admin.MessageDetailDto;
import io.github.ngirchev.opendaimon.rest.dto.admin.MessageSummaryDto;
import io.github.ngirchev.opendaimon.rest.dto.admin.PageResponseDto;
import io.github.ngirchev.opendaimon.rest.dto.admin.UserSummaryDto;
import io.github.ngirchev.opendaimon.rest.exception.UnauthorizedException;
import io.github.ngirchev.opendaimon.rest.model.RestUser;
import io.github.ngirchev.opendaimon.rest.repository.AdminConversationRepository;
import io.github.ngirchev.opendaimon.rest.repository.AdminUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Read-only admin service: assembles paginated conversation/message/user views.
 * Does not mutate any entity.
 */
@Slf4j
@RequiredArgsConstructor
public class AdminQueryService {

    private static final int CONTENT_PREVIEW_LIMIT = 200;
    private static final String ATTACH_KEY_STORAGE = "storageKey";
    private static final String ATTACH_KEY_MIME = "mimeType";
    private static final String ATTACH_KEY_FILENAME = "filename";
    private static final String ATTACH_KEY_EXPIRES = "expiresAt";

    private final AdminConversationRepository adminConversationRepository;
    private final AdminUserRepository adminUserRepository;
    private final OpenDaimonMessageRepository messageRepository;

    @Transactional(readOnly = true)
    public PageResponseDto<ConversationSummaryDto> listConversations(
            Long userId, ThreadScopeKind scopeKind, Boolean isActive, Pageable pageable) {
        Page<ConversationThread> page = adminConversationRepository
                .findAllWithFilters(userId, scopeKind, isActive, pageable);
        return PageResponseDto.from(page.map(this::toConversationSummary));
    }

    @Transactional(readOnly = true)
    public ConversationSummaryDto getConversation(Long threadId) {
        ConversationThread thread = adminConversationRepository.findByIdWithUser(threadId)
                .orElseThrow(() -> new UnauthorizedException("Conversation not found: " + threadId));
        return toConversationSummary(thread);
    }

    @Transactional(readOnly = true)
    public List<MessageSummaryDto> listMessages(Long threadId) {
        ConversationThread thread = adminConversationRepository.findByIdWithUser(threadId)
                .orElseThrow(() -> new UnauthorizedException("Conversation not found: " + threadId));
        List<OpenDaimonMessage> messages = messageRepository.findByThreadOrderBySequenceNumberAsc(thread);
        List<MessageSummaryDto> result = new ArrayList<>(messages.size());
        for (OpenDaimonMessage m : messages) {
            result.add(toMessageSummary(m));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public MessageDetailDto getMessage(Long messageId) {
        OpenDaimonMessage message = messageRepository.findById(messageId)
                .orElseThrow(() -> new UnauthorizedException("Message not found: " + messageId));
        return toMessageDetail(message);
    }

    @Transactional(readOnly = true)
    public PageResponseDto<UserSummaryDto> listUsers(String search, Pageable pageable) {
        Page<User> page = adminUserRepository.searchAll(search, pageable);
        return PageResponseDto.from(page.map(this::toUserSummary));
    }

    private ConversationSummaryDto toConversationSummary(ConversationThread t) {
        return new ConversationSummaryDto(
                t.getId(),
                t.getThreadKey(),
                t.getTitle(),
                t.getScopeKind() != null ? t.getScopeKind().name() : null,
                t.getScopeId(),
                t.getTotalMessages(),
                t.getTotalTokens(),
                t.getIsActive(),
                t.getLastActivityAt(),
                t.getCreatedAt(),
                toUserSummary(t.getUser())
        );
    }

    private MessageSummaryDto toMessageSummary(OpenDaimonMessage m) {
        return new MessageSummaryDto(
                m.getId(),
                m.getSequenceNumber(),
                m.getRole() != null ? m.getRole().name() : null,
                m.getRequestType() != null ? m.getRequestType().name() : null,
                m.getStatus() != null ? m.getStatus().name() : null,
                preview(m.getContent()),
                m.getAttachments() != null ? m.getAttachments().size() : 0,
                m.getCreatedAt()
        );
    }

    private MessageDetailDto toMessageDetail(OpenDaimonMessage m) {
        return new MessageDetailDto(
                m.getId(),
                m.getThread() != null ? m.getThread().getId() : null,
                m.getSequenceNumber(),
                m.getRole() != null ? m.getRole().name() : null,
                m.getContent(),
                m.getRequestType() != null ? m.getRequestType().name() : null,
                m.getStatus() != null ? m.getStatus().name() : null,
                m.getServiceName(),
                m.getTokenCount(),
                m.getProcessingTimeMs(),
                m.getErrorMessage(),
                m.getTelegramMessageId(),
                m.getCreatedAt(),
                toAttachmentRefs(m.getAttachments()),
                m.getMetadata(),
                m.getResponseData(),
                toUserSummary(m.getUser())
        );
    }

    private UserSummaryDto toUserSummary(User user) {
        if (user == null) {
            return null;
        }
        String discriminator = resolveUserType(user);
        String identity = resolveIdentity(user);
        return new UserSummaryDto(
                user.getId(),
                discriminator,
                user.getUsername(),
                user.getFirstName(),
                user.getLastName(),
                identity,
                user.getIsAdmin(),
                user.getIsBlocked()
        );
    }

    private static final String TELEGRAM_USER_CLASS = "TelegramUser";
    private static final String TELEGRAM_ID_GETTER = "getTelegramId";

    private String resolveUserType(User user) {
        if (user instanceof RestUser) {
            return "REST";
        }
        if (TELEGRAM_USER_CLASS.equals(user.getClass().getSimpleName())) {
            return "TELEGRAM";
        }
        return "USER";
    }

    private String resolveIdentity(User user) {
        if (user instanceof RestUser ru) {
            return ru.getEmail();
        }
        if (TELEGRAM_USER_CLASS.equals(user.getClass().getSimpleName())) {
            return invokeTelegramId(user);
        }
        return null;
    }

    private String invokeTelegramId(User user) {
        try {
            Object v = user.getClass().getMethod(TELEGRAM_ID_GETTER).invoke(user);
            return v != null ? v.toString() : null;
        } catch (ReflectiveOperationException e) {
            log.debug("Failed to reflect TelegramUser.getTelegramId on {}", user.getClass(), e);
            return null;
        }
    }

    private List<AttachmentRefDto> toAttachmentRefs(List<Map<String, Object>> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<AttachmentRefDto> refs = new ArrayList<>(raw.size());
        for (Map<String, Object> entry : raw) {
            String storageKey = asString(entry.get(ATTACH_KEY_STORAGE));
            if (storageKey == null) {
                continue;
            }
            refs.add(new AttachmentRefDto(
                    storageKey,
                    asString(entry.get(ATTACH_KEY_MIME)),
                    asString(entry.get(ATTACH_KEY_FILENAME)),
                    parseExpiry(entry.get(ATTACH_KEY_EXPIRES))
            ));
        }
        return refs;
    }

    private String asString(Object v) {
        return v != null ? v.toString() : null;
    }

    private OffsetDateTime parseExpiry(Object v) {
        if (v == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(v.toString());
        } catch (Exception e) {
            log.debug("Failed to parse attachment expiresAt value: {}", v);
            return null;
        }
    }

    private String preview(String content) {
        if (content == null) {
            return null;
        }
        if (content.length() <= CONTENT_PREVIEW_LIMIT) {
            return content;
        }
        return content.substring(0, CONTENT_PREVIEW_LIMIT) + "…";
    }
}
