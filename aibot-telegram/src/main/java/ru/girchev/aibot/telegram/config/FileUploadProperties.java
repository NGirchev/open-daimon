package ru.girchev.aibot.telegram.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.Set;

/**
 * Конфигурация загрузки файлов в Telegram боте.
 * 
 * Все значения обязательны и должны быть указаны в application.yml.
 */
@Validated
@Getter
@Setter
@ConfigurationProperties(prefix = "ai-bot.telegram.file-upload")
public class FileUploadProperties {

    /**
     * Включить обработку файлов (feature flag).
     */
    @NotNull(message = "enabled обязателен")
    private Boolean enabled;

    /**
     * Максимальный размер файла в мегабайтах (лимит Telegram API = 20MB).
     */
    @NotNull(message = "maxFileSizeMb обязателен")
    @Min(value = 1, message = "maxFileSizeMb должен быть >= 1")
    private Integer maxFileSizeMb;

    /**
     * Поддерживаемые типы изображений (через запятую: jpeg,png,gif,webp).
     */
    @NotNull(message = "supportedImageTypes обязателен")
    private String supportedImageTypes;

    /**
     * Поддерживаемые типы документов (через запятую: pdf).
     */
    @NotNull(message = "supportedDocumentTypes обязателен")
    private String supportedDocumentTypes;

    /**
     * Получить Set поддерживаемых типов изображений.
     */
    public Set<String> getSupportedImageTypesSet() {
        return Set.of(supportedImageTypes.toLowerCase().split(","));
    }

    /**
     * Получить Set поддерживаемых типов документов.
     */
    public Set<String> getSupportedDocumentTypesSet() {
        return Set.of(supportedDocumentTypes.toLowerCase().split(","));
    }

    /**
     * Проверить, поддерживается ли MIME тип как изображение.
     */
    public boolean isSupportedImageType(String mimeType) {
        if (mimeType == null) return false;
        String type = mimeType.toLowerCase();
        return getSupportedImageTypesSet().stream()
                .anyMatch(supported -> type.contains(supported));
    }

    /**
     * Проверить, поддерживается ли MIME тип как документ.
     */
    public boolean isSupportedDocumentType(String mimeType) {
        if (mimeType == null) return false;
        String type = mimeType.toLowerCase();
        return getSupportedDocumentTypesSet().stream()
                .anyMatch(supported -> type.contains(supported));
    }

    /**
     * Получить максимальный размер файла в байтах.
     */
    public long getMaxFileSizeBytes() {
        return maxFileSizeMb * 1024L * 1024L;
    }
}
