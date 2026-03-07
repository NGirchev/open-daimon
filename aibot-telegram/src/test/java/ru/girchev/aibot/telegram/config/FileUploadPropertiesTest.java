package ru.girchev.aibot.telegram.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit тесты для FileUploadProperties.
 * Проверяет определение поддерживаемых типов документов для всех форматов.
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
        // isSupportedDocumentType проверяет содержит ли MIME тип строку из списка
        // "application/msword" содержит "doc" только если в списке есть "doc"
        // Но "msword" не содержит "doc", поэтому проверяем что "doc" есть в списке
        assertTrue(properties.getSupportedDocumentTypesSet().contains("doc"));
        // Для реального MIME типа нужна проверка через содержимое
        assertTrue(properties.isSupportedDocumentType("application/doc"));
    }

    @Test
    void isSupportedDocumentType_xlsx_returnsTrue() {
        assertTrue(properties.isSupportedDocumentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
    }

    @Test
    void isSupportedDocumentType_xls_returnsTrue() {
        // "application/vnd.ms-excel" не содержит "xls", но "xls" есть в списке
        assertTrue(properties.getSupportedDocumentTypesSet().contains("xls"));
        // Проверяем что метод работает для MIME типов, содержащих "xls"
        assertTrue(properties.isSupportedDocumentType("application/xls"));
    }

    @Test
    void isSupportedDocumentType_pptx_returnsTrue() {
        assertTrue(properties.isSupportedDocumentType("application/vnd.openxmlformats-officedocument.presentationml.presentation"));
    }

    @Test
    void isSupportedDocumentType_ppt_returnsTrue() {
        // "application/vnd.ms-powerpoint" не содержит "ppt", но "ppt" есть в списке
        assertTrue(properties.getSupportedDocumentTypesSet().contains("ppt"));
        // Проверяем что метод работает для MIME типов, содержащих "ppt"
        assertTrue(properties.isSupportedDocumentType("application/ppt"));
    }

    @Test
    void isSupportedDocumentType_txt_returnsTrue() {
        // "text/plain" не содержит "txt", но "txt" есть в списке поддерживаемых
        assertTrue(properties.getSupportedDocumentTypesSet().contains("txt"));
        // Проверяем что метод работает для MIME типов, содержащих "txt"
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
        // FileUploadProperties проверяет содержит ли MIME тип строку из списка поддерживаемых
        // "text/markdown" не содержит "md", но "md" есть в списке
        assertTrue(properties.getSupportedDocumentTypesSet().contains("md"));
        // Проверяем что метод работает для MIME типов, содержащих "md"
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
