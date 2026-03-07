package ru.girchev.aibot.telegram.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.telegram.telegrambots.meta.api.methods.GetFile;
import org.telegram.telegrambots.meta.api.objects.Document;
import org.telegram.telegrambots.meta.api.objects.File;
import org.telegram.telegrambots.meta.api.objects.PhotoSize;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import ru.girchev.aibot.common.model.Attachment;
import ru.girchev.aibot.common.model.AttachmentType;
import ru.girchev.aibot.common.storage.service.FileStorageService;
import ru.girchev.aibot.telegram.TelegramBot;
import ru.girchev.aibot.telegram.config.FileUploadProperties;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.List;
import java.util.UUID;

/**
 * Сервис для работы с файлами в Telegram.
 * Обеспечивает скачивание файлов через Telegram API и сохранение в MinIO.
 */
@Slf4j
@RequiredArgsConstructor
public class TelegramFileService {

    private final ObjectProvider<TelegramBot> telegramBotProvider;
    private final ObjectProvider<FileStorageService> fileStorageServiceProvider;
    private final FileUploadProperties fileUploadProperties;

    /**
     * Обрабатывает фото из сообщения Telegram.
     * Выбирает фото с наибольшим разрешением, скачивает и сохраняет в MinIO.
     *
     * @param photos список объектов PhotoSize (разные размеры одного фото)
     * @return Attachment с данными о сохраненном файле
     */
    public Attachment processPhoto(List<PhotoSize> photos) {
        if (photos == null || photos.isEmpty()) {
            throw new IllegalArgumentException("Photos list is empty");
        }

        // Выбираем фото с максимальным разрешением
        PhotoSize largestPhoto = photos.stream()
                .max((p1, p2) -> {
                    int size1 = p1.getWidth() * p1.getHeight();
                    int size2 = p2.getWidth() * p2.getHeight();
                    return Integer.compare(size1, size2);
                })
                .orElseThrow(() -> new IllegalArgumentException("Cannot find largest photo"));

        String fileId = largestPhoto.getFileId();
        Integer fileSize = largestPhoto.getFileSize();

        // Проверка размера файла
        if (fileSize != null && fileSize > fileUploadProperties.getMaxFileSizeBytes()) {
            throw new IllegalArgumentException("File size exceeds maximum allowed: " + 
                    fileUploadProperties.getMaxFileSizeMb() + "MB");
        }

        // Скачиваем файл
        byte[] fileData = downloadFile(fileId);

        // Определяем MIME тип (Telegram фото всегда JPEG)
        String mimeType = "image/jpeg";
        String originalName = "photo_" + fileId + ".jpg";

        // Генерируем ключ для хранилища
        String storageKey = generateStorageKey("photo", ".jpg");

        // Сохраняем в MinIO
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
     * Обрабатывает документ из сообщения Telegram.
     * Скачивает и сохраняет в MinIO.
     *
     * @param document объект Document из Telegram
     * @return Attachment с данными о сохраненном файле или null если тип не поддерживается
     */
    public Attachment processDocument(Document document) {
        if (document == null) {
            throw new IllegalArgumentException("Document is null");
        }

        String fileId = document.getFileId();
        String mimeType = document.getMimeType();
        String originalName = document.getFileName();
        Long fileSize = document.getFileSize();

        // Проверяем поддержку типа
        if (!fileUploadProperties.isSupportedDocumentType(mimeType)) {
            log.warn("Unsupported document type: {}", mimeType);
            return null;
        }

        // Проверка размера файла
        if (fileSize != null && fileSize > fileUploadProperties.getMaxFileSizeBytes()) {
            throw new IllegalArgumentException("File size exceeds maximum allowed: " + 
                    fileUploadProperties.getMaxFileSizeMb() + "MB");
        }

        // Скачиваем файл
        byte[] fileData = downloadFile(fileId);

        // Определяем тип вложения
        AttachmentType attachmentType = determineAttachmentType(mimeType);

        // Генерируем ключ для хранилища
        String extension = getExtensionFromMimeType(mimeType);
        String storageKey = generateStorageKey("document", extension);

        // Сохраняем в MinIO
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
     * Скачивает файл из Telegram по fileId.
     *
     * @param fileId идентификатор файла в Telegram
     * @return содержимое файла
     */
    public byte[] downloadFile(String fileId) {
        try {
            TelegramBot bot = telegramBotProvider.getObject();
            
            // Получаем информацию о файле
            GetFile getFile = new GetFile();
            getFile.setFileId(fileId);
            File file = bot.execute(getFile);

            // Формируем URL для скачивания
            String fileUrl = "https://api.telegram.org/file/bot" + 
                    bot.getBotToken() + "/" + file.getFilePath();

            // Скачиваем файл
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
     * Получает информацию о файле из Telegram.
     *
     * @param fileId идентификатор файла в Telegram
     * @return объект File с информацией о файле
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
     * Генерирует уникальный ключ для хранилища.
     */
    private String generateStorageKey(String prefix, String extension) {
        return prefix + "/" + UUID.randomUUID() + extension;
    }

    /**
     * Определяет тип вложения по MIME типу.
     */
    private AttachmentType determineAttachmentType(String mimeType) {
        if (mimeType == null) {
            return AttachmentType.PDF; // По умолчанию для документов
        }
        String type = mimeType.toLowerCase();
        if (type.startsWith("image/")) {
            return AttachmentType.IMAGE;
        }
        return AttachmentType.PDF;
    }

    /**
     * Получает расширение файла по MIME типу.
     */
    private String getExtensionFromMimeType(String mimeType) {
        if (mimeType == null) return "";
        String type = mimeType.toLowerCase();
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
            default -> {
                // Проверяем по содержимому MIME типа
                if (type.contains("wordprocessingml")) {
                    yield ".docx";
                } else if (type.contains("msword")) {
                    yield ".doc";
                } else if (type.contains("spreadsheetml")) {
                    yield ".xlsx";
                } else if (type.contains("ms-excel")) {
                    yield ".xls";
                } else if (type.contains("presentationml")) {
                    yield ".pptx";
                } else if (type.contains("ms-powerpoint")) {
                    yield ".ppt";
                } else if (type.contains("text/plain")) {
                    yield ".txt";
                } else if (type.contains("rtf")) {
                    yield ".rtf";
                } else if (type.contains("opendocument.text")) {
                    yield ".odt";
                } else if (type.contains("opendocument.spreadsheet")) {
                    yield ".ods";
                } else if (type.contains("opendocument.presentation")) {
                    yield ".odp";
                } else if (type.contains("csv")) {
                    yield ".csv";
                } else if (type.contains("text/html")) {
                    yield ".html";
                } else if (type.contains("markdown")) {
                    yield ".md";
                } else if (type.contains("json")) {
                    yield ".json";
                } else if (type.contains("xml")) {
                    yield ".xml";
                } else if (type.contains("epub")) {
                    yield ".epub";
                } else if (type.contains("svg")) {
                    yield ".svg";
                } else if (type.contains("bmp")) {
                    yield ".bmp";
                } else if (type.contains("tiff")) {
                    yield ".tiff";
                }
                yield "";
            }
        };
    }
}
