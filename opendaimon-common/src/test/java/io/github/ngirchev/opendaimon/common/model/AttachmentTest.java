package io.github.ngirchev.opendaimon.common.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AttachmentTest {

    @Test
    void isImage_returnsTrueForImageType() {
        Attachment att = new Attachment("k", "image/png", "x.png", 1, AttachmentType.IMAGE, new byte[]{});
        assertTrue(att.isImage());
    }

    @Test
    void isImage_returnsFalseForPdfType() {
        Attachment att = new Attachment("k", "application/pdf", "x.pdf", 1, AttachmentType.PDF, new byte[]{});
        assertFalse(att.isImage());
    }

    @Test
    void isPdf_returnsTrueWhenTypePdfAndMimeContainsPdf() {
        Attachment att = new Attachment("k", "application/pdf", "x.pdf", 1, AttachmentType.PDF, new byte[]{});
        assertTrue(att.isPdf());
    }

    @Test
    void isPdf_returnsFalseWhenMimeNull() {
        Attachment att = new Attachment("k", null, "x.pdf", 1, AttachmentType.PDF, new byte[]{});
        assertFalse(att.isPdf());
    }

    @Test
    void isDocument_returnsTrueForPdfMimeType() {
        Attachment att = new Attachment("k", "application/pdf", "x.pdf", 1, AttachmentType.PDF, new byte[]{});
        assertTrue(att.isDocument());
    }

    @Test
    void isDocument_returnsTrueForDocxByExtension() {
        Attachment att = new Attachment("k", null, "doc.docx", 1, AttachmentType.PDF, new byte[]{});
        assertTrue(att.isDocument());
    }

    @Test
    void isDocument_returnsFalseWhenMimeAndFilenameNull() {
        Attachment att = new Attachment("k", null, null, 1, AttachmentType.IMAGE, new byte[]{});
        assertFalse(att.isDocument());
    }

    @Test
    void equals_andHashCode() {
        Attachment a = new Attachment("k", "image/png", "n", 10, AttachmentType.IMAGE, new byte[]{1});
        Attachment b = new Attachment("k", "image/png", "n", 10, AttachmentType.IMAGE, new byte[]{2});
        Attachment c = new Attachment("k2", "image/png", "n", 10, AttachmentType.IMAGE, new byte[]{1});

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
        assertNotEquals(a, null);
        assertEquals(a, a);
    }

    @Test
    void toString_containsKeyAndType() {
        Attachment att = new Attachment("myKey", "image/jpeg", "f.jpg", 5, AttachmentType.IMAGE, new byte[]{});
        String s = att.toString();
        assertTrue(s.contains("myKey"));
        assertTrue(s.contains("IMAGE"));
    }
}
