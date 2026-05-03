package io.github.ngirchev.opendaimon.rest.service.model;

import java.time.OffsetDateTime;

public record AdminConversationSummary(
        Long id,
        String threadKey,
        String title,
        String scopeKind,
        Long scopeId,
        Integer totalMessages,
        Long totalTokens,
        Boolean isActive,
        OffsetDateTime lastActivityAt,
        OffsetDateTime createdAt,
        AdminUserSummary user
) {
}
