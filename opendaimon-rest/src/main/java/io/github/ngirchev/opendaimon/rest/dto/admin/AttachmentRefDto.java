package io.github.ngirchev.opendaimon.rest.dto.admin;

import java.time.OffsetDateTime;

/**
 * Attachment reference exposed to the admin UI.
 * Mirrors the JSONB entry on {@code message.attachments} but hides internal-only keys.
 */
public record AttachmentRefDto(
        String storageKey,
        String mimeType,
        String filename,
        OffsetDateTime expiresAt
) {
}
