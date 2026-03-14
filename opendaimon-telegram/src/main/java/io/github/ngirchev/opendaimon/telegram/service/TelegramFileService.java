package io.github.ngirchev.opendaimon.telegram.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.telegram.telegrambots.meta.api.methods.GetFile;
import org.telegram.telegrambots.meta.api.objects.Document;
import org.telegram.telegrambots.meta.api.objects.File;
import org.telegram.telegrambots.meta.api.objects.PhotoSize;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import io.github.ngirchev.opendaimon.common.model.Attachment;
import io.github.ngirchev.opendaimon.common.model.AttachmentType;
import io.github.ngirchev.opendaimon.common.storage.service.FileStorageService;
import io.github.ngirchev.opendaimon.telegram.TelegramBot;
import io.github.ngirchev.opendaimon.telegram.config.FileUploadProperties;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Service for Telegram file handling.
 * Downloads files via Telegram API and saves to MinIO.
 */
@Slf4j
@RequiredArgsConstructor
public class TelegramFileService {

    private final ObjectProvider<TelegramBot> telegramBotProvider;
    private final ObjectProvider<FileStorageService> fileStorageServiceProvider;
    private final FileUploadProperties fileUploadProperties;

    /**
     * Processes photo from Telegram message.
     * Picks largest resolution, downloads and saves to MinIO.
     *
     * @param photos list of PhotoSize (different sizes of same photo)
     * @return Attachment with saved file data
     */
    public Attachment processPhoto(List<PhotoSize> photos) {
        if (photos == null || photos.isEmpty()) {
            throw new IllegalArgumentException("Photos list is empty");
        }

        // Pick photo with max resolution
        PhotoSize largestPhoto = photos.stream()
                .max((p1, p2) -> {
                    int size1 = p1.getWidth() * p1.getHeight();
                    int size2 = p2.getWidth() * p2.getHeight();
                    return Integer.compare(size1, size2);
                })
                .orElseThrow(() -> new IllegalArgumentException("Cannot find largest photo"));

        String fileId = largestPhoto.getFileId();
        Integer fileSize = largestPhoto.getFileSize();

        // Check file size
        if (fileSize != null && fileSize > fileUploadProperties.getMaxFileSizeBytes()) {
            throw new IllegalArgumentException("File size exceeds maximum allowed: " + 
                    fileUploadProperties.getMaxFileSizeMb() + "MB");
        }

        // Download file
        byte[] fileData = downloadFile(fileId);

        // Determine MIME type (Telegram photos are always JPEG)
        String mimeType = "image/jpeg";
        String originalName = "photo_" + fileId + ".jpg";

        // Generate storage key
        String storageKey = generateStorageKey("photo", ".jpg");

        // Save to MinIO
        fileStorageServiceProvider.getObject().save(storageKey, fileData, mimeType, originalName);

        log.info("Processed photo: fileId={}, size={}, storageKey={}", fileId, fileData.length, storageKey);

        return new Attachment(
                storageKey,
                mimeType,
                originalName,
                fileData.length,
                AttachmentType.IMAGE,
                fileData
        );
    }

    /**
     * Processes document from Telegram message.
     * Downloads and saves to MinIO.
     *
     * @param document Telegram Document object
     * @return Attachment with saved file data or null if type not supported
     */
    public Attachment processDocument(Document document) {
        if (document == null) {
            throw new IllegalArgumentException("Document is null");
        }

        String fileId = document.getFileId();
        String mimeType = document.getMimeType();
        String originalName = document.getFileName();
        Long fileSize = document.getFileSize();

        // Check type support
        if (!fileUploadProperties.isSupportedDocumentType(mimeType)) {
            log.warn("Unsupported document type: {}", mimeType);
            return null;
        }

        // Check file size
        if (fileSize != null && fileSize > fileUploadProperties.getMaxFileSizeBytes()) {
            throw new IllegalArgumentException("File size exceeds maximum allowed: " + 
                    fileUploadProperties.getMaxFileSizeMb() + "MB");
        }

        // Download file
        byte[] fileData = downloadFile(fileId);

        // Determine attachment type
        AttachmentType attachmentType = determineAttachmentType(mimeType);

        // Generate storage key
        String extension = getExtensionFromMimeType(mimeType);
        String storageKey = generateStorageKey("document", extension);

        // Save to MinIO
        fileStorageServiceProvider.getObject().save(storageKey, fileData, mimeType, originalName);

        log.info("Processed document: fileId={}, mimeType={}, size={}, storageKey={}", 
                fileId, mimeType, fileData.length, storageKey);

        return new Attachment(
                storageKey,
                mimeType,
                originalName != null ? originalName : "document" + extension,
                fileData.length,
                attachmentType,
                fileData
        );
    }

    /**
     * Downloads file from Telegram by fileId.
     *
     * @param fileId file identifier in Telegram
     * @return file content
     */
    public byte[] downloadFile(String fileId) {
        try {
            TelegramBot bot = telegramBotProvider.getObject();
            
            // Get file info
            GetFile getFile = new GetFile();
            getFile.setFileId(fileId);
            File file = bot.execute(getFile);

            // Build download URL
            String fileUrl = "https://api.telegram.org/file/bot" + 
                    bot.getBotToken() + "/" + file.getFilePath();

            // Download file
            try (InputStream is = new URL(fileUrl).openStream()) {
                byte[] data = is.readAllBytes();
                log.debug("Downloaded file: fileId={}, size={}", fileId, data.length);
                return data;
            }
        } catch (TelegramApiException | IOException e) {
            log.error("Error downloading file: fileId={}", fileId, e);
            throw new RuntimeException("Failed to download file from Telegram", e);
        }
    }

    /**
     * Gets file info from Telegram.
     *
     * @param fileId file identifier in Telegram
     * @return File object with file info
     */
    public File getFileInfo(String fileId) {
        try {
            TelegramBot bot = telegramBotProvider.getObject();
            GetFile getFile = new GetFile();
            getFile.setFileId(fileId);
            return bot.execute(getFile);
        } catch (TelegramApiException e) {
            log.error("Error getting file info: fileId={}", fileId, e);
            throw new RuntimeException("Failed to get file info from Telegram", e);
        }
    }

    /**
     * Generates unique storage key.
     */
    private String generateStorageKey(String prefix, String extension) {
        return prefix + "/" + UUID.randomUUID() + extension;
    }

    /**
     * Determines attachment type from MIME type.
     */
    private AttachmentType determineAttachmentType(String mimeType) {
        if (mimeType == null) {
            return AttachmentType.PDF; // Default for documents
        }
        String type = mimeType.toLowerCase();
        if (type.startsWith("image/")) {
            return AttachmentType.IMAGE;
        }
        return AttachmentType.PDF;
    }

    /**
     * Gets file extension from MIME type.
     */
    private String getExtensionFromMimeType(String mimeType) {
        if (mimeType == null) return "";
        String type = mimeType.toLowerCase();
        String exact = extensionForExactMimeType(type);
        return exact != null ? exact : extensionFromMimeTypeContent(type);
    }

    /**
     * Returns extension for exact MIME type match, or null if not in the known list.
     */
    private static String extensionForExactMimeType(String type) {
        return switch (type) {
            case "application/pdf" -> ".pdf";
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> ".docx";
            case "application/msword" -> ".doc";
            case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> ".xlsx";
            case "application/vnd.ms-excel" -> ".xls";
            case "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> ".pptx";
            case "application/vnd.ms-powerpoint" -> ".ppt";
            case "text/plain" -> ".txt";
            case "application/rtf" -> ".rtf";
            case "application/vnd.oasis.opendocument.text" -> ".odt";
            case "application/vnd.oasis.opendocument.spreadsheet" -> ".ods";
            case "application/vnd.oasis.opendocument.presentation" -> ".odp";
            case "text/csv" -> ".csv";
            case "text/html" -> ".html";
            case "text/markdown" -> ".md";
            case "application/json" -> ".json";
            case "application/xml", "text/xml" -> ".xml";
            case "application/epub+zip" -> ".epub";
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/gif" -> ".gif";
            case "image/webp" -> ".webp";
            case "image/svg+xml" -> ".svg";
            case "image/bmp" -> ".bmp";
            case "image/tiff" -> ".tiff";
            default -> null;
        };
    }

    private static final List<Map.Entry<String, String>> MIME_CONTENT_TO_EXTENSION = List.of(
            Map.entry("wordprocessingml", ".docx"),
            Map.entry("msword", ".doc"),
            Map.entry("spreadsheetml", ".xlsx"),
            Map.entry("ms-excel", ".xls"),
            Map.entry("presentationml", ".pptx"),
            Map.entry("ms-powerpoint", ".ppt"),
            Map.entry("text/plain", ".txt"),
            Map.entry("rtf", ".rtf"),
            Map.entry("opendocument.text", ".odt"),
            Map.entry("opendocument.spreadsheet", ".ods"),
            Map.entry("opendocument.presentation", ".odp"),
            Map.entry("csv", ".csv"),
            Map.entry("text/html", ".html"),
            Map.entry("markdown", ".md"),
            Map.entry("json", ".json"),
            Map.entry("xml", ".xml"),
            Map.entry("epub", ".epub"),
            Map.entry("svg", ".svg"),
            Map.entry("bmp", ".bmp"),
            Map.entry("tiff", ".tiff")
    );

    private static String extensionFromMimeTypeContent(String type) {
        for (Map.Entry<String, String> e : MIME_CONTENT_TO_EXTENSION) {
            if (type.contains(e.getKey())) {
                return e.getValue();
            }
        }
        return "";
    }
}
