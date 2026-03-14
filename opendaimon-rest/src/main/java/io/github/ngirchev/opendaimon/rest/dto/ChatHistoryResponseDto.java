package io.github.ngirchev.opendaimon.rest.dto;

import java.util.List;

/**
 * DTO for session message history
 */
public record ChatHistoryResponseDto(String sessionId, List<ChatMessageDto> messages) {
}

