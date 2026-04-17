package io.github.ngirchev.opendaimon.rest.dto.admin;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Full message payload for the admin drill-down view.
 * Includes raw JSONB metadata/responseData for diagnostics.
 */
public record MessageDetailDto(
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
        List<AttachmentRefDto> attachments,
        Map<String, Object> metadata,
        Map<String, Object> responseData,
        UserSummaryDto user
) {
}
