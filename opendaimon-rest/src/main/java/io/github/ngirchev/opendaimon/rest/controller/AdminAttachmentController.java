package io.github.ngirchev.opendaimon.rest.controller;

import io.github.ngirchev.opendaimon.rest.service.AdminAttachmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/**
 * Streams attachment bytes from MinIO via FileStorageService, with ownership check
 * against the source message's attachments JSONB.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/messages")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
@Tag(name = "Admin Attachment Controller", description = "Binary proxy for message attachments")
public class AdminAttachmentController {

    private final AdminAttachmentService adminAttachmentService;

    @GetMapping("/{messageId}/attachment")
    @Operation(summary = "Download message attachment by storage key")
    public ResponseEntity<byte[]> download(
            @PathVariable Long messageId,
            @RequestParam("key") String storageKey) {
        Optional<AdminAttachmentService.ResolvedAttachment> resolved =
                adminAttachmentService.resolve(messageId, storageKey);
        if (resolved.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        AdminAttachmentService.ResolvedAttachment a = resolved.get();
        MediaType mediaType;
        try {
            mediaType = MediaType.parseMediaType(a.mimeType());
        } catch (Exception e) {
            mediaType = MediaType.APPLICATION_OCTET_STREAM;
        }
        return ResponseEntity.ok()
                .contentType(mediaType)
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(1)).cachePrivate())
                .header(HttpHeaders.CONTENT_DISPOSITION, inlineDisposition(a.filename()))
                .body(a.data());
    }

    private String inlineDisposition(String filename) {
        String safe = filename == null ? "attachment" : filename.replace("\"", "");
        String encoded = java.net.URLEncoder.encode(safe, StandardCharsets.UTF_8).replace("+", "%20");
        return "inline; filename=\"" + safe + "\"; filename*=UTF-8''" + encoded;
    }
}
