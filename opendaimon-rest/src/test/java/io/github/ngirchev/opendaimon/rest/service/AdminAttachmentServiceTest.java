package io.github.ngirchev.opendaimon.rest.service;

import io.github.ngirchev.opendaimon.common.model.OpenDaimonMessage;
import io.github.ngirchev.opendaimon.common.repository.OpenDaimonMessageRepository;
import io.github.ngirchev.opendaimon.common.storage.service.FileStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminAttachmentServiceTest {

    @Mock
    private OpenDaimonMessageRepository messageRepository;
    @Mock
    private FileStorageService fileStorageService;

    private AdminAttachmentService service;

    @BeforeEach
    void setUp() {
        service = new AdminAttachmentService(messageRepository, fileStorageService);
    }

    @Test
    void shouldReturnAttachmentWhenKeyBelongsToMessage() {
        OpenDaimonMessage message = messageWithAttachments(List.of(
                Map.of("storageKey", "abc123", "mimeType", "image/png", "filename", "pic.png")
        ));
        when(messageRepository.findById(42L)).thenReturn(Optional.of(message));
        when(fileStorageService.get("abc123")).thenReturn(new byte[]{1, 2, 3});

        Optional<AdminAttachmentService.ResolvedAttachment> resolved = service.resolve(42L, "abc123");

        assertThat(resolved).isPresent();
        assertThat(resolved.get().data()).containsExactly(1, 2, 3);
        assertThat(resolved.get().mimeType()).isEqualTo("image/png");
        assertThat(resolved.get().filename()).isEqualTo("pic.png");
    }

    @Test
    void shouldReturnEmptyWhenMessageMissing() {
        when(messageRepository.findById(42L)).thenReturn(Optional.empty());

        Optional<AdminAttachmentService.ResolvedAttachment> resolved = service.resolve(42L, "abc123");

        assertThat(resolved).isEmpty();
        verify(fileStorageService, never()).get(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void shouldReturnEmptyWhenStorageKeyNotInMessageAttachments() {
        OpenDaimonMessage message = messageWithAttachments(List.of(
                Map.of("storageKey", "other", "mimeType", "image/png")
        ));
        when(messageRepository.findById(42L)).thenReturn(Optional.of(message));

        Optional<AdminAttachmentService.ResolvedAttachment> resolved = service.resolve(42L, "abc123");

        assertThat(resolved).isEmpty();
        verify(fileStorageService, never()).get(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void shouldReturnEmptyWhenStorageKeyBlank() {
        Optional<AdminAttachmentService.ResolvedAttachment> resolved = service.resolve(42L, "");

        assertThat(resolved).isEmpty();
        verify(messageRepository, never()).findById(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void shouldReturnEmptyWhenMessageIdNull() {
        Optional<AdminAttachmentService.ResolvedAttachment> resolved = service.resolve(null, "abc");

        assertThat(resolved).isEmpty();
        verify(messageRepository, never()).findById(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void shouldReturnEmptyWhenStorageLookupFails() {
        OpenDaimonMessage message = messageWithAttachments(List.of(
                Map.of("storageKey", "abc123", "mimeType", "image/png")
        ));
        when(messageRepository.findById(42L)).thenReturn(Optional.of(message));
        when(fileStorageService.get("abc123")).thenThrow(new RuntimeException("minio down"));

        Optional<AdminAttachmentService.ResolvedAttachment> resolved = service.resolve(42L, "abc123");

        assertThat(resolved).isEmpty();
    }

    @Test
    void shouldFallbackDefaultMimeWhenMissing() {
        OpenDaimonMessage message = messageWithAttachments(List.of(
                Map.of("storageKey", "abc123")
        ));
        when(messageRepository.findById(42L)).thenReturn(Optional.of(message));
        when(fileStorageService.get("abc123")).thenReturn(new byte[]{});

        Optional<AdminAttachmentService.ResolvedAttachment> resolved = service.resolve(42L, "abc123");

        assertThat(resolved).isPresent();
        assertThat(resolved.get().mimeType()).isEqualTo("application/octet-stream");
        assertThat(resolved.get().filename()).isEqualTo("abc123");
    }

    private OpenDaimonMessage messageWithAttachments(List<Map<String, Object>> attachments) {
        OpenDaimonMessage message = new OpenDaimonMessage();
        message.setAttachments(attachments);
        return message;
    }
}
