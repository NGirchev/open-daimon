package io.github.ngirchev.aibot.common.storage.model;

import java.time.OffsetDateTime;

/**
 * File metadata in storage.
 *
 * @param key unique file key in storage
 * @param mimeType file MIME type (image/png, application/pdf, etc.)
 * @param originalName original file name
 * @param size file size in bytes
 * @param uploadedAt file upload time
 */
public record FileMetadata(
    String key,
    String mimeType,
    String originalName,
    long size,
    OffsetDateTime uploadedAt
) {}
