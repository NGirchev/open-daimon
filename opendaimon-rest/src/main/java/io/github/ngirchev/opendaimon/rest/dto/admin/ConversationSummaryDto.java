package io.github.ngirchev.opendaimon.rest.dto.admin;

import java.time.OffsetDateTime;

/**
 * Admin list row for a ConversationThread.
 * Lightweight: includes owner user summary, counters, timestamps.
 */
public record ConversationSummaryDto(
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
        UserSummaryDto user
) {
}
