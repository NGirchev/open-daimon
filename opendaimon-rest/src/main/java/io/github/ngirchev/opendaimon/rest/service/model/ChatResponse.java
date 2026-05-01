package io.github.ngirchev.opendaimon.rest.service.model;

public record ChatResponse<T>(T message, String sessionId) {
}
