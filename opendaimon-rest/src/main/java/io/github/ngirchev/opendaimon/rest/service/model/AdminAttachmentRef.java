package io.github.ngirchev.opendaimon.rest.service.model;

import java.time.OffsetDateTime;

public record AdminAttachmentRef(
        String storageKey,
        String mimeType,
        String filename,
        OffsetDateTime expiresAt
) {
}
