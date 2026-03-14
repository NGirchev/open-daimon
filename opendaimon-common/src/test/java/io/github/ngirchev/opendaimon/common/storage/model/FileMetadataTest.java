package io.github.ngirchev.opendaimon.common.storage.model;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class FileMetadataTest {

    @Test
    void record_holdsAllFields() {
        OffsetDateTime now = OffsetDateTime.now();
        FileMetadata meta = new FileMetadata("key1", "image/png", "photo.png", 100L, now);

        assertEquals("key1", meta.key());
        assertEquals("image/png", meta.mimeType());
        assertEquals("photo.png", meta.originalName());
        assertEquals(100L, meta.size());
        assertEquals(now, meta.uploadedAt());
    }

    @Test
    void record_equalsAndHashCode() {
        OffsetDateTime now = OffsetDateTime.now();
        FileMetadata a = new FileMetadata("k", "t", "n", 1, now);
        FileMetadata b = new FileMetadata("k", "t", "n", 1, now);
        assertNotNull(a.toString());
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }
}
