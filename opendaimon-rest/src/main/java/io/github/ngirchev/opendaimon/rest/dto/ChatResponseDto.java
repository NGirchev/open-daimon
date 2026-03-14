package io.github.ngirchev.opendaimon.rest.dto;

/**
 * DTO for chat request response
 */
public record ChatResponseDto<T>(T message, String sessionId) {
}

