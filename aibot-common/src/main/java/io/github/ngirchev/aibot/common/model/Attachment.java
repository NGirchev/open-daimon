package io.github.ngirchev.aibot.common.model;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * Attachment model for passing to AI provider.
 * Used for multimodal requests (images, PDF, etc.).
 *
 * @param key unique attachment key (e.g. storage key)
 * @param mimeType file MIME type (image/png, image/jpeg, application/pdf, etc.)
 * @param filename original file name
 * @param size file size in bytes
 * @param type attachment type (IMAGE, PDF)
 * @param data file binary data
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
     * Checks if attachment is an image.
     */
    public boolean isImage() {
        return type == AttachmentType.IMAGE;
    }
    
    /**
     * Checks if attachment is a PDF document.
     */
    public boolean isPdf() {
        return type == AttachmentType.PDF && mimeType != null && mimeType.contains("pdf");
    }
    
    /**
     * Checks if attachment is a document (PDF, DOCX, DOC, XLS, XLSX, PPT, PPTX, TXT, RTF, ODT, ODS, ODP, CSV, HTML, MD, JSON, XML, EPUB, etc.).
     */
    public boolean isDocument() {
        if (mimeType == null) {
            // Check by file extension
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

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        Attachment that = (Attachment) o;
        return size == that.size && Objects.equals(key, that.key) && Objects.equals(mimeType, that.mimeType) && Objects.equals(filename, that.filename) && type == that.type;
    }

    @Override
    public int hashCode() {
        return Objects.hash(key, mimeType, filename, size, type);
    }

    @NotNull
    @Override
    public String toString() {
        return "Attachment{" +
                "key='" + key + '\'' +
                ", mimeType='" + mimeType + '\'' +
                ", filename='" + filename + '\'' +
                ", size=" + size +
                ", type=" + type +
                '}';
    }
}
