package ru.girchev.aibot.common.model;

/**
 * Модель вложения для передачи в AI провайдера.
 * Используется для multimodal запросов (изображения, PDF и т.д.).
 *
 * @param key уникальный ключ вложения (например, ключ в хранилище)
 * @param mimeType MIME-тип файла (image/png, image/jpeg, application/pdf и т.д.)
 * @param filename оригинальное имя файла
 * @param size размер файла в байтах
 * @param type тип вложения (IMAGE, PDF)
 * @param data бинарные данные файла
 */
public record Attachment(
        String key,
        String mimeType,
        String filename,
        long size,
        AttachmentType type,
        byte[] data
) {
    
    /**
     * Проверяет, является ли вложение изображением.
     */
    public boolean isImage() {
        return type == AttachmentType.IMAGE;
    }
    
    /**
     * Проверяет, является ли вложение PDF документом.
     */
    public boolean isPdf() {
        return type == AttachmentType.PDF && mimeType != null && mimeType.contains("pdf");
    }
    
    /**
     * Проверяет, является ли вложение документом (PDF, DOCX, DOC, XLS, XLSX, PPT, PPTX, TXT, RTF, ODT, ODS, ODP, CSV, HTML, MD, JSON, XML, EPUB и т.д.).
     */
    public boolean isDocument() {
        if (mimeType == null) {
            // Проверяем по расширению файла
            if (filename != null) {
                String name = filename.toLowerCase();
                return name.endsWith(".pdf") || name.endsWith(".docx") || name.endsWith(".doc") ||
                       name.endsWith(".xls") || name.endsWith(".xlsx") ||
                       name.endsWith(".ppt") || name.endsWith(".pptx") ||
                       name.endsWith(".txt") || name.endsWith(".rtf") ||
                       name.endsWith(".odt") || name.endsWith(".ods") || name.endsWith(".odp") ||
                       name.endsWith(".csv") || name.endsWith(".html") || name.endsWith(".htm") ||
                       name.endsWith(".md") || name.endsWith(".markdown") ||
                       name.endsWith(".json") || name.endsWith(".xml") ||
                       name.endsWith(".epub");
            }
            return type == AttachmentType.PDF;
        }
        String type = mimeType.toLowerCase();
        return type.contains("pdf") || 
               type.contains("msword") || 
               type.contains("wordprocessingml") || // docx
               type.contains("spreadsheetml") || // xlsx
               type.contains("ms-excel") || // xls
               type.contains("presentationml") || // pptx
               type.contains("ms-powerpoint") || // ppt
               type.contains("text/plain") || // txt
               type.contains("rtf") || // Rich Text Format
               type.contains("opendocument") || // ODT, ODS, ODP
               type.contains("csv") || // CSV
               type.contains("text/html") || // HTML
               type.contains("markdown") || // Markdown
               type.contains("json") || // JSON
               type.contains("xml") || // XML
               type.contains("epub") || // EPUB
               type.contains("document");
    }
}
