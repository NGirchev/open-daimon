package io.github.ngirchev.opendaimon.rest.service.model;

import java.time.OffsetDateTime;

public record ChatSession(String sessionId, String name, OffsetDateTime createdAt) {
}
