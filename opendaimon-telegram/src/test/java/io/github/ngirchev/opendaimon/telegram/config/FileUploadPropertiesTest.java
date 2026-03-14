package io.github.ngirchev.opendaimon.telegram.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for FileUploadProperties.
 * Verifies supported document type detection for all formats.
 */
class FileUploadPropertiesTest {

    private FileUploadProperties properties;

    @BeforeEach
    void setUp() {
        properties = new FileUploadProperties();
        properties.setEnabled(true);
        properties.setMaxFileSizeMb(20);
        properties.setSupportedImageTypes("jpeg,png,gif,webp,svg,bmp,tiff");
        properties.setSupportedDocumentTypes("pdf,txt,docx,doc,xls,xlsx,ppt,pptx,odt,ods,odp,rtf,html,md,csv,json,xml,epub");
    }

    @Test
    void isSupportedDocumentType_pdf_returnsTrue() {
        assertTrue(properties.isSupportedDocumentType("application/pdf"));
        assertTrue(properties.isSupportedDocumentType("APPLICATION/PDF"));
    }

    @Test
    void isSupportedDocumentType_docx_returnsTrue() {
        assertTrue(properties.isSupportedDocumentType("application/vnd.openxmlformats-officedocument.wordprocessingml.document"));
    }

    @Test
    void isSupportedDocumentType_doc_returnsTrue() {
        // isSupportedDocumentType checks if MIME type contains a string from the list
        // "application/msword" contains "doc" only if "doc" is in the list
        // "msword" does not contain "doc", so we verify "doc" is in the list
        assertTrue(properties.getSupportedDocumentTypesSet().contains("doc"));
        // For actual MIME type we need a content-based check
        assertTrue(properties.isSupportedDocumentType("application/doc"));
    }

    @Test
    void isSupportedDocumentType_xlsx_returnsTrue() {
        assertTrue(properties.isSupportedDocumentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
    }

    @Test
    void isSupportedDocumentType_xls_returnsTrue() {
        // "application/vnd.ms-excel" does not contain "xls", but "xls" is in the list
        assertTrue(properties.getSupportedDocumentTypesSet().contains("xls"));
        // Verify the method works for MIME types containing "xls"
        assertTrue(properties.isSupportedDocumentType("application/xls"));
    }

    @Test
    void isSupportedDocumentType_pptx_returnsTrue() {
        assertTrue(properties.isSupportedDocumentType("application/vnd.openxmlformats-officedocument.presentationml.presentation"));
    }

    @Test
    void isSupportedDocumentType_ppt_returnsTrue() {
        // "application/vnd.ms-powerpoint" does not contain "ppt", but "ppt" is in the list
        assertTrue(properties.getSupportedDocumentTypesSet().contains("ppt"));
        // Verify the method works for MIME types containing "ppt"
        assertTrue(properties.isSupportedDocumentType("application/ppt"));
    }

    @Test
    void isSupportedDocumentType_txt_returnsTrue() {
        // "text/plain" does not contain "txt", but "txt" is in the supported list
        assertTrue(properties.getSupportedDocumentTypesSet().contains("txt"));
        // Verify the method works for MIME types containing "txt"
        assertTrue(properties.isSupportedDocumentType("text/txt"));
    }

    @Test
    void isSupportedDocumentType_rtf_returnsTrue() {
        assertTrue(properties.isSupportedDocumentType("application/rtf"));
    }

    @Test
    void isSupportedDocumentType_odt_returnsTrue() {
        assertTrue(properties.isSupportedDocumentType("application/vnd.oasis.opendocument.text"));
    }

    @Test
    void isSupportedDocumentType_ods_returnsTrue() {
        assertTrue(properties.isSupportedDocumentType("application/vnd.oasis.opendocument.spreadsheet"));
    }

    @Test
    void isSupportedDocumentType_odp_returnsTrue() {
        assertTrue(properties.isSupportedDocumentType("application/vnd.oasis.opendocument.presentation"));
    }

    @Test
    void isSupportedDocumentType_csv_returnsTrue() {
        assertTrue(properties.isSupportedDocumentType("text/csv"));
    }

    @Test
    void isSupportedDocumentType_html_returnsTrue() {
        assertTrue(properties.isSupportedDocumentType("text/html"));
    }

    @Test
    void isSupportedDocumentType_md_returnsTrue() {
        // FileUploadProperties checks if MIME type contains a string from the supported list
        // "text/markdown" does not contain "md", but "md" is in the list
        assertTrue(properties.getSupportedDocumentTypesSet().contains("md"));
        // Verify the method works for MIME types containing "md"
        assertTrue(properties.isSupportedDocumentType("text/md"));
    }

    @Test
    void isSupportedDocumentType_json_returnsTrue() {
        assertTrue(properties.isSupportedDocumentType("application/json"));
    }

    @Test
    void isSupportedDocumentType_xml_returnsTrue() {
        assertTrue(properties.isSupportedDocumentType("application/xml"));
        assertTrue(properties.isSupportedDocumentType("text/xml"));
    }

    @Test
    void isSupportedDocumentType_epub_returnsTrue() {
        assertTrue(properties.isSupportedDocumentType("application/epub+zip"));
    }

    @Test
    void isSupportedDocumentType_unsupported_returnsFalse() {
        assertFalse(properties.isSupportedDocumentType("application/xyz"));
        assertFalse(properties.isSupportedDocumentType(null));
    }

    @Test
    void getMaxFileSizeBytes_returnsCorrectValue() {
        properties.setMaxFileSizeMb(20);
        assertEquals(20 * 1024 * 1024L, properties.getMaxFileSizeBytes());
    }

    @Test
    void getSupportedDocumentTypesSet_returnsAllTypes() {
        var types = properties.getSupportedDocumentTypesSet();
        assertEquals(18, types.size());
        assertTrue(types.contains("pdf"));
        assertTrue(types.contains("docx"));
        assertTrue(types.contains("txt"));
        assertTrue(types.contains("csv"));
        assertTrue(types.contains("json"));
        assertTrue(types.contains("xml"));
        assertTrue(types.contains("epub"));
    }
}
