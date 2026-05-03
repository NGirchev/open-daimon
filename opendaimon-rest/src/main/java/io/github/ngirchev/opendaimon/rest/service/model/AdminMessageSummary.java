package io.github.ngirchev.opendaimon.rest.service.model;

import java.time.OffsetDateTime;

public record AdminMessageSummary(
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
