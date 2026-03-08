package io.github.ngirchev.aibot.telegram.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.Set;

/**
 * File upload configuration for Telegram bot.
 *
 * All values are required and must be specified in application.yml.
 */
@Validated
@Getter
@Setter
@ConfigurationProperties(prefix = "ai-bot.telegram.file-upload")
public class FileUploadProperties {

    /**
     * Enable file processing (feature flag).
     */
    @NotNull(message = "enabled is required")
    private Boolean enabled;

    /**
     * Maximum file size in megabytes (Telegram API limit = 20MB).
     */
    @NotNull(message = "maxFileSizeMb is required")
    @Min(value = 1, message = "maxFileSizeMb must be >= 1")
    private Integer maxFileSizeMb;

    /**
     * Supported image types (comma-separated: jpeg,png,gif,webp).
     */
    @NotNull(message = "supportedImageTypes is required")
    private String supportedImageTypes;

    /**
     * Supported document types (comma-separated: pdf).
     */
    @NotNull(message = "supportedDocumentTypes is required")
    private String supportedDocumentTypes;

    /**
     * Get set of supported image types.
     */
    public Set<String> getSupportedImageTypesSet() {
        return Set.of(supportedImageTypes.toLowerCase().split(","));
    }

    /**
     * Get set of supported document types.
     */
    public Set<String> getSupportedDocumentTypesSet() {
        return Set.of(supportedDocumentTypes.toLowerCase().split(","));
    }

    /**
     * Check if MIME type is supported as image.
     */
    public boolean isSupportedImageType(String mimeType) {
        if (mimeType == null) return false;
        String type = mimeType.toLowerCase();
        return getSupportedImageTypesSet().stream()
                .anyMatch(supported -> type.contains(supported));
    }

    /**
     * Check if MIME type is supported as document.
     */
    public boolean isSupportedDocumentType(String mimeType) {
        if (mimeType == null) return false;
        String type = mimeType.toLowerCase();
        return getSupportedDocumentTypesSet().stream()
                .anyMatch(supported -> type.contains(supported));
    }

    /**
     * Get maximum file size in bytes.
     */
    public long getMaxFileSizeBytes() {
        return maxFileSizeMb * 1024L * 1024L;
    }
}
