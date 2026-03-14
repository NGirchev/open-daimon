package io.github.ngirchev.opendaimon.common.storage.service;

import io.github.ngirchev.opendaimon.common.storage.model.FileMetadata;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MinioFileStorageServiceTest {

    private static final String BUCKET = "test-bucket";
    private static final String KEY = "path/to/file.jpg";

    @Mock
    private MinioClient minioClient;

    private MinioFileStorageService service;

    @BeforeEach
    void setUp() {
        service = new MinioFileStorageService(minioClient, BUCKET);
    }

    @Test
    void save_putsObjectAndReturnsMetadata() throws Exception {
        byte[] data = new byte[]{1, 2, 3};
        String contentType = "image/jpeg";
        String originalName = "photo.jpg";

        FileMetadata result = service.save(KEY, data, contentType, originalName);

        assertNotNull(result);
        assertEquals(KEY, result.key());
        assertEquals(contentType, result.mimeType());
        assertEquals(originalName, result.originalName());
        assertEquals(3, result.size());
        assertNotNull(result.uploadedAt());

        ArgumentCaptor<PutObjectArgs> captor = ArgumentCaptor.forClass(PutObjectArgs.class);
        verify(minioClient).putObject(captor.capture());
        assertEquals(BUCKET, captor.getValue().bucket());
        assertEquals(KEY, captor.getValue().object());
    }

    @Test
    void save_whenMinioThrows_throwsRuntimeException() throws Exception {
        byte[] data = new byte[]{1};
        doThrow(new RuntimeException("MinIO error")).when(minioClient).putObject(any(PutObjectArgs.class));

        assertThrows(RuntimeException.class, () ->
                service.save(KEY, data, "image/png", "x.png"));
    }

    @Test
    void get_returnsBytesFromStream() throws Exception {
        byte[] expected = new byte[]{10, 20, 30};
        GetObjectResponse response = mock(GetObjectResponse.class);
        when(response.readAllBytes()).thenReturn(expected);
        when(minioClient.getObject(any(GetObjectArgs.class))).thenReturn(response);

        byte[] result = service.get(KEY);

        assertArrayEquals(expected, result);
    }

    @Test
    void get_whenNoSuchKey_throwsRuntimeException() throws Exception {
        ErrorResponseException err = mock(ErrorResponseException.class);
        var errorResponse = mock(io.minio.messages.ErrorResponse.class);
        when(errorResponse.code()).thenReturn("NoSuchKey");
        when(err.errorResponse()).thenReturn(errorResponse);
        when(minioClient.getObject(any(GetObjectArgs.class))).thenThrow(err);

        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.get(KEY));
        assertTrue(ex.getMessage().contains("File not found"));
    }

    @Test
    void delete_removesObject() throws Exception {
        service.delete(KEY);

        ArgumentCaptor<RemoveObjectArgs> captor = ArgumentCaptor.forClass(RemoveObjectArgs.class);
        verify(minioClient).removeObject(captor.capture());
        assertEquals(BUCKET, captor.getValue().bucket());
        assertEquals(KEY, captor.getValue().object());
    }

    @Test
    void delete_whenMinioThrows_throwsRuntimeException() throws Exception {
        doThrow(new RuntimeException("Delete failed")).when(minioClient).removeObject(any(RemoveObjectArgs.class));

        assertThrows(RuntimeException.class, () -> service.delete(KEY));
    }

    @Test
    void exists_whenObjectExists_returnsTrue() throws Exception {
        when(minioClient.statObject(any(StatObjectArgs.class))).thenReturn(null);

        assertTrue(service.exists(KEY));
    }

    @Test
    void exists_whenNoSuchKey_returnsFalse() throws Exception {
        ErrorResponseException err = mock(ErrorResponseException.class);
        var errorResponse = mock(io.minio.messages.ErrorResponse.class);
        when(errorResponse.code()).thenReturn("NoSuchKey");
        when(err.errorResponse()).thenReturn(errorResponse);
        when(minioClient.statObject(any(StatObjectArgs.class))).thenThrow(err);

        assertFalse(service.exists(KEY));
    }

    @Test
    void exists_whenOtherError_throwsRuntimeException() throws Exception {
        when(minioClient.statObject(any(StatObjectArgs.class)))
                .thenThrow(new RuntimeException("Connection failed"));

        assertThrows(RuntimeException.class, () -> service.exists(KEY));
    }
}
