package io.github.ngirchev.opendaimon.rest.service.model;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public record AdminMessageDetail(
        Long id,
        Long threadId,
        Integer sequenceNumber,
        String role,
        String content,
        String requestType,
        String status,
        String serviceName,
        Integer tokenCount,
        Integer processingTimeMs,
        String errorMessage,
        Long telegramMessageId,
        OffsetDateTime createdAt,
        List<AdminAttachmentRef> attachments,
        Map<String, Object> metadata,
        Map<String, Object> responseData,
        AdminUserSummary user
) {
}
