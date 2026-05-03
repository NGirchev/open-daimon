package io.github.ngirchev.opendaimon.rest.dto.admin;

import java.time.OffsetDateTime;

/**
 * Row in the message list of a conversation.
 * Content is truncated by the service for preview purposes.
 */
public record MessageSummaryDto(
        Long id,
        Integer sequenceNumber,
        String role,
        String requestType,
        String status,
        String contentPreview,
        int attachmentCount,
        OffsetDateTime createdAt
) {
}
