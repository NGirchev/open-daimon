package io.github.ngirchev.opendaimon.telegram.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.telegram.telegrambots.meta.api.objects.Document;
import org.telegram.telegrambots.meta.api.objects.File;
import org.telegram.telegrambots.meta.api.objects.PhotoSize;
import io.github.ngirchev.opendaimon.common.model.Attachment;
import io.github.ngirchev.opendaimon.common.model.AttachmentType;
import io.github.ngirchev.opendaimon.common.storage.service.FileStorageService;
import io.github.ngirchev.opendaimon.telegram.TelegramBot;
import io.github.ngirchev.opendaimon.telegram.config.FileUploadProperties;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

/**
 * Unit tests for {@link TelegramFileService} — file download and processing.
 * Uses a test subclass that overrides downloadFile to avoid network calls.
 */
@ExtendWith(MockitoExtension.class)
class TelegramFileServiceTest {

    private static final byte[] MOCK_PHOTO_DATA = "fake-jpeg-content".getBytes();
    private static final byte[] MOCK_DOCUMENT_DATA = "fake-pdf-content".getBytes();
    private static final long MAX_FILE_SIZE_BYTES = 5 * 1024 * 1024L; // 5 MB

    @Mock
    private ObjectProvider<TelegramBot> telegramBotProvider;
    @Mock
    private ObjectProvider<FileStorageService> fileStorageServiceProvider;
    @Mock
    private FileStorageService fileStorageService;
    @Mock
    private TelegramBot telegramBot;

    private FileUploadProperties fileUploadProperties;
    private TelegramFileService fileService;

    @BeforeEach
    void setUp() {
        fileUploadProperties = new FileUploadProperties();
        fileUploadProperties.setEnabled(true);
        fileUploadProperties.setMaxFileSizeMb(5);
        fileUploadProperties.setSupportedImageTypes("jpeg,png,gif,webp");
        fileUploadProperties.setSupportedDocumentTypes("pdf,txt,docx,doc,xls,xlsx,ppt,pptx,odt,ods,odp,rtf,html,md,csv,json,xml,epub");

        lenient().when(fileStorageServiceProvider.getObject()).thenReturn(fileStorageService);

        fileService = new TestableTelegramFileService(
                telegramBotProvider,
                fileStorageServiceProvider,
                fileUploadProperties
        );
    }

    // --- processPhoto ---

    @Test
    void whenProcessPhoto_photosNull_thenThrows() {
        assertThrows(IllegalArgumentException.class, () -> fileService.processPhoto(null));
        verify(fileStorageService, never()).save(any(), any(), any(), any());
    }

    @Test
    void whenProcessPhoto_photosEmpty_thenThrows() {
        assertThrows(IllegalArgumentException.class, () -> fileService.processPhoto(List.of()));
        verify(fileStorageService, never()).save(any(), any(), any(), any());
    }

    @Test
    void whenProcessPhoto_singlePhotoUnderLimit_thenReturnsAttachmentAndSaves() {
        PhotoSize photo = createPhotoSize("file-id-1", 100, 100, 1000);
        ((TestableTelegramFileService) fileService).setDownloadResult(MOCK_PHOTO_DATA);

        Attachment result = fileService.processPhoto(List.of(photo));

        assertNotNull(result);
        assertEquals(AttachmentType.IMAGE, result.type());
        assertEquals("image/jpeg", result.mimeType());
        assertTrue(result.filename().startsWith("photo_"));
        assertTrue(result.filename().endsWith(".jpg"));
        assertEquals(MOCK_PHOTO_DATA.length, result.size());
        assertArrayEquals(MOCK_PHOTO_DATA, result.data());
        assertTrue(result.key().startsWith("photo/"));
        assertTrue(result.key().endsWith(".jpg"));

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<byte[]> dataCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(fileStorageService).save(keyCaptor.capture(), dataCaptor.capture(), eq("image/jpeg"), anyString());
        assertArrayEquals(MOCK_PHOTO_DATA, dataCaptor.getValue());
    }

    @Test
    void whenProcessPhoto_multipleSizes_thenPicksLargestAndProcesses() {
        PhotoSize small = createPhotoSize("small", 50, 50, 500);
        PhotoSize large = createPhotoSize("large", 200, 200, 2000);
        ((TestableTelegramFileService) fileService).setDownloadResult(MOCK_PHOTO_DATA);

        Attachment result = fileService.processPhoto(List.of(small, large));

        assertNotNull(result);
        assertEquals("photo_large.jpg", result.filename());
        verify(fileStorageService).save(anyString(), eq(MOCK_PHOTO_DATA), eq("image/jpeg"), eq("photo_large.jpg"));
    }

    @Test
    void whenProcessPhoto_fileSizeOverLimit_thenThrows() {
        PhotoSize photo = createPhotoSize("file-id-1", 100, 100, (int) (MAX_FILE_SIZE_BYTES + 1));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> fileService.processPhoto(List.of(photo)));
        assertTrue(ex.getMessage().contains("File size exceeds maximum allowed"));

        verify(fileStorageService, never()).save(any(), any(), any(), any());
    }

    // --- processDocument ---

    @Test
    void whenProcessDocument_documentNull_thenThrows() {
        assertThrows(IllegalArgumentException.class, () -> fileService.processDocument(null));
        verify(fileStorageService, never()).save(any(), any(), any(), any());
    }

    @Test
    void whenProcessDocument_unsupportedMimeType_thenReturnsNull() {
        Document doc = createDocument("file-id-1", "application/xyz", "file.xyz", 100L);
        assertNull(fileService.processDocument(doc));
        verify(fileStorageService, never()).save(any(), any(), any(), any());
    }

    @Test
    void whenProcessDocument_pdfSupported_thenReturnsAttachmentAndSaves() {
        Document doc = createDocument("file-id-pdf", "application/pdf", "doc.pdf", 500L);
        ((TestableTelegramFileService) fileService).setDownloadResult(MOCK_DOCUMENT_DATA);

        Attachment result = fileService.processDocument(doc);

        assertNotNull(result);
        assertEquals(AttachmentType.PDF, result.type());
        assertEquals("application/pdf", result.mimeType());
        assertEquals("doc.pdf", result.filename());
        assertEquals(MOCK_DOCUMENT_DATA.length, result.size());
        assertArrayEquals(MOCK_DOCUMENT_DATA, result.data());
        assertTrue(result.key().startsWith("document/"));
        assertTrue(result.key().endsWith(".pdf"));

        verify(fileStorageService).save(anyString(), eq(MOCK_DOCUMENT_DATA), eq("application/pdf"), eq("doc.pdf"));
    }

    @Test
    void whenProcessDocument_fileSizeOverLimit_thenThrows() {
        Document doc = createDocument("file-id-1", "application/pdf", "big.pdf", MAX_FILE_SIZE_BYTES + 1);
        ((TestableTelegramFileService) fileService).setDownloadResult(MOCK_DOCUMENT_DATA);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> fileService.processDocument(doc));
        assertTrue(ex.getMessage().contains("File size exceeds maximum allowed"));

        verify(fileStorageService, never()).save(any(), any(), any(), any());
    }

    @Test
    void whenProcessDocument_nullFileName_thenUsesDefaultFilename() {
        Document doc = createDocument("file-id-1", "application/pdf", null, 100L);
        ((TestableTelegramFileService) fileService).setDownloadResult(MOCK_DOCUMENT_DATA);

        Attachment result = fileService.processDocument(doc);

        assertNotNull(result);
        assertTrue(result.filename().startsWith("document"));
        assertTrue(result.filename().endsWith(".pdf"));
        verify(fileStorageService).save(anyString(), any(), eq("application/pdf"), isNull());
    }

    // --- getFileInfo ---

    @Test
    void whenGetFileInfo_thenCallsBotAndReturnsFile() throws Exception {
        File expectedFile = new File();
        expectedFile.setFilePath("photos/file_1.jpg");
        when(telegramBotProvider.getObject()).thenReturn(telegramBot);
        when(telegramBot.execute(any(org.telegram.telegrambots.meta.api.methods.GetFile.class))).thenReturn(expectedFile);

        File result = fileService.getFileInfo("file-id-123");

        assertNotNull(result);
        assertEquals("photos/file_1.jpg", result.getFilePath());
        verify(telegramBotProvider).getObject();
        verify(telegramBot).execute(any(org.telegram.telegrambots.meta.api.methods.GetFile.class));
    }

    // --- downloadFile (via test subclass: real download not tested to avoid network) ---

    @Test
    void whenDownloadFile_usedByProcessPhoto_thenOverrideProvidesData() {
        PhotoSize photo = createPhotoSize("any-id", 10, 10, 10);
        byte[] customData = "custom-bytes".getBytes();
        ((TestableTelegramFileService) fileService).setDownloadResult(customData);

        Attachment result = fileService.processPhoto(List.of(photo));

        assertArrayEquals(customData, result.data());
        assertEquals(customData.length, result.size());
    }

    // --- Helpers ---

    private static PhotoSize createPhotoSize(String fileId, int width, int height, int fileSize) {
        PhotoSize p = new PhotoSize();
        p.setFileId(fileId);
        p.setWidth(width);
        p.setHeight(height);
        p.setFileSize(fileSize);
        return p;
    }

    private static Document createDocument(String fileId, String mimeType, String fileName, Long fileSize) {
        Document d = new Document();
        d.setFileId(fileId);
        d.setMimeType(mimeType);
        d.setFileName(fileName);
        d.setFileSize(fileSize);
        return d;
    }

    /**
     * Subclass that overrides downloadFile to return configurable bytes (no network).
     */
    private static final class TestableTelegramFileService extends TelegramFileService {
        private byte[] downloadResult = new byte[0];

        TestableTelegramFileService(
                ObjectProvider<TelegramBot> telegramBotProvider,
                ObjectProvider<FileStorageService> fileStorageServiceProvider,
                FileUploadProperties fileUploadProperties) {
            super(telegramBotProvider, fileStorageServiceProvider, fileUploadProperties);
        }

        void setDownloadResult(byte[] downloadResult) {
            this.downloadResult = downloadResult != null ? downloadResult : new byte[0];
        }

        @Override
        public byte[] downloadFile(String fileId) {
            return downloadResult;
        }
    }
}
