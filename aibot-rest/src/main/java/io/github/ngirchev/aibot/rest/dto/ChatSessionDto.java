package io.github.ngirchev.aibot.rest.dto;

import java.time.OffsetDateTime;

/**
 * DTO for chat session
 */
public record ChatSessionDto(String sessionId, String name, OffsetDateTime createdAt) {
}

