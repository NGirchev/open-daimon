package io.github.ngirchev.aibot.rest.dto;

import java.util.List;

/**
 * DTO for session message history
 */
public record ChatHistoryResponseDto(String sessionId, List<ChatMessageDto> messages) {
}

