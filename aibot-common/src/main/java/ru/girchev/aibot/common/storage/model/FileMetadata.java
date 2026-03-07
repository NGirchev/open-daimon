package ru.girchev.aibot.common.storage.model;

import java.time.OffsetDateTime;

/**
 * Метаданные файла в хранилище.
 *
 * @param key уникальный ключ файла в хранилище
 * @param mimeType MIME тип файла (image/png, application/pdf и т.д.)
 * @param originalName оригинальное имя файла
 * @param size размер файла в байтах
 * @param uploadedAt время загрузки файла
 */
public record FileMetadata(
    String key,
    String mimeType,
    String originalName,
    long size,
    OffsetDateTime uploadedAt
) {}
