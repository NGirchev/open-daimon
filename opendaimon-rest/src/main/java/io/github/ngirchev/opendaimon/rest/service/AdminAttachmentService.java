package io.github.ngirchev.opendaimon.rest.service;

import io.github.ngirchev.opendaimon.common.model.OpenDaimonMessage;
import io.github.ngirchev.opendaimon.common.repository.OpenDaimonMessageRepository;
import io.github.ngirchev.opendaimon.common.storage.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Attachment proxy for the admin panel.
 * Before streaming bytes from MinIO, verifies the requested storageKey is present
 * in the specified message's attachments JSONB — prevents the endpoint from
 * turning into an arbitrary MinIO key fetcher.
 */
@Slf4j
@RequiredArgsConstructor
public class AdminAttachmentService {

    private static final String ATTACH_KEY_STORAGE = "storageKey";
    private static final String ATTACH_KEY_MIME = "mimeType";
    private static final String ATTACH_KEY_FILENAME = "filename";
    private static final String DEFAULT_MIME = "application/octet-stream";

    private final OpenDaimonMessageRepository messageRepository;
    private final FileStorageService fileStorageService;

    public record ResolvedAttachment(byte[] data, String mimeType, String filename) {
    }

    @Transactional(readOnly = true)
    public Optional<ResolvedAttachment> resolve(Long messageId, String storageKey) {
        if (messageId == null || storageKey == null || storageKey.isBlank()) {
            return Optional.empty();
        }
        Optional<OpenDaimonMessage> messageOpt = messageRepository.findById(messageId);
        if (messageOpt.isEmpty()) {
            log.warn("Admin requested attachment for unknown messageId={}", messageId);
            return Optional.empty();
        }
        Optional<Map<String, Object>> entryOpt = findEntry(messageOpt.get(), storageKey);
        if (entryOpt.isEmpty()) {
            log.warn("Admin requested storageKey={} not present on messageId={}", storageKey, messageId);
            return Optional.empty();
        }
        byte[] bytes;
        try {
            bytes = fileStorageService.get(storageKey);
        } catch (RuntimeException e) {
            log.warn("Failed to fetch attachment from storage: {}", storageKey, e);
            return Optional.empty();
        }
        Map<String, Object> entry = entryOpt.get();
        return Optional.of(new ResolvedAttachment(
                bytes,
                asString(entry.get(ATTACH_KEY_MIME), DEFAULT_MIME),
                asString(entry.get(ATTACH_KEY_FILENAME), storageKey)
        ));
    }

    private Optional<Map<String, Object>> findEntry(OpenDaimonMessage message, String storageKey) {
        List<Map<String, Object>> attachments = message.getAttachments();
        if (attachments == null) {
            return Optional.empty();
        }
        for (Map<String, Object> entry : attachments) {
            if (storageKey.equals(asString(entry.get(ATTACH_KEY_STORAGE), null))) {
                return Optional.of(entry);
            }
        }
        return Optional.empty();
    }

    private String asString(Object v, String fallback) {
        return v != null ? v.toString() : fallback;
    }
}
